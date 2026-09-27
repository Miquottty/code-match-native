package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Deterministic discovery/connection/reconnection coordinator.
 *
 * The platform adapter owns Android Bluetooth callbacks. This class owns the
 * app-visible state and persistence-independent reconnect policy. Call
 * [onTransportEvent] from the adapter's serialized callback context and call
 * [tick] from the host coroutine/timer to perform deadline handling and a
 * scheduled reconnect. Discovery, connection, and reconnect deadlines are
 * respectively 5s, 30s, and 8s by default.
 *
 * Adapters that can tag callbacks should copy [pendingRequestGeneration] and
 * [pendingLinkGeneration] into connection events, and the accepted current
 * tokens into scan/disconnect events. Untagged legacy events are accepted only
 * when their device and current state make them unambiguous.
 */
class BleConnectionCoordinator(
    private val transport: BleTransport,
    private val diagnostics: BleDiagnosticLog = BleDiagnosticLog(),
    private val reconnectDelayMillis: (attempt: Int) -> Long = ::defaultReconnectDelayMillis,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val discoveryTimeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    /** Optional app-private store for a manually retained known scanner. */
    private val knownDeviceStore: KnownDeviceStore? = null,
    /** Optional foreground-only retry interval after the initial budget. */
    private val sustainedReconnectDelayMillis: Long? = null,
) : BleTransportListener {
    private enum class DisconnectIntent {
        MANUAL,
        RECONNECT,
    }

    init {
        require(maxReconnectAttempts > 0) { "maxReconnectAttempts must be positive" }
        require(sustainedReconnectDelayMillis == null || sustainedReconnectDelayMillis > 0)
        require(discoveryTimeoutMillis > 0) { "discoveryTimeoutMillis must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        transport.listener = this
    }

    private val mutableDevices = linkedMapOf<String, BleDiscoveredDevice>()
    private var applicationActive = true
    private var preferredDevice: ScannerDevice? = null
    private var reconnectAttempt = 0
    private var reconnectAtMillis: Long? = null
    private var manualDisconnect = false
    private var disconnectIntent: DisconnectIntent? = null
    /** True while the adapter has accepted a close request without a callback. */
    private var disconnectRequestInFlight = false
    /** Distinguishes a reentrant close request from its outer transport call. */
    private var disconnectRequestSequence = 0L
    /** Logical time used by callbacks delivered reentrantly from a transport call. */
    private var operationAtMillis: Long? = null
    private var discoveryStartedAtMillis: Long? = null
    private var connectStartedAtMillis: Long? = null
    private var pendingConnectDevice: ScannerDevice? = null
    private var pendingConnectRequestGeneration: Long? = null
    private var pendingPhysicalLinkGeneration: Long? = null
    private var activeDevice: ScannerDevice? = null
    private var activeRequestGeneration: Long? = null
    private var activeLinkGeneration: Long? = null
    private var nextRequestGeneration = 0L
    private var nextLinkGeneration = 0L
    private var mutableExpectedFormat: ScanFormat? = null
    private var mutableState: BleScannerState = BleScannerState()
    private var listener: BleScannerListener? = null
    private val payloadGate = BleScanPayloadGate(nowMillis = nowMillis)
    private var knownDeviceReadResult: BleKnownDeviceReadResult =
        BleKnownDeviceReadResult.Missing

    init {
        preferredDevice = readKnownDevice()
    }

    val state: BleScannerState get() = mutableState
    val connectionState: BleConnectionState get() = mutableState.connection
    val configurationState: ConfigurationState get() = mutableState.configuration
    val devices: List<ScannerDevice> get() = mutableDevices.values.map(BleDiscoveredDevice::device)
    val expectedFormat: ScanFormat? get() = mutableExpectedFormat
    val pendingReconnectAtMillis: Long? get() = reconnectAtMillis
    val reconnectAttemptCount: Int get() = reconnectAttempt
    val knownDevice: ScannerDevice? get() = preferredDevice
    /** Result of the last identity-aware read from [knownDeviceStore]. */
    val persistedKnownDevice: BleKnownDeviceReadResult get() = knownDeviceReadResult
    /** Token the adapter may attach to the next connection callback. */
    val pendingRequestGeneration: Long? get() = pendingConnectRequestGeneration
    /** Token the adapter may attach to the next physical-link callback. */
    val pendingLinkGeneration: Long? get() = pendingPhysicalLinkGeneration
    /** Last accepted request token, retained to reject stale callbacks. */
    val currentRequestGeneration: Long? get() = activeRequestGeneration
    /** Last accepted physical-link token, retained to reject stale callbacks. */
    val currentLinkGeneration: Long? get() = activeLinkGeneration
    /**
     * Whether the adapter still owns a physical or pending connection. A
     * failed disconnect deliberately keeps this true until a matching
     * [BleTransportEvent.Disconnected] arrives.
     */
    val hasPhysicalLink: Boolean
        get() = activeDevice != null || pendingConnectDevice != null

    /** Whether a link has been established (not merely requested). */
    val hasEstablishedLink: Boolean
        get() = activeDevice != null

    /** Whether the current link (pending or established) is being closed. */
    val isClosingLink: Boolean
        get() = disconnectIntent != null

    /** Whether a close request has been accepted and awaits its callback. */
    val isCloseInFlight: Boolean
        get() = disconnectRequestInFlight

    /** The device of a requested connection that is not established yet. */
    val connectingDevice: ScannerDevice?
        get() = pendingConnectDevice

    fun setListener(listener: BleScannerListener?) {
        this.listener = listener
    }

    fun startDiscovery(): Boolean {
        // Discovery shares the public connection state. Do not replace a
        // selected link's state/settings owner with Searching before closure.
        if (hasPhysicalLink || connectionState == BleConnectionState.Searching) return false
        val readiness = readTransportReadiness()
        val failure = readiness.failureReason(forConnection = false)
        if (failure != null) {
            transition(connection = readiness.asConnectionState(forConnection = false))
            diagnostics.error(failure)
            return false
        }
        // Explicit discovery supersedes an idle automatic reconnect. Do this
        // before notifying listeners so its timer cannot race the new search.
        reconnectAtMillis = null
        transition(connection = BleConnectionState.Searching)
        discoveryStartedAtMillis = nowMillis()
        diagnostics.connection("Discovery requested")
        val accepted = try {
            transport.startDiscovery()
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            discoveryStartedAtMillis = null
            transition(connection = BleConnectionState.Failed("Bluetooth discovery could not start"))
            diagnostics.error("Discovery start failed")
        }
        return accepted
    }

    fun stopDiscovery(): Boolean {
        if (connectionState != BleConnectionState.Searching) return false
        discoveryStartedAtMillis = null
        val accepted = try {
            transport.stopDiscovery()
        } catch (_: Exception) {
            false
        }
        transition(connection = BleConnectionState.Idle)
        diagnostics.connection("Discovery stopped")
        return accepted
    }

    fun connect(device: ScannerDevice): Boolean {
        if (activeDevice != null || pendingConnectDevice != null || disconnectIntent != null) {
            return false
        }
        return beginConnectionAttempt(device, reconnecting = false)
    }

    /**
     * Starts a known-device connection with the reconnect policy enabled.
     *
     * A process-recreated scanner can have a persisted identity while the
     * radio or runtime permission is temporarily unavailable. Treating this
     * as an ordinary user connect used to leave the coordinator in
     * [BleConnectionState.Unavailable] with no future attempt scheduled. The
     * host ticker would therefore never notice that Bluetooth recovered while
     * the app stayed in the foreground.
     */
    private fun beginConnectionAttempt(
        device: ScannerDevice,
        reconnecting: Boolean,
        retryOnFailure: Boolean = reconnecting,
    ): Boolean {
        if (!rememberKnownDevice(device)) {
            transition(
                connection = BleConnectionState.Failed(
                    "Known scanner identity could not be saved",
                ),
                configuration = ConfigurationState.Unavailable,
            )
            return false
        }
        reconnectAtMillis = null
        if (!reconnecting) reconnectAttempt = 0
        manualDisconnect = false
        payloadGate.reset()
        preferredDevice = device
        mutableDevices[device.id] = BleDiscoveredDevice(device)
        return startConnectionAttempt(
            device = device,
            reconnecting = reconnecting,
            retryOnFailure = retryOnFailure,
        )
    }

    fun disconnect(): Boolean {
        val device = activeDevice ?: pendingConnectDevice ?: return false
        // A transport can reject a disconnect synchronously, or report an
        // asynchronous failure while retaining the physical link. In that
        // terminal state a later explicit disconnect is a safe retry; every
        // other in-flight request remains idempotent.
        if (disconnectIntent == DisconnectIntent.MANUAL &&
            disconnectRequestInFlight
        ) {
            return true
        }
        if (disconnectIntent == DisconnectIntent.RECONNECT &&
            disconnectRequestInFlight
        ) {
            // A user cancellation supersedes an automatic close that is
            // already accepted. Keep that request in flight, but retain the
            // manual intent so its eventual acknowledgement cannot schedule a
            // reconnect.
            manualDisconnect = true
            disconnectIntent = DisconnectIntent.MANUAL
            reconnectAtMillis = null
            reconnectAttempt = 0
            return true
        }
        manualDisconnect = true
        disconnectIntent = DisconnectIntent.MANUAL
        reconnectAtMillis = null
        reconnectAttempt = 0
        discoveryStartedAtMillis = null
        // Keep the active request/link tokens until the adapter's disconnect
        // callback arrives; that callback is still relevant to this link.
        connectStartedAtMillis = null
        return requestPhysicalDisconnect(
            device = device,
            intent = DisconnectIntent.MANUAL,
            failureReason = "Bluetooth disconnect could not start",
            acceptedMessage = "Manual disconnect requested",
            failureMessage = "Disconnect start failed",
        )
    }

    /**
     * Operator selection of [device] (#137). One scanner is used at a time, so
     * choosing a scanner pre-empts every automatic recovery of the previous
     * one: the selection becomes the known/preferred device at once (failure
     * retries then target it), the reconnect timer and budget are cleared, a
     * running discovery is stopped, a requested but not yet established link
     * to another scanner is closed without scheduling a reconnect, and a close
     * already in flight no longer reconnects the old scanner.
     *
     * The caller still connects [device] itself once no link remains. Returns
     * false, without changing anything, while an established link is not
     * being closed (the operator must disconnect it first), or when the
     * identity cannot be persisted.
     */
    fun preferSelectedDevice(device: ScannerDevice): Boolean {
        if (activeDevice != null && disconnectIntent == null) return false
        if (!rememberKnownDevice(device)) {
            transition(
                connection = BleConnectionState.Failed(
                    "Known scanner identity could not be saved",
                ),
                configuration = ConfigurationState.Unavailable,
            )
            return false
        }
        preferredDevice = device
        if (!mutableDevices.containsKey(device.id)) {
            mutableDevices[device.id] = BleDiscoveredDevice(device)
        }
        reconnectAtMillis = null
        reconnectAttempt = 0
        if (connectionState == BleConnectionState.Searching) stopDiscovery()
        val pending = pendingConnectDevice
        if (activeDevice == null && pending != null && pending.id != device.id &&
            !disconnectRequestInFlight
        ) {
            requestPhysicalDisconnect(
                device = pending,
                intent = DisconnectIntent.MANUAL,
                failureReason = "Bluetooth disconnect could not start",
                acceptedMessage = "Pending connection cancelled for the selected scanner",
                failureMessage = "Pending connection cancel failed",
            )
        }
        if (disconnectIntent == DisconnectIntent.RECONNECT) {
            // A reset of the previous scanner must not turn into its reconnect.
            disconnectIntent = DisconnectIntent.MANUAL
            manualDisconnect = true
        }
        diagnostics.connection("Scanner selected by the operator")
        emitState()
        return true
    }

    /**
     * Close a requested but not yet established link on the operator's behalf
     * (for example to search while an automatic reconnect is pending). No
     * reconnect is scheduled for it. Returns false when there is no such link.
     */
    fun cancelPendingConnection(): Boolean {
        val pending = pendingConnectDevice ?: return false
        if (activeDevice != null) return false
        reconnectAtMillis = null
        reconnectAttempt = 0
        if (disconnectIntent != null) {
            disconnectIntent = DisconnectIntent.MANUAL
            manualDisconnect = true
            if (disconnectRequestInFlight) return true
        }
        return requestPhysicalDisconnect(
            device = pending,
            intent = DisconnectIntent.MANUAL,
            failureReason = "Bluetooth disconnect could not start",
            acceptedMessage = "Pending connection cancelled",
            failureMessage = "Pending connection cancel failed",
        )
    }

    /**
     * Stop treating [deviceId] as the known scanner: automatic reconnects to
     * it end and its persisted identity is cleared. Used when a scanner is
     * expected to come back under another identity (GATT mode switch).
     */
    fun forgetKnownDevice(deviceId: String) {
        if (preferredDevice?.id == deviceId) {
            preferredDevice = null
            reconnectAtMillis = null
            reconnectAttempt = 0
        }
        val store = knownDeviceStore ?: return
        val result = runCatching { store.clear(expectedDeviceId = deviceId) }.getOrNull()
        if (result == BleKnownDeviceClearResult.Cleared) {
            knownDeviceReadResult = BleKnownDeviceReadResult.Missing
        }
    }

    /**
     * Publish an operator-facing failure while no link exists (for example
     * when a renamed scanner must be picked from the search results).
     */
    fun publishFailure(reason: String): Boolean {
        if (hasPhysicalLink) return false
        transition(
            connection = BleConnectionState.Failed(reason),
            configuration = ConfigurationState.Unavailable,
        )
        return true
    }

    /** Append a payload-free adapter diagnostic and publish it. */
    fun recordDiagnostic(message: String, error: Boolean = false) {
        if (error) diagnostics.error(message) else diagnostics.connection(message)
        emitState()
    }

    fun reconnectKnownDevice(): Boolean {
        val device = preferredDevice ?: readKnownDevice() ?: return false
        // Explicit user/foreground recovery starts a new bounded sequence,
        // including when an exhausted sequence still owns a link to close.
        reconnectAttempt = 0
        if (disconnectIntent != null) {
            if (disconnectRequestInFlight) {
                disconnectIntent = DisconnectIntent.RECONNECT
                manualDisconnect = false
                return true
            }
            // A failed disconnect left the adapter owning the old link. Retry
            // the close first; never call connect() over that link.
            val current = activeDevice ?: pendingConnectDevice ?: return false
            return requestPhysicalDisconnect(
                device = current,
                intent = DisconnectIntent.RECONNECT,
                failureReason = "Bluetooth reconnect reset could not start",
                acceptedMessage = "Reconnect reset requested",
                failureMessage = "Reconnect reset start failed",
            )
        }
        if (activeDevice != null || pendingConnectDevice != null) {
            if (activeDevice != null && configurationState.isReady) return true
            val current = activeDevice ?: pendingConnectDevice ?: return false
            return requestPhysicalDisconnect(
                device = current,
                intent = DisconnectIntent.RECONNECT,
                failureReason = "Bluetooth reconnect reset could not start",
                acceptedMessage = "Reconnect reset requested",
                failureMessage = "Reconnect reset start failed",
            )
        }
        reconnectAtMillis = null
        reconnectAttempt = 0
        // Preserve the existing immediate API state (Connecting) while still
        // enabling bounded retries if readiness is temporarily unavailable.
        return beginConnectionAttempt(
            device = device,
            reconnecting = false,
            retryOnFailure = true,
        )
    }

    /**
     * Re-read the persisted identity after a service/process recreation.
     *
     * This only restores an identity candidate. It never claims that the
     * scanner is connected or ready; callers must still perform the normal
     * connect and fresh-settings/recovery handshake.
     */
    fun loadKnownDevice(): ScannerDevice? {
        val device = readKnownDevice()
        if (device != null) preferredDevice = device
        return device
    }

    fun setApplicationActive(active: Boolean, atMillis: Long = nowMillis()) {
        applicationActive = active
        if (!active) {
            reconnectAtMillis = null
            if (connectionState == BleConnectionState.Searching) stopDiscovery()
            return
        }
        if (connectionState is BleConnectionState.Failed ||
            connectionState is BleConnectionState.Unavailable
        ) {
            scheduleReconnect(atMillis)
        }
    }

    /**
     * Physical scanner mode is intentionally fixed for a session. Changing
     * QR→Code128 is a logical app step and does not call GATT.
     */
    fun setExpectedFormat(format: ScanFormat?) {
        mutableExpectedFormat = format
        val mode = BleSymbologyMode.forExpectedFormat(format)
        diagnostics.configuration(
            if (mode == BleSymbologyMode.UNRESTRICTED) {
                "Scanner session mode cleared"
            } else {
                "Scanner session mode is fixed to QR and Code 128"
            },
        )
        emitState()
    }

    /** Host calls this after it has applied/verified a GATT setting command. */
    fun markConfiguration(state: ConfigurationState) {
        // Availability recovery is not a close acknowledgement. Keep a late
        // settings callback from publishing Ready while the adapter still
        // owns an active or pending link that must be closed first.
        if (connectionState !is BleConnectionState.Connected || disconnectIntent != null) {
            transition(configuration = ConfigurationState.Unavailable)
            return
        }
        transition(configuration = state)
    }

    /**
     * Feed adapter events. Notification payloads are forwarded only as typed
     * [ScanPayload] values; this method never sends them to diagnostics.
     */
    override fun onTransportEvent(event: BleTransportEvent) {
        when (event) {
            BleTransportEvent.DiscoveryStarted -> {
                if (discoveryStartedAtMillis == null) {
                    discoveryStartedAtMillis = nowMillis()
                }
                transition(connection = BleConnectionState.Searching)
            }
            is BleTransportEvent.DeviceFound -> {
                mutableDevices[event.device.device.id] = event.device
                emitState()
            }
            BleTransportEvent.DiscoveryStopped -> {
                discoveryStartedAtMillis = null
                if (connectionState == BleConnectionState.Searching) {
                    transition(connection = BleConnectionState.Idle)
                }
            }
            is BleTransportEvent.Connected -> {
                if (!acceptConnectedEvent(event)) return
                discoveryStartedAtMillis = null
                connectStartedAtMillis = null
                activeRequestGeneration = event.requestGeneration ?: pendingConnectRequestGeneration
                activeLinkGeneration = event.linkGeneration ?: pendingPhysicalLinkGeneration
                pendingConnectDevice = null
                pendingConnectRequestGeneration = null
                pendingPhysicalLinkGeneration = null
                activeDevice = event.device
                preferredDevice = event.device
                mutableDevices[event.device.id] = BleDiscoveredDevice(event.device)
                if (disconnectIntent != null) {
                    // A cancellation request may race the SDK's final connect
                    // callback. Never publish this late link as Connected or
                    // start a settings read; close it first.
                    requestPhysicalDisconnect(
                        device = event.device,
                        intent = disconnectIntent!!,
                        failureReason = "Bluetooth disconnect could not start",
                        acceptedMessage = "Connection reset requested",
                        failureMessage = "Late connection reset failed",
                        requestedAtMillis = operationNowMillis(),
                    )
                    return
                }
                reconnectAttempt = 0
                reconnectAtMillis = null
                payloadGate.reset()
                transition(
                    connection = BleConnectionState.Connected(event.device),
                    configuration = ConfigurationState.Configuring,
                )
                diagnostics.connection("Scanner connected")
            }
            is BleTransportEvent.ConnectionFailed -> {
                if (!acceptConnectionFailureEvent(event)) return
                val shouldReconnect = disconnectIntent == DisconnectIntent.RECONNECT
                val wasManual = disconnectIntent == DisconnectIntent.MANUAL || manualDisconnect
                disconnectIntent = null
                manualDisconnect = false
                disconnectRequestInFlight = false
                connectStartedAtMillis = null
                pendingConnectDevice = null
                pendingConnectRequestGeneration = null
                pendingPhysicalLinkGeneration = null
                preferredDevice = event.device
                transition(connection = BleConnectionState.Failed(event.reason))
                diagnostics.error("Scanner connection failed")
                if (shouldReconnect || !wasManual) scheduleReconnect(operationNowMillis())
            }
            is BleTransportEvent.DisconnectFailed -> {
                if (!acceptDisconnectFailedEvent(event)) return
                disconnectRequestInFlight = false
                // This is intentionally not a Disconnected transition: the
                // adapter explicitly could not prove that the old physical
                // link closed. Keep the identity and wait for a retry or the
                // matching close callback before allowing connect().
                transition(
                    connection = BleConnectionState.Failed("Bluetooth disconnect failed"),
                    configuration = ConfigurationState.Unavailable,
                )
                diagnostics.error("Disconnect failed")
                if (disconnectIntent == DisconnectIntent.RECONNECT) {
                    scheduleDisconnectRetry(operationNowMillis())
                }
            }
            is BleTransportEvent.Disconnected -> {
                if (!acceptDisconnectedEvent(event)) return
                val shouldReconnect = disconnectIntent == DisconnectIntent.RECONNECT
                val wasManual = disconnectIntent == DisconnectIntent.MANUAL || manualDisconnect
                disconnectIntent = null
                manualDisconnect = false
                disconnectRequestInFlight = false
                connectStartedAtMillis = null
                pendingConnectDevice = null
                pendingConnectRequestGeneration = null
                pendingPhysicalLinkGeneration = null
                activeDevice = null
                payloadGate.reset()
                transition(
                    connection = if (wasManual || !event.unexpected) {
                        BleConnectionState.Idle
                    } else {
                        BleConnectionState.Failed("Bluetooth scanner disconnected")
                    },
                    configuration = ConfigurationState.Unavailable,
                )
                diagnostics.connection(
                    if (wasManual || !event.unexpected) {
                        "Scanner disconnected"
                    } else {
                        "Scanner connection lost"
                    },
                )
                if (shouldReconnect || (!wasManual && event.unexpected)) {
                    scheduleReconnect(operationNowMillis())
                }
            }
            is BleTransportEvent.ScanReceived -> {
                if (!acceptScanEvent(event)) return
                // Deliberately no diagnostics call here: event.payload.value
                // must remain outside the diagnostic stream.
                val timestamp = event.payload.timestampMillis.takeIf { it != 0L } ?: nowMillis()
                val normalized = payloadGate.accept(event.payload.value, timestamp) ?: return
                listener?.onScanPayload(
                    event.payload.copy(
                        value = normalized,
                        timestampMillis = timestamp,
                    ),
                )
            }
            is BleTransportEvent.AvailabilityChanged -> {
                if (event.availability is BleAvailability.Ready) {
                    // Clear a stale radio/permission notice only while no
                    // physical link is owned. Keep retry timing unchanged:
                    // radio readiness is neither a connection nor a settings
                    // confirmation, and must not erase a connection failure.
                    if (!hasPhysicalLink && connectionState is BleConnectionState.Unavailable) {
                        transition(
                            connection = BleConnectionState.Idle,
                            configuration = ConfigurationState.Unavailable,
                        )
                    }
                } else {
                    discoveryStartedAtMillis = null
                    val retainedLink = hasPhysicalLink
                    // Readiness is adapter-level status only; it does not prove
                    // that a pending or active physical link has closed. Keep
                    // the generations and any manual close intent so late
                    // callbacks remain attributable to this link.
                    if (retainedLink && disconnectIntent == null) {
                        disconnectIntent = DisconnectIntent.RECONNECT
                        manualDisconnect = false
                    }
                    transition(
                        connection = event.availability.asConnectionState(),
                        configuration = ConfigurationState.Unavailable,
                    )
                    if (applicationActive) {
                        if (retainedLink && disconnectIntent == DisconnectIntent.RECONNECT) {
                            // A close request that is already accepted remains
                            // in flight; retry only after a failure callback.
                            scheduleDisconnectRetry(operationNowMillis())
                        } else if (!retainedLink && disconnectIntent != DisconnectIntent.MANUAL) {
                            scheduleReconnect(operationNowMillis())
                        }
                    }
                }
            }
        }
    }

    /**
     * Advances discovery/connection/reconnect deadlines.
     *
     * A deadline is inclusive: a tick at exactly 5s/30s/8s performs the
     * corresponding action. Returns true when a transport operation was
     * started by this tick.
     */
    fun tick(nowMillis: Long): Boolean {
        val discoveryStartedAt = discoveryStartedAtMillis
        if (connectionState == BleConnectionState.Searching &&
            discoveryStartedAt != null &&
            nowMillis - discoveryStartedAt >= discoveryTimeoutMillis
        ) {
            discoveryStartedAtMillis = null
            val stopped = try {
                transport.stopDiscovery()
            } catch (_: Exception) {
                false
            }
            transition(connection = BleConnectionState.Failed("Bluetooth discovery timed out"))
            diagnostics.error("Discovery timed out")
            return stopped
        }

        val connectStartedAt = connectStartedAtMillis
        val connectingDevice = pendingConnectDevice
        if (connectStartedAt != null &&
            connectingDevice != null &&
            nowMillis - connectStartedAt >= connectTimeoutMillis &&
            (connectionState is BleConnectionState.Connecting ||
                connectionState is BleConnectionState.Reconnecting)
        ) {
            disconnectIntent = DisconnectIntent.RECONNECT
            manualDisconnect = false
            transition(
                connection = BleConnectionState.Failed("Bluetooth connection timed out"),
                configuration = ConfigurationState.Unavailable,
            )
            diagnostics.error("Connection timed out")
            // Do not clear the link generation or schedule a new connection
            // until the adapter proves that the old physical link is closed.
            // The shared close path also handles synchronous false/exception
            // results and schedules a bounded close-only retry.
            return requestPhysicalDisconnect(
                device = connectingDevice,
                intent = DisconnectIntent.RECONNECT,
                failureReason = "Bluetooth connection timed out",
                acceptedMessage = "Timed-out connection close requested",
                failureMessage = "Timed-out connection close failed",
                requestedAtMillis = nowMillis,
            )
        }

        val dueAt = reconnectAtMillis ?: return false
        if (!applicationActive || nowMillis < dueAt) return false
        // Adapter-originated discovery may bypass startDiscovery(). Keep the
        // deadline pending until discovery ends rather than overlapping it.
        if (connectionState == BleConnectionState.Searching) return false
        reconnectAtMillis = null
        val device = preferredDevice ?: return false
        if (disconnectIntent == DisconnectIntent.RECONNECT && hasPhysicalLink) {
            // A failed close may retain the adapter's old link while the
            // app-level state is Failed. Retry the close, never overlap it
            // with a fresh connect attempt.
            val current = activeDevice ?: pendingConnectDevice ?: return false
            return requestPhysicalDisconnect(
                device = current,
                intent = DisconnectIntent.RECONNECT,
                failureReason = "Bluetooth reconnect reset could not start",
                acceptedMessage = "Reconnect reset requested",
                failureMessage = "Reconnect reset start failed",
                requestedAtMillis = nowMillis,
            )
        }
        // A retained link is never replaced merely because app-visible state
        // is unavailable after an adapter status change. Wait for a matching
        // Disconnected callback even if an older caller left intent unset.
        if (hasPhysicalLink) return false
        if (connectionState is BleConnectionState.Connected ||
            connectionState is BleConnectionState.Connecting ||
            connectionState is BleConnectionState.Reconnecting
        ) return false
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(maxReconnectAttempts)
        return startConnectionAttempt(
            device = device,
            reconnecting = true,
            startedAtMillis = nowMillis,
        )
    }

    private fun startConnectionAttempt(
        device: ScannerDevice,
        reconnecting: Boolean,
        retryOnFailure: Boolean = reconnecting,
        startedAtMillis: Long = nowMillis(),
    ): Boolean {
        val readiness = readTransportReadiness()
        val failure = readiness.failureReason(forConnection = true)
        if (failure != null) {
            transition(connection = readiness.asConnectionState(forConnection = true))
            diagnostics.error(failure)
            // Keep a persisted known-device recovery attempt alive across a
            // temporary Bluetooth/permission outage. The bounded reconnect
            // policy prevents this from becoming a tight retry loop, while a
            // later foreground/readiness recovery can start a new sequence.
            // A scheduled tick owns its logical timestamp. Using the
            // platform clock here can immediately make the next retry due
            // again when a host supplies a monotonic/test clock to tick().
            if (retryOnFailure) scheduleReconnect(startedAtMillis)
            return false
        }

        val requestGeneration = ++nextRequestGeneration
        val linkGeneration = ++nextLinkGeneration
        pendingConnectRequestGeneration = requestGeneration
        pendingConnectDevice = device
        pendingPhysicalLinkGeneration = linkGeneration
        connectStartedAtMillis = startedAtMillis
        transition(
            connection = if (reconnecting) {
                BleConnectionState.Reconnecting(device, reconnectAttempt)
            } else {
                BleConnectionState.Connecting(device)
            },
            configuration = ConfigurationState.Unavailable,
        )
        diagnostics.connection(
            if (reconnecting) "Automatic reconnect requested" else "Connection requested",
        )
        val previousOperationAtMillis = operationAtMillis
        operationAtMillis = startedAtMillis
        val accepted = try {
            transport.connect(device, requestGeneration, linkGeneration)
        } catch (_: Exception) {
            false
        } finally {
            operationAtMillis = previousOperationAtMillis
        }
        if (!accepted && pendingConnectRequestGeneration == requestGeneration) {
            val reconnectRequested = disconnectIntent == DisconnectIntent.RECONNECT
            val wasManual = disconnectIntent == DisconnectIntent.MANUAL || manualDisconnect
            clearPendingConnection()
            // A readiness callback can request closure reentrantly while the
            // adapter is starting a link. If that same start is rejected, no
            // physical link exists to acknowledge the close. Retire only this
            // request's intent so the next connection is not closed as stale.
            disconnectIntent = null
            disconnectRequestInFlight = false
            manualDisconnect = false
            if (wasManual) reconnectAtMillis = null
            transition(
                connection = if (reconnecting) {
                    BleConnectionState.Failed("Bluetooth reconnect could not start")
                } else {
                    BleConnectionState.Failed("Bluetooth connection could not start")
                },
            )
            diagnostics.error(
                if (reconnecting) "Reconnect start failed" else "Connection start failed",
            )
            if (!wasManual && (retryOnFailure || reconnectRequested)) {
                scheduleReconnect(startedAtMillis)
            }
        }
        return accepted
    }

    private fun clearPendingConnection() {
        connectStartedAtMillis = null
        pendingConnectDevice = null
        pendingConnectRequestGeneration = null
        pendingPhysicalLinkGeneration = null
    }

    /**
     * Requests a physical close while retaining the link identity until the
     * adapter emits [BleTransportEvent.Disconnected]. A successful request is
     * only an accepted operation, not proof that the link is already closed.
     */
    private fun requestPhysicalDisconnect(
        device: ScannerDevice,
        intent: DisconnectIntent,
        failureReason: String,
        acceptedMessage: String,
        failureMessage: String,
        requestedAtMillis: Long = nowMillis(),
    ): Boolean {
        disconnectIntent = intent
        manualDisconnect = intent == DisconnectIntent.MANUAL
        reconnectAtMillis = null
        discoveryStartedAtMillis = null
        connectStartedAtMillis = null
        val requestSequence = ++disconnectRequestSequence
        disconnectRequestInFlight = true
        val previousOperationAtMillis = operationAtMillis
        operationAtMillis = requestedAtMillis
        val accepted = try {
            transport.disconnect(device)
        } catch (_: Exception) {
            false
        } finally {
            operationAtMillis = previousOperationAtMillis
        }
        // A callback may synchronously start a newer close operation. Its
        // result owns the state, so the outer call must not clear or rewrite
        // that newer operation's bookkeeping.
        if (disconnectRequestSequence != requestSequence) return accepted
        if (accepted) {
            // A synchronous Disconnected callback (or a listener-triggered
            // newer request from that callback) owns the resulting state. Do
            // not overwrite it after transport.disconnect returns.
            if (!hasPhysicalLink || disconnectIntent != intent) return true
            // Preserve a synchronously delivered DisconnectFailed event. The
            // old link is still retained and must remain non-ready until a
            // successful close callback arrives.
            val alreadyNonReady = connectionState is BleConnectionState.Failed ||
                connectionState is BleConnectionState.Unavailable
            if (!alreadyNonReady) {
                transition(
                    connection = BleConnectionState.Idle,
                    configuration = ConfigurationState.Unavailable,
                )
                diagnostics.connection(acceptedMessage)
            }
        } else {
            disconnectRequestInFlight = false
            // A reentrant callback may have already acknowledged closure even
            // if the transport returned false or threw. Do not resurrect the
            // old identity or arm a reconnect in that case.
            if (!hasPhysicalLink || disconnectIntent != intent) return false
            // Retain the physical-link identity. A late successful connection
            // is immediately disconnected below instead of becoming Ready.
            transition(
                connection = BleConnectionState.Failed(failureReason),
                configuration = ConfigurationState.Unavailable,
            )
            diagnostics.error(failureMessage)
            if (intent == DisconnectIntent.RECONNECT) {
                scheduleDisconnectRetry(requestedAtMillis)
            }
        }
        return accepted
    }

    private fun readKnownDevice(): ScannerDevice? {
        val store = knownDeviceStore ?: run {
            knownDeviceReadResult = BleKnownDeviceReadResult.Missing
            return null
        }
        val result = runCatching { store.read() }.getOrElse {
            BleKnownDeviceReadResult.Rejected(
                BleKnownDeviceRejectionReason.DATASTORE_READ_FAILED,
            )
        }
        knownDeviceReadResult = result
        return when (result) {
            is BleKnownDeviceReadResult.Found -> result.device
            BleKnownDeviceReadResult.Missing -> null
            is BleKnownDeviceReadResult.Rejected -> {
                // Keep the reason out of the diagnostic text. The reason may
                // originate from a persistence implementation and must never
                // become a channel for scanner settings or callback payloads.
                diagnostics.error("Known scanner identity rejected")
                null
            }
        }
    }

    private fun rememberKnownDevice(device: ScannerDevice): Boolean {
        val store = knownDeviceStore ?: return true
        val result = runCatching { store.save(device) }.getOrElse {
            BleKnownDeviceWriteResult.Rejected(
                BleKnownDeviceRejectionReason.DATASTORE_WRITE_FAILED,
            )
        }
        return when (result) {
            BleKnownDeviceWriteResult.Saved -> {
                knownDeviceReadResult = BleKnownDeviceReadResult.Found(device)
                true
            }
            is BleKnownDeviceWriteResult.Rejected -> {
                diagnostics.error("Known scanner identity could not be saved")
                false
            }
        }
    }

    private fun acceptConnectedEvent(event: BleTransportEvent.Connected): Boolean {
        val expectedDevice = pendingConnectDevice ?: return false
        if (event.device.id != expectedDevice.id) return false
        val expectedRequest = pendingConnectRequestGeneration
        if (event.requestGeneration != null && event.requestGeneration != expectedRequest) {
            return false
        }
        val expectedLink = pendingPhysicalLinkGeneration
        val link = event.linkGeneration
        if (link != null) {
            if (expectedLink != null && link < expectedLink) return false
            if (activeLinkGeneration != null && link <= activeLinkGeneration!!) return false
        }
        return true
    }

    private fun acceptConnectionFailureEvent(event: BleTransportEvent.ConnectionFailed): Boolean {
        val expectedDevice = pendingConnectDevice ?: return false
        if (event.device.id != expectedDevice.id) return false
        if (event.requestGeneration != null &&
            event.requestGeneration != pendingConnectRequestGeneration
        ) return false
        val link = event.linkGeneration
        if (link != null && pendingPhysicalLinkGeneration != null &&
            link < pendingPhysicalLinkGeneration!!
        ) return false
        return true
    }

    private fun acceptDisconnectedEvent(event: BleTransportEvent.Disconnected): Boolean {
        val expectedDevice = activeDevice ?: pendingConnectDevice
        if (expectedDevice == null || event.device.id != expectedDevice.id) return false
        val requestMatches = event.requestGeneration == null ||
            event.requestGeneration == activeRequestGeneration ||
            event.requestGeneration == pendingConnectRequestGeneration
        if (!requestMatches) return false
        val linkMatches = event.linkGeneration == null ||
            event.linkGeneration == activeLinkGeneration ||
            event.linkGeneration == pendingPhysicalLinkGeneration
        return linkMatches
    }

    private fun acceptDisconnectFailedEvent(event: BleTransportEvent.DisconnectFailed): Boolean {
        // A failure callback is meaningful only while this coordinator owns
        // a close request. In particular, an untagged late legacy callback
        // must not demote a newer healthy connection for the same device.
        if (disconnectIntent == null) return false
        val expectedDevice = activeDevice ?: pendingConnectDevice
        if (expectedDevice == null || event.device.id != expectedDevice.id) return false
        val requestMatches = event.requestGeneration == null ||
            event.requestGeneration == activeRequestGeneration ||
            event.requestGeneration == pendingConnectRequestGeneration
        if (!requestMatches) return false
        val linkMatches = event.linkGeneration == null ||
            event.linkGeneration == activeLinkGeneration ||
            event.linkGeneration == pendingPhysicalLinkGeneration
        return linkMatches
    }

    private fun acceptScanEvent(event: BleTransportEvent.ScanReceived): Boolean {
        val connected = connectionState as? BleConnectionState.Connected ?: return false
        if (event.device != null && event.device.id != connected.device.id) return false
        if (event.requestGeneration != null &&
            event.requestGeneration != activeRequestGeneration
        ) return false
        if (event.linkGeneration != null && event.linkGeneration != activeLinkGeneration) {
            return false
        }
        return true
    }

    private fun operationNowMillis(): Long = operationAtMillis ?: nowMillis()

    private fun scheduleReconnect(nowMillis: Long) {
        if (!applicationActive || manualDisconnect || preferredDevice == null) return
        if (reconnectAtMillis != null) return
        val delay = if (reconnectAttempt >= maxReconnectAttempts) {
            // Only disconnected devices enter slow recovery. Failed physical
            // closes retain their separate bounded policy and cannot overlap.
            if (hasPhysicalLink) return
            sustainedReconnectDelayMillis ?: return
        } else {
            reconnectDelayMillis(reconnectAttempt + 1)
        }
        require(delay >= 0) { "reconnect delay must not be negative" }
        reconnectAtMillis = nowMillis + delay
    }

    /**
     * Schedules a bounded retry of a failed physical close. This uses the
     * reconnect budget but never calls connect while the old link is retained.
     */
    private fun scheduleDisconnectRetry(nowMillis: Long) {
        if (!applicationActive || disconnectIntent != DisconnectIntent.RECONNECT ||
            preferredDevice == null || disconnectRequestInFlight || reconnectAtMillis != null ||
            reconnectAttempt >= maxReconnectAttempts
        ) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(maxReconnectAttempts)
        val delay = reconnectDelayMillis(reconnectAttempt)
        require(delay >= 0) { "reconnect delay must not be negative" }
        reconnectAtMillis = nowMillis + delay
    }

    private fun transition(
        connection: BleConnectionState = mutableState.connection,
        configuration: ConfigurationState = mutableState.configuration,
    ) {
        mutableState = mutableState.copy(
            connection = connection,
            configuration = configuration,
            devices = devices,
            expectedFormat = expectedFormat,
            diagnostics = diagnostics.snapshot(),
        )
        emitState()
    }

    private fun emitState() {
        mutableState = mutableState.copy(
            devices = devices,
            expectedFormat = expectedFormat,
            diagnostics = diagnostics.snapshot(),
        )
        listener?.onStateChanged(mutableState)
    }

    /**
     * A readiness getter is platform code. Treat an adapter exception as an
     * unavailable transport instead of allowing it to escape through a UI
     * action or a reconnect ticker.
     */
    private fun readTransportReadiness(): BleTransportReadiness = runCatching {
        transport.readiness
    }.getOrElse {
        BleTransportReadiness(
            availability = BleAvailability.Failed("Bluetooth readiness unavailable"),
        )
    }

    private fun BleTransportReadiness.asConnectionState(
        forConnection: Boolean,
    ): BleConnectionState = when {
        lifecycle == BleAdapterLifecycleState.DESTROYED ->
            BleConnectionState.Unavailable("Bluetooth adapter is closed")
        lifecycle == BleAdapterLifecycleState.BACKGROUND ->
            BleConnectionState.Unavailable("Bluetooth adapter is inactive")
        availability !is BleAvailability.Ready -> availability.asConnectionState()
        !forConnection && discoveryPermission != BlePermissionState.GRANTED ->
            BleConnectionState.Unavailable("Bluetooth discovery permission is required")
        forConnection && connectionPermission != BlePermissionState.GRANTED ->
            BleConnectionState.Unavailable("Bluetooth connection permission is required")
        else -> BleConnectionState.Unavailable("Bluetooth is unavailable")
    }

    private fun BleAvailability.asConnectionState(): BleConnectionState = when (this) {
        BleAvailability.Ready -> BleConnectionState.Idle
        BleAvailability.Unknown -> BleConnectionState.Unavailable("Bluetooth is preparing")
        BleAvailability.PoweredOff -> BleConnectionState.Unavailable("Bluetooth is off")
        BleAvailability.Unauthorized -> BleConnectionState.Unavailable("Bluetooth permission is required")
        BleAvailability.Unsupported -> BleConnectionState.Unavailable("Bluetooth is unsupported")
        is BleAvailability.Failed -> BleConnectionState.Unavailable(reason)
    }

    private companion object {
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 4
        const val DEFAULT_DISCOVERY_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_RECONNECT_DELAY_MILLIS = 8_000L

        fun defaultReconnectDelayMillis(attempt: Int): Long =
            DEFAULT_RECONNECT_DELAY_MILLIS
    }
}
