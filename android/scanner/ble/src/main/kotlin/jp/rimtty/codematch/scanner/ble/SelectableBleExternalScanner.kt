package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice

/** Creates the settings/session owner after a user selects a discovered device. */
fun interface BleSessionCoordinatorFactory {
    fun create(device: ScannerDevice): BleScannerSessionCoordinator
}

/**
 * Production-facing BLE facade for a scanner whose identity is learned by discovery.
 *
 * [BleExternalScanner] remains useful when the adapter already knows a fixed device.
 * A real Android flow cannot construct that fixed stack before discovery, so this
 * facade binds a fresh [BleScannerSessionCoordinator] to the selected device before
 * starting the physical connection. The same factory path is used when a persisted
 * known device is reconnected after process recreation.
 *
 * Only one device/session owner can be active. Selecting another device is rejected
 * while the previous device still has a physical or pending link or a connect or
 * reconnect in flight. Once the link is gone the owner may be replaced even if its
 * scan session was never restored (for example the scanner was switched off
 * mid-session), as long as that session has no settings read or command left in
 * flight: its pre-session inventory is persisted under its own device id, so it is
 * restored on that scanner's next connection and can never be redirected to the
 * new scanner (#135).
 *
 * An operator's [connect] always targets the chosen scanner (#137). It pre-empts
 * every automatic recovery of the previous one: the choice becomes the known
 * device at once, reconnect timers stop, a running discovery stops, and a
 * requested but not yet established link to the previous scanner is closed. The
 * choice is then connected as soon as no link remains ([tick] finishes a close
 * that completes asynchronously); meanwhile [connectionState] reports it as
 * Connecting. Only an established link that is not being closed refuses the
 * choice: the operator disconnects it first. [startDiscovery] likewise closes a
 * pending, not yet established link before searching.
 */
