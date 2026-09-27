package jp.rimtty.codematch.scanner.inateck

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.ble.BleAdapterLifecycleState
import jp.rimtty.codematch.scanner.ble.BleAvailability
import jp.rimtty.codematch.scanner.ble.BleDiscoveredDevice
import jp.rimtty.codematch.scanner.ble.BleScanCallbackDecoder
import jp.rimtty.codematch.scanner.ble.BleTransport
import jp.rimtty.codematch.scanner.ble.BleTransportEvent
import jp.rimtty.codematch.scanner.ble.BleTransportListener
import jp.rimtty.codematch.scanner.ble.BlePermissionState
import jp.rimtty.codematch.scanner.ble.BleTransportReadiness

/**
 * Maps the official Inateck SDK's high-level operations to the BLE safety core.
 *
 * `read` and `write` are logical settings operations here, not raw GATT
 * characteristic access. The SDK owns authentication, command framing, and
 * its FF00 endpoints. This transport never logs vendor replies or scan bytes.
 */
internal class InateckSdkTransport(
    private val gateway: InateckSdkGateway,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val scanDecoder: BleScanCallbackDecoder =
        InateckScanCallbackDecoder,
    private val scanDeliveryObserver: (InateckScanDeliveryKind) -> Unit = {},
) : BleTransport {
    private var discovering = false
    private var pendingDevice: ScannerDevice? = null
    private var activeDevice: ScannerDevice? = null
    private var discoveryGeneration = 0L
    /** Private callback epoch; never used as a coordinator request token. */
    private var connectionGeneration = 0L
    private var nextStandaloneGeneration = 0L
    private var currentRequestGeneration: Long? = null
    private var currentLinkGeneration: Long? = null
    private var closed = false
    /** Last effective connection readiness observed by the refresh bridge. */
    private var observedConnectionAvailability: BleAvailability? = null
    /** Readiness loss invalidates callbacks without retiring the physical link. */
    private var connectionReadinessInvalidated = false

    override var listener: BleTransportListener? = null

    /** Host observer of every discovered scanner (GATT-switch follow-up, #137). */
    var onDeviceDiscovered: ((ScannerDevice) -> Unit)? = null

    override val availability: BleAvailability
        get() = readGatewayReadiness().availability

    override val readiness: BleTransportReadiness
        get() = readGatewayReadiness()

    val isLinkActive: Boolean
        get() = activeDevice != null || pendingDevice != null

    /**
     * Reconcile dynamic SDK readiness with the BLE safety core.
     *
     * Our SDK gateway exposes Android readiness as a getter rather than as a
     * callback. The host therefore calls this method from its serialized
     * ticker and lifecycle/user-operation boundaries. A readiness transition
     * never retires a pending or active link: its physical close callback (or
     * a matching connection failure) remains the only authority that clears
     * link identity and generations.
     *
     * The return value is the sanitized snapshot used for this refresh. It is
     * useful to callback gates so a callback does not read the SDK twice after
     * a synchronous listener reaction.
     */
    fun refreshReadiness(): BleTransportReadiness {
        val current = readGatewayReadiness()
        if (closed) return current

        val previous = observedConnectionAvailability
        val effective = current.effectiveConnectionAvailability()
        observedConnectionAvailability = effective

        // Discovery depends on SCAN, but an established link depends on
        // CONNECT. A revoked SCAN grant must stop discovery only; it must not
        // demote a connected scanner when CONNECT is still available.
        val connectionReadinessChanged = previous != effective
        val shouldStopDiscovery = discovering &&
            current.failureReason(forConnection = false) != null
        val discoveryGenerationBeforeNotification = discoveryGeneration
        if (effective !is BleAvailability.Ready && isLinkActive) {
            // Keep this latch set across a transient recovery. A link that was
            // observed while CONNECT was unavailable must close and reconnect
            // before its callbacks can make the app Ready again.
            connectionReadinessInvalidated = true
        }

        // Do not publish a synthetic initial Ready/Unknown event. In
        // particular, an SDK that starts in Unknown and settles on Ready must
        // not cause a needless reconnect from the construction path. Once a
        // baseline exists, publish both loss and recovery so the core can
        // update availability UI; the core deliberately does not auto-connect
        // from the Ready event.
        if (connectionReadinessChanged &&
            (previous != null || (effective !is BleAvailability.Ready && isLinkActive))
        ) {
            listener?.onTransportEvent(BleTransportEvent.AvailabilityChanged(effective))
        }

        // Publish the connection transition before the discovery-stop event.
        // A listener may synchronously refresh readiness or start a new
        // discovery; generation/closed checks prevent this older snapshot from
        // stopping that newer operation.
        if (shouldStopDiscovery &&
            !closed &&
            discovering &&
            discoveryGeneration == discoveryGenerationBeforeNotification &&
            observedConnectionAvailability == effective
        ) {
            stopDiscoveryForReadiness()
        }
        return current
    }

    override fun startDiscovery(): Boolean {
        refreshReadiness()
        if (closed || discovering || isLinkActive || readiness.failureReason(forConnection = false) != null) {
            return false
        }
        discovering = true
        val discovery = ++discoveryGeneration
        listener?.onTransportEvent(BleTransportEvent.DiscoveryStarted)
        val accepted = gateway.startDiscovery(
            onDevice = { sdkDevice ->
                if (!closed && discovering && discovery == discoveryGeneration) {
                    val device = ScannerDevice(sdkDevice.id, sdkDevice.name)
                    listener?.onTransportEvent(
                        BleTransportEvent.DeviceFound(BleDiscoveredDevice(device)),
                    )
                    onDeviceDiscovered?.invoke(device)
                }
            },
            onFinished = {
                if (!closed && discovering && discovery == discoveryGeneration) {
                    discovering = false
                    listener?.onTransportEvent(BleTransportEvent.DiscoveryStopped)
                }
            },
        )
        if (!accepted && discovering) {
            discovering = false
            discoveryGeneration++
            listener?.onTransportEvent(BleTransportEvent.DiscoveryStopped)
        }
        return accepted
    }

    override fun stopDiscovery(): Boolean {
        refreshReadiness()
        if (closed || !discovering) return false
        return stopDiscoveryForReadiness()
    }

    override fun connect(device: ScannerDevice): Boolean {
        val generation = ++nextStandaloneGeneration
        return connect(device, generation, generation)
    }

    override fun connect(
        device: ScannerDevice,
        requestGeneration: Long,
        linkGeneration: Long,
    ): Boolean {
        refreshReadiness()
        if (closed || activeDevice != null || pendingDevice != null ||
            readiness.failureReason(forConnection = true) != null
        ) {
            return false
        }
        if (discovering) stopDiscovery()
        val epoch = ++connectionGeneration
        val request = requestGeneration
        val link = linkGeneration
        nextStandaloneGeneration = maxOf(nextStandaloneGeneration, request, link)
        currentRequestGeneration = request
        currentLinkGeneration = link
        pendingDevice = device
        connectionReadinessInvalidated = false
        var connectCompletionDelivered = false
        val accepted = runCatching {
            gateway.connect(
                deviceId = device.id,
                onScanBytes = { bytes -> acceptScanBytes(device, epoch, request, link, bytes) },
                onDisconnected = { unexpected ->
                    if (!closed && epoch == connectionGeneration) {
                        retireLink()
                        listener?.onTransportEvent(
                            BleTransportEvent.Disconnected(
                                device = device,
                                unexpected = unexpected,
                                requestGeneration = request,
                                linkGeneration = link,
                            ),
                        )
                    }
                },
                completion = connectionCompletion@{ result ->
                    if (closed || epoch != connectionGeneration || connectCompletionDelivered) {
                        return@connectionCompletion
                    }
                    connectCompletionDelivered = true
                    val currentReadiness = refreshReadiness()
                    // A refresh may synchronously cause the safety core to
                    // request/acknowledge a physical close. Re-check before
                    // allowing a late success to become an active link.
                    if (closed || epoch != connectionGeneration) return@connectionCompletion
                    if (result.isSuccess) {
                        if (!connectionCallbacksAllowed(currentReadiness)) {
                            return@connectionCompletion
                        }
                        pendingDevice = null
                        activeDevice = device
                        listener?.onTransportEvent(
                            BleTransportEvent.Connected(
                                device = device,
                                requestGeneration = request,
                                linkGeneration = link,
                            ),
                        )
                    } else {
                        retireLink()
                        listener?.onTransportEvent(
                            BleTransportEvent.ConnectionFailed(
                                device = device,
                                reason = "Inateck scanner connection failed",
                                requestGeneration = request,
                                linkGeneration = link,
                            ),
                        )
                    }
                },
            )
        }.getOrDefault(false)
        // A gateway can synchronously reject after its readiness changed. Read
        // the state once more before handling that rejection so the bridge can
        // notify the coordinator without exposing the SDK exception/details.
        refreshReadiness()
        if (!accepted && epoch == connectionGeneration && pendingDevice != null) {
            retireLink()
        }
        return accepted
    }

    override fun disconnect(device: ScannerDevice): Boolean {
        refreshReadiness()
        val target = (activeDevice ?: pendingDevice)?.takeIf { it.id == device.id } ?: return false
        val epoch = connectionGeneration
        val request = currentRequestGeneration ?: return false
        val link = currentLinkGeneration ?: return false
        val accepted = gateway.disconnect(target.id) completion@{ result ->
            if (!closed && epoch == connectionGeneration) {
                if (result.isFailure) {
                    // The SDK callback reports that the close operation did
                    // not complete. Keep activeDevice/pendingDevice intact
                    // and tell the safety core explicitly; emitting
                    // Disconnected here would permit a replacement link to
                    // overlap a still-live GATT connection.
                    listener?.onTransportEvent(
                        BleTransportEvent.DisconnectFailed(
                            device = device,
                            requestGeneration = request,
                            linkGeneration = link,
                        ),
                    )
                    return@completion
                }
                retireLink()
                listener?.onTransportEvent(
                    BleTransportEvent.Disconnected(
                        device = device,
                        unexpected = false,
                        requestGeneration = request,
                        linkGeneration = link,
                    ),
                )
            }
        }
        return accepted
    }

    override fun read(
        characteristicUuid: String,
        completion: (Result<ByteArray>) -> Unit,
    ): Boolean {
        val currentReadiness = refreshReadiness()
        if (characteristicUuid != INATECK_SETTINGS_ENDPOINT) {
            return completeReadFailure(completion)
        }
        val device = activeDevice ?: return completeReadFailure(completion)
        if (!connectionCallbacksAllowed(currentReadiness)) {
            return completeReadFailure(completion, READINESS_UNAVAILABLE_REASON)
        }
        val epoch = connectionGeneration
        return gateway.readSettings(device.id) { result ->
            if (closed || epoch != connectionGeneration) return@readSettings
            val callbackReadiness = refreshReadiness()
            if (closed || epoch != connectionGeneration) return@readSettings
            if (!connectionCallbacksAllowed(callbackReadiness)) {
                completion(Result.failure(IllegalStateException(READINESS_UNAVAILABLE_REASON)))
                return@readSettings
            }
            completion(
                result.mapCatching { settings ->
                    settingsEnvelope(settings).toByteArray(StandardCharsets.UTF_8)
                },
            )
        }
    }

    override fun write(
        characteristicUuid: String,
        payload: ByteArray,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        val currentReadiness = refreshReadiness()
        if (characteristicUuid != INATECK_SETTINGS_ENDPOINT) {
            return completeWriteFailure(completion)
        }
        val device = activeDevice ?: return completeWriteFailure(completion)
        if (!connectionCallbacksAllowed(currentReadiness)) {
            return completeWriteFailure(completion, READINESS_UNAVAILABLE_REASON)
        }
        val command = strictUtf8(payload) ?: return completeWriteFailure(completion)
        val epoch = connectionGeneration
        return gateway.writeSettings(device.id, command) { result ->
            if (closed || epoch != connectionGeneration) return@writeSettings
            val callbackReadiness = refreshReadiness()
            if (closed || epoch != connectionGeneration) return@writeSettings
            if (!connectionCallbacksAllowed(callbackReadiness)) {
                completion(Result.failure(IllegalStateException(READINESS_UNAVAILABLE_REASON)))
                return@writeSettings
            }
            completion(result)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        discovering = false
        discoveryGeneration++
        retireLink()
        listener = null
        gateway.close()
    }

    private fun acceptScanBytes(
        device: ScannerDevice,
        epoch: Long,
        request: Long,
        link: Long,
        bytes: ByteArray,
    ) {
        val currentReadiness = refreshReadiness()
        if (closed || epoch != connectionGeneration || activeDevice?.id != device.id ||
            !connectionCallbacksAllowed(currentReadiness)
        ) {
            scanDeliveryObserver(InateckScanDeliveryKind.STALE)
            return
        }
        val callbackValue = strictUtf8(bytes) ?: run {
            scanDeliveryObserver(InateckScanDeliveryKind.INVALID_UTF8)
            return
        }
        val event = BleTransportEvent.ScanReceived.fromRawCallback(
            callbackValue = callbackValue,
            source = InputSource.BLUETOOTH,
            // FF01 does not carry a trustworthy symbology label. QR is only
            // an adapter placeholder; BleScannerSessionCoordinator replaces
            // it with the authoritative logical step before delivery.
            format = ScanFormat.QR,
            timestampMillis = nowMillis(),
            device = device,
            requestGeneration = request,
            linkGeneration = link,
            decoder = scanDecoder,
        ) ?: run {
            scanDeliveryObserver(InateckScanDeliveryKind.DECODER_REJECTED)
            return
        }
        scanDeliveryObserver(InateckScanDeliveryKind.DELIVERED)
        listener?.onTransportEvent(event)
    }

    /** Invalidate old callbacks before publishing the terminal event. */
    private fun retireLink() {
        pendingDevice = null
        activeDevice = null
        currentRequestGeneration = null
        currentLinkGeneration = null
        connectionReadinessInvalidated = false
        connectionGeneration++
    }

    /** Readiness is adapter state; it is not evidence that a link is closed. */
    private fun connectionCallbacksAllowed(readiness: BleTransportReadiness): Boolean {
        if (readiness.failureReason(forConnection = true) != null) {
            connectionReadinessInvalidated = true
            return false
        }
        return !connectionReadinessInvalidated
    }

    private fun stopDiscoveryForReadiness(): Boolean {
        if (closed || !discovering) return false
        discovering = false
        discoveryGeneration++
        val accepted = runCatching { gateway.stopDiscovery() }.getOrDefault(false)
        listener?.onTransportEvent(BleTransportEvent.DiscoveryStopped)
        return accepted
    }

    private fun readGatewayReadiness(): BleTransportReadiness = runCatching {
        gateway.readiness
    }.getOrElse { FAILED_READINESS }

    private fun BleTransportReadiness.effectiveConnectionAvailability(): BleAvailability = when {
        lifecycle == BleAdapterLifecycleState.DESTROYED ->
            BleAvailability.Failed("Bluetooth adapter is closed")
        lifecycle == BleAdapterLifecycleState.BACKGROUND ->
            BleAvailability.Failed("Bluetooth adapter is inactive")
        availability !is BleAvailability.Ready -> availability
        connectionPermission != BlePermissionState.GRANTED ->
            BleAvailability.Unauthorized
        else -> BleAvailability.Ready
    }

    private fun settingsEnvelope(settings: List<Map<String, String>>): String {
        require(settings.isNotEmpty()) { "Inateck settings inventory is empty" }
        val root = JsonObject()
        root.add("data", Gson().toJsonTree(settings))
        return Gson().toJson(root)
    }

    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun completeReadFailure(
        completion: (Result<ByteArray>) -> Unit,
        reason: String = "Inateck scanner is not connected",
    ): Boolean {
        completion(Result.failure(IllegalStateException(reason)))
        return false
    }

    private fun completeWriteFailure(
        completion: (Result<Unit>) -> Unit,
        reason: String = "Inateck scanner is not connected",
    ): Boolean {
        completion(Result.failure(IllegalStateException(reason)))
        return false
    }

    private companion object {
        const val READINESS_UNAVAILABLE_REASON = "Inateck scanner readiness unavailable"
        val FAILED_READINESS = BleTransportReadiness(
            lifecycle = BleAdapterLifecycleState.FOREGROUND,
            availability = BleAvailability.Failed("Bluetooth readiness unavailable"),
            discoveryPermission = BlePermissionState.UNKNOWN,
            connectionPermission = BlePermissionState.UNKNOWN,
        )
    }
}

/** Payload-free stages used by the local PoC diagnostic trace. */
internal enum class InateckScanDeliveryKind {
    STALE,
    INVALID_UTF8,
    DECODER_REJECTED,
    DELIVERED,
}
