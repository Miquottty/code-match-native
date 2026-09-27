package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Combined state exposed by the production-adapter boundary.
 *
 * A connected GATT link is not enough to scan: the device inventory must have
 * been read and the session restriction must have been applied successfully.
 * Keeping that distinction here prevents an adapter from publishing a
 * connected-but-unconfigured scanner to the application.
 */
data class BleScannerSessionState(
    val connection: BleConnectionState,
    val configuration: ConfigurationState,
    val symbology: BleSymbologySessionState,
    val expectedFormat: ScanFormat?,
    val isReadyForScanning: Boolean,
    val diagnostics: List<DiagnosticEvent>,
) {
    val connectedDevice: ScannerDevice?
        get() = connection.connectedDevice

    val isSessionActive: Boolean
        get() = symbology != BleSymbologySessionState.Disconnected &&
            symbology != BleSymbologySessionState.Ready &&
            symbology !is BleSymbologySessionState.Failed
}

/**
 * Wires the connection coordinator and the symbology/session owner together.
 *
 * This is deliberately a pure Kotlin orchestration layer. The future Android
 * adapter still owns Bluetooth callbacks, service discovery, permissions,
 * UUIDs, and byte framing; it only needs to implement [BleTransport] and feed
 * events into [BleConnectionCoordinator].
 */