class SelectableBleExternalScanner(
    private val connectionCoordinator: BleConnectionCoordinator,
    private val sessionFactory: BleSessionCoordinatorFactory,
) : ExternalScanner, BleScannerListener {
    private var sessionCoordinator: BleScannerSessionCoordinator? = null
    private var mutableListener: ExternalScannerListener? = null
    private var lastConnectionState: ConnectionState? = null
    private var lastConfigurationState: ConfigurationState? = null
    private var closed = false
    /** Operator choice waiting for the previous link to close (#137). */
    private var pendingSelection: ScannerDevice? = null
    /** Operator search waiting for a cancelled pending link to close. */
    private var discoveryAfterClose = false

    init {
        connectionCoordinator.setListener(this)
    }

    override val devices: List<ScannerDevice>
        get() = connectionCoordinator.devices

    override val connectionState: ConnectionState
        get() = pendingSelection?.let { ConnectionState.Connecting(it) }
            ?: sessionCoordinator?.state?.connection?.asApiState()
            ?: connectionCoordinator.connectionState.asApiState()

    /** Operator choice that will be connected once the previous link closes. */
    val queuedSelection: ScannerDevice?
        get() = pendingSelection

    override val configurationState: ConfigurationState
        get() = sessionCoordinator?.state?.configuration
            ?: connectionCoordinator.configurationState

    override val diagnosticEvents: List<DiagnosticEvent>
        get() = sessionCoordinator?.state?.diagnostics
            ?: connectionCoordinator.state.diagnostics

    override val expectedFormat: ScanFormat?
        get() = sessionCoordinator?.state?.expectedFormat

    override val isReadyForScanning: Boolean
        get() = sessionCoordinator?.state?.isReadyForScanning == true

    /** Device currently owning the settings/recovery state machine. */
    val boundDevice: ScannerDevice?
        get() = sessionCoordinator?.device

    /** True only while a timed-out settings operation waits for link reset. */
    val isAwaitingTransportReset: Boolean
        get() = sessionCoordinator?.state?.symbology ==
            BleSymbologySessionState.AwaitingTransportReset

    override var listener: ExternalScannerListener?
        get() = mutableListener
        set(value) {
            mutableListener = value
            if (value != null && !closed) {
                value.onConnectionStateChanged(connectionState)
                value.onConfigurationStateChanged(configurationState)
            }
        }

    override fun startDiscovery(): Boolean {
        if (closed) return false
        // An operator search supersedes a queued choice and closes a pending,
        // not yet established link (for example an automatic reconnect).
        if (pendingSelection != null) {
            pendingSelection = null
            publishCurrent()
        }
        if (!connectionCoordinator.hasEstablishedLink &&
            connectionCoordinator.connectingDevice != null
        ) {
            connectionCoordinator.cancelPendingConnection()
            if (connectionCoordinator.hasPhysicalLink) {
                discoveryAfterClose = connectionCoordinator.isCloseInFlight
                return discoveryAfterClose
            }
        }
        discoveryAfterClose = false
        return startDiscoveryNow()
    }

    private fun startDiscoveryNow(): Boolean =
        sessionCoordinator?.startDiscovery() ?: connectionCoordinator.startDiscovery()

    override fun stopDiscovery(): Boolean {
        if (closed) return false
        return sessionCoordinator?.stopDiscovery() ?: connectionCoordinator.stopDiscovery()
    }

    /**
     * Operator choice (#137): always connect [device], pre-empting automatic
     * recovery of the previous scanner. Returns false only when an established
     * link that is not being closed exists (disconnect it first), when the
     * choice cannot be persisted, or when the connection cannot start.
     */
    override fun connect(device: ScannerDevice): Boolean {
        if (closed) return false
        if (connectionCoordinator.hasEstablishedLink && !connectionCoordinator.isClosingLink) {
            return false
        }
        discoveryAfterClose = false
        if (!connectionCoordinator.preferSelectedDevice(device)) return false
        pendingSelection = device
        val started = resolvePendingSelection()
        publishCurrent()
        return started ?: true
    }

    override fun disconnect(): Boolean {
        if (closed) return false
        val hadQueuedRequest = pendingSelection != null || discoveryAfterClose
        pendingSelection = null
        discoveryAfterClose = false
        if (hadQueuedRequest) publishCurrent()
        return (sessionCoordinator?.disconnect() ?: connectionCoordinator.disconnect()) ||
            hadQueuedRequest
    }

    override fun reconnectKnownDevice(): Boolean {
        if (closed) return false
        // A queued operator choice owns the next connection; automatic or
        // repeated recovery requests must not redirect or reset it.
        if (pendingSelection != null) return resolvePendingSelection() ?: true
        val device = connectionCoordinator.knownDevice
            ?: connectionCoordinator.loadKnownDevice()
            ?: return false
        if (!bind(device)) return false
        return requireNotNull(sessionCoordinator).reconnectKnownDevice()
    }

    override fun setExpectedFormat(format: ScanFormat?): Boolean {
        if (closed) return false
        val coordinator = sessionCoordinator ?: return false
        if (format == null) {
            return if (coordinator.isSessionActive) coordinator.endSession() else false
        }
        return if (coordinator.isSessionActive) {
            coordinator.setExpectedFormat(format)
        } else if (
            coordinator.state.connection.connectedDevice?.id == coordinator.device.id &&
            coordinator.state.symbology == BleSymbologySessionState.Ready
        ) {
            coordinator.startSession(format)
        } else {
            false
        }
    }

    /** Forward host lifecycle while retaining the selected device identity. */
    fun setApplicationActive(active: Boolean, atMillis: Long) {
        if (closed) return
        sessionCoordinator?.setApplicationActive(active, atMillis)
            ?: connectionCoordinator.setApplicationActive(active, atMillis)
    }

    /** Advance command/reconnect deadlines from the Android host scheduler. */
    fun tick(atMillis: Long): BleScannerSessionCoordinator.BleScannerTickResult? {
        if (closed) return null
        val result = sessionCoordinator?.tick(atMillis) ?: run {
            connectionCoordinator.tick(atMillis)
            null
        }
        if (!closed) resolveQueuedOperatorRequests()
        return result
    }

    /** Finish an operator choice or search that waited for a link to close. */
    private fun resolveQueuedOperatorRequests() {
        if (pendingSelection != null) {
            val selection = pendingSelection
            val waitingForOwnAttempt =
                connectionCoordinator.connectingDevice?.id == selection?.id
            if (connectionCoordinator.hasPhysicalLink &&
                !connectionCoordinator.isCloseInFlight &&
                !waitingForOwnAttempt
            ) {
                // The close failed or was never accepted. Stop presenting the
                // choice as Connecting; the retained link's failure is shown.
                pendingSelection = null
                publishCurrent()
            } else if (resolvePendingSelection() != null) {
                publishCurrent()
            }
        }
        if (discoveryAfterClose) {
            if (!connectionCoordinator.hasPhysicalLink) {
                discoveryAfterClose = false
                startDiscoveryNow()
            } else if (!connectionCoordinator.isCloseInFlight) {
                discoveryAfterClose = false
            }
        }
    }

    /**
     * Connect the queued choice when no other link remains. Returns null while
     * still waiting, otherwise whether the connection request was accepted.
     */
    private fun resolvePendingSelection(): Boolean? {
        val selection = pendingSelection ?: return null
        val current = sessionCoordinator
        if (current?.device?.id == selection.id) {
            if (connectionCoordinator.connectingDevice?.id == selection.id &&
                !connectionCoordinator.isClosingLink
            ) {
                // Already connecting to the chosen scanner.
                pendingSelection = null
                return true
            }
            if (connectionCoordinator.hasPhysicalLink) return null
        } else if (current != null && !canReplace(current)) {
            return null
        }
        pendingSelection = null
        if (!bind(selection)) return false
        return requireNotNull(sessionCoordinator).connect(selection)
    }

    /** A timed-out settings command can resume only after physical link reset. */
    fun onTransportResetCompleted() {
        if (!closed) sessionCoordinator?.onTransportResetCompleted()
    }

    fun close() {
        if (closed) return
        closed = true
        mutableListener = null
        sessionCoordinator?.close()
        sessionCoordinator = null
        connectionCoordinator.setListener(null)
    }

    override fun onStateChanged(state: BleScannerState) {
        if (!closed) publishCurrent()
    }

    override fun onScanPayload(payload: ScanPayload) {
        // An unbound connection coordinator is never ready, but retain the
        // privacy-safe typed boundary if a custom transport emits early data.
        if (!closed && isReadyForScanning) mutableListener?.onScanPayload(payload)
    }

    private fun bind(device: ScannerDevice): Boolean {
        val current = sessionCoordinator
        if (current?.device?.id == device.id) return true
        if (current != null && !canReplace(current)) return false

        current?.close()
        sessionCoordinator = null
        connectionCoordinator.setListener(this)
        val created = runCatching { sessionFactory.create(device) }.getOrNull() ?: return false
        if (created.device.id != device.id) {
            created.close()
            connectionCoordinator.setListener(this)
            return false
        }
        created.onPayload = { payload ->
            if (!closed) mutableListener?.onScanPayload(payload)
        }
        created.setListener {
            if (!closed) publishCurrent()
        }
        sessionCoordinator = created
        publishCurrent()
        return true
    }

    private fun canReplace(current: BleScannerSessionCoordinator): Boolean =
        // Idle/Unavailable may still describe a pending or closing physical
        // link. Do not detach its settings owner before the close callback.
        !connectionCoordinator.hasPhysicalLink &&
            connectionCoordinator.connectionState !is BleConnectionState.Connected &&
            connectionCoordinator.connectionState !is BleConnectionState.Connecting &&
            connectionCoordinator.connectionState !is BleConnectionState.Reconnecting &&
            (!current.isSessionActive || isSettledWithoutLink(current))

    /**
     * An unrestored session no longer blocks another scanner once its link is
     * gone (#135): nothing can be written to that scanner now, and its
     * persisted snapshot is restored by the RECOVERY path when it reconnects.
     * It must however have nothing left on the shared transport: no settings
     * read or command still awaiting its callback (the scan flow's restore
     * request after a drop can be accepted by a transport that has no link)
     * and no timed-out command still awaiting its transport reset. Both end
     * within the command/read deadline driven by [tick].
     */
    private fun isSettledWithoutLink(current: BleScannerSessionCoordinator): Boolean =
        !current.isOperationInFlight &&
            current.state.symbology != BleSymbologySessionState.AwaitingTransportReset

    private fun publishCurrent() {
        publish(connectionState, configurationState)
    }

    private fun publish(connection: ConnectionState, configuration: ConfigurationState) {
        val currentListener = mutableListener
        if (connection != lastConnectionState) {
            lastConnectionState = connection
            currentListener?.onConnectionStateChanged(connection)
        }
        if (configuration != lastConfigurationState) {
            lastConfigurationState = configuration
            currentListener?.onConfigurationStateChanged(configuration)
        }
    }
}