class BleScannerSessionCoordinator(
    private val connectionCoordinator: BleConnectionCoordinator,
    private val symbologySession: BleSymbologySession,
) : BleScannerListener {
    private var listener: ((BleScannerSessionState) -> Unit)? = null
    private var closed = false
    private var applicationActive = true
    private var pendingManualDisconnect = false
    private var pendingReconnectAfterDisconnect = false
    private var observedConnectedDeviceId: String? = null
    private var observedConnectedLinkGeneration: Long? = null
    private var synchronizingConfiguration = false
    private var mutableState: BleScannerSessionState = stateFromCurrentValues()

    init {
        connectionCoordinator.setListener(this)
        symbologySession.setListener { _, configuration ->
            // The connection coordinator's state is also consumed directly by
            // settings UI. Keep it conservative: Ready is published there
            // only after this session owner has reached Ready.
            if (connectionCoordinator.configurationState != configuration &&
                !synchronizingConfiguration
            ) {
                synchronizingConfiguration = true
                try {
                    connectionCoordinator.markConfiguration(configuration)
                } finally {
                    synchronizingConfiguration = false
                }
            }
            resumeSuspendedSessionIfPossible()
            completePendingManualDisconnectIfSafe()
            publish()
        }
        reconcileConnection(connectionCoordinator.state.connection)
        publish()
    }

    val state: BleScannerSessionState
        get() = mutableState

    /** Devices currently reported by the adapter's discovery coordinator. */
    val devices: List<ScannerDevice>
        get() = connectionCoordinator.devices

    /** Whether the physical QR+Code128 session has not yet been restored. */
    val isSessionActive: Boolean
        get() = symbologySession.isSessionActive

    /** Whether a settings read or command of this owner is still unsettled. */
    val isOperationInFlight: Boolean
        get() = symbologySession.isOperationInFlight

    /** Whether a backgrounded session is waiting for foreground resumption. */
    val isSuspendedForBackground: Boolean
        get() = symbologySession.isSuspendedForBackground

    val device: ScannerDevice
        get() = symbologySession.scannerDevice

    fun setListener(listener: ((BleScannerSessionState) -> Unit)?) {
        this.listener = listener
        if (!closed) listener?.invoke(mutableState)
    }

    fun startDiscovery(): Boolean {
        if (closed) return false
        return connectionCoordinator.startDiscovery()
    }

    fun stopDiscovery(): Boolean {
        if (closed) return false
        return connectionCoordinator.stopDiscovery()
    }

    /** Rejects a device that does not match this session's persisted identity. */
    fun connect(device: ScannerDevice): Boolean {
        if (closed || device.id != this.device.id) return false
        return connectionCoordinator.connect(device)
    }

    fun disconnect(): Boolean {
        if (closed) return false
        // A new manual disconnect supersedes a reconnect that was queued while
        // an earlier restore was still in flight.
        pendingReconnectAfterDisconnect = false
        val symbologyState = symbologySession.state
        if (symbologyState == BleSymbologySessionState.Restoring) {
            pendingManualDisconnect = true
            return true
        }
        if (symbologySession.isSessionActive) {
            pendingManualDisconnect = true
            if (symbologySession.endSession()) return true
            // A timed-out link cannot accept a restore command. The transport
            // is still closed below, while the persisted snapshot remains for
            // recovery on the next connection.
        }
        pendingManualDisconnect = false
        return connectionCoordinator.disconnect()
    }

    fun reconnectKnownDevice(): Boolean {
        if (closed) return false
        if (pendingManualDisconnect) {
            pendingReconnectAfterDisconnect = true
            return true
        }
        return connectionCoordinator.reconnectKnownDevice()
    }

    /** Start the physical restriction for the expected step. */
    fun startSession(expectedFormat: ScanFormat): Boolean {
        if (closed || !isConnectedToBoundDevice()) return false
        return symbologySession.startSession(expectedFormat)
    }

    /** Change the physical restriction and wait for setting completion. */
    fun setExpectedFormat(expectedFormat: ScanFormat): Boolean {
        if (closed) return false
        return symbologySession.setExpectedFormat(expectedFormat)
    }

    fun endSession(): Boolean {
        if (closed) return false
        return symbologySession.endSession()
    }

    /** Forward the host lifecycle state without importing Android Lifecycle. */
    fun setApplicationActive(active: Boolean, atMillis: Long) {
        if (closed) return
        if (applicationActive == active) return
        applicationActive = active
        if (!active && symbologySession.isSettingsReadPending) {
            // Invalidate a read callback before closing the link. Otherwise a
            // response arriving after backgrounding could publish Ready while
            // the adapter is no longer owned by the active host.
            symbologySession.onTransportDisconnected()
            connectionCoordinator.disconnect()
        } else if (!active) {
            // Restore baseline while the adapter is still foregrounded so a
            // background transition cannot strand a restricted scanner.
            symbologySession.suspendForBackground()
        }
        connectionCoordinator.setApplicationActive(active, atMillis)
        if (active) {
            // A connected adapter may have stayed alive while the app was in
            // the background. Reconcile it now, then resume the logical step
            // only after the fresh settings/restore boundary is complete.
            reconcileConnection(connectionCoordinator.state.connection)
            resumeSuspendedSessionIfPossible()
        }
        completePendingManualDisconnectIfSafe()
        publish()
    }

    /** A transport timeout must be acknowledged only after the link is closed. */
    fun onTransportResetCompleted() {
        if (closed) return
        symbologySession.onTransportResetCompleted()
        publish()
    }

    /** Drive both deadline owners from one adapter/application ticker. */
    fun tick(atMillis: Long): BleScannerTickResult {
        if (closed) {
            return BleScannerTickResult(
                command = BleCommandTickResult.Noop,
                connectionOperationStarted = false,
            )
        }
        val command = symbologySession.tick(atMillis)
        val connectionOperationStarted = connectionCoordinator.tick(atMillis)
        publish()
        return BleScannerTickResult(command, connectionOperationStarted)
    }

    /** Stop accepting events and detach both callbacks owned by this bridge. */
    fun close() {
        if (closed) return
        closed = true
        listener = null
        connectionCoordinator.setListener(null)
        symbologySession.setListener(null)
    }

    override fun onStateChanged(state: BleScannerState) {
        if (closed) return
        reconcileConnection(state.connection)
        completePendingManualDisconnectIfSafe()
        publish()
    }

    override fun onScanPayload(payload: ScanPayload) {
        if (closed || !applicationActive || !mutableState.isReadyForScanning) return
        // A coordinator for a BLE transport must never forward a callback
        // mislabeled as camera input. Dropping it is safer than recording it.
        if (payload.source != InputSource.BLUETOOTH) return
        val expectedFormat = mutableState.expectedFormat ?: return
        // Do not add the value to state or diagnostics. The typed payload is
        // delivered only to the caller that explicitly subscribed.
        // A BLE notification does not reliably contain its symbology. The
        // logical QR/Code128 step is authoritative and survives background
        // restore/reconnect independently of an adapter-local label.
        onPayload?.invoke(payload.copy(format = expectedFormat))
    }

    /** Separate payload callback keeps state snapshots free of scan text. */
    var onPayload: ((ScanPayload) -> Unit)? = null

    private fun reconcileConnection(connection: BleConnectionState) {
        if (closed) return
        val connected = connection.connectedDevice
        if (!applicationActive) {
            if (connected == null &&
                symbologySession.state != BleSymbologySessionState.Disconnected &&
                symbologySession.state != BleSymbologySessionState.AwaitingTransportReset
            ) {
                symbologySession.onTransportDisconnected()
            }
            return
        }
        if (connected?.id == device.id) {
            val isNewPhysicalLink = observedConnectedDeviceId != connected.id ||
                (connectionCoordinator.currentLinkGeneration != null &&
                    connectionCoordinator.currentLinkGeneration != observedConnectedLinkGeneration)
            when (symbologySession.state) {
                BleSymbologySessionState.Disconnected,
                BleSymbologySessionState.AwaitingReconnect,
                is BleSymbologySessionState.Failed,
                -> if (isNewPhysicalLink) symbologySession.onConnected()
                else -> Unit
            }
            observedConnectedDeviceId = connected.id
            observedConnectedLinkGeneration = connectionCoordinator.currentLinkGeneration
            return
        }

        observedConnectedDeviceId = null
        observedConnectedLinkGeneration = null

        // Do not release an awaiting-timeout command until the adapter
        // explicitly acknowledges transport reset. Every other non-connected
        // transition invalidates the session's pending read/write callbacks.
        if (symbologySession.state != BleSymbologySessionState.Disconnected &&
            symbologySession.state != BleSymbologySessionState.AwaitingTransportReset
        ) {
            symbologySession.onTransportDisconnected()
        }
    }

    private fun resumeSuspendedSessionIfPossible() {
        if (!applicationActive) return
        if (connectionCoordinator.connectionState.connectedDevice?.id != device.id) return
        if (symbologySession.state != BleSymbologySessionState.Ready) return
        symbologySession.resumeSuspendedSession()
    }

    private fun completePendingManualDisconnectIfSafe() {
        if (!pendingManualDisconnect) return
        if (connectionCoordinator.connectionState.connectedDevice == null) {
            pendingManualDisconnect = false
            // Availability loss can invalidate a restore before the physical
            // link closes. Carry the user's manual intent into that close;
            // otherwise the availability event schedules an unwanted retry.
            if (connectionCoordinator.hasPhysicalLink) {
                connectionCoordinator.disconnect()
            }
            reconnectAfterPendingDisconnect()
            return
        }
        when (symbologySession.state) {
            BleSymbologySessionState.Restoring,
            BleSymbologySessionState.AwaitingTransportReset,
            -> return
            else -> Unit
        }
        pendingManualDisconnect = false
        val disconnected = connectionCoordinator.disconnect()
        if (disconnected || connectionCoordinator.connectionState.connectedDevice == null) {
            reconnectAfterPendingDisconnect()
        }
    }

    private fun reconnectAfterPendingDisconnect() {
        if (!pendingReconnectAfterDisconnect) return
        pendingReconnectAfterDisconnect = false
        connectionCoordinator.reconnectKnownDevice()
    }

    private fun isConnectedToBoundDevice(): Boolean =
        connectionCoordinator.connectionState.connectedDevice?.id == device.id

    private fun publish() {
        if (closed) return
        mutableState = stateFromCurrentValues()
        listener?.invoke(mutableState)
    }

    private fun stateFromCurrentValues(): BleScannerSessionState {
        val connection = connectionCoordinator.connectionState
        val configuration = symbologySession.configurationState
        val ready = connection.connectedDevice?.id == device.id &&
            symbologySession.isReadyForScanning &&
            configuration.isReady
        return BleScannerSessionState(
            connection = connection,
            configuration = configuration,
            symbology = symbologySession.state,
            expectedFormat = symbologySession.expectedFormat,
            isReadyForScanning = ready,
            diagnostics = (connectionCoordinator.state.diagnostics +
                symbologySession.diagnosticEvents)
                .sortedWith(compareBy<DiagnosticEvent> { it.timestampMillis }.thenBy { it.sequence })
                .takeLast(MAX_DIAGNOSTICS),
        )
    }

    data class BleScannerTickResult(
        val command: BleCommandTickResult,
        val connectionOperationStarted: Boolean,
    )

    private companion object {
        const val MAX_DIAGNOSTICS = 300
    }
}
