package jp.rimtty.codematch.scanner.api

/** Listener used by UI/application coordinators without imposing coroutines. */
interface ExternalScannerListener {
    fun onConnectionStateChanged(state: ConnectionState) {}
    fun onConfigurationStateChanged(state: ConfigurationState) {}
    fun onScanPayload(payload: ScanPayload) {}
    fun onIlluminationStateChanged(state: IlluminationState) {}
    fun onTuningStateChanged(state: TuningState) {}
}

/** Confirmed lamp state; UNKNOWN must not be presented as a successful OFF. */
enum class IlluminationState { UNSUPPORTED, UNKNOWN, APPLYING, OFF, ON, FAILED }

/**
 * Connect-time read tuning (multi-code off, inverse off, red-light time).
 * MATCHED means the scanner already had the profile; APPLIED means a write
 * was needed and the readback confirmed it.
 */
enum class TuningState { UNSUPPORTED, UNKNOWN, APPLYING, MATCHED, APPLIED, FAILED }

typealias ScannerListener = ExternalScannerListener

/**
 * Platform-neutral contract for camera/Bluetooth scanner adapters.
 *
 * Production camera and BLE implementations can be asynchronous, while the
 * Fake implementation is synchronous and deterministic. No Android camera or
 * Bluetooth type crosses this boundary.
 */
interface ExternalScanner {
    val devices: List<ScannerDevice>
    val connectionState: ConnectionState
    val configurationState: ConfigurationState
    val diagnosticEvents: List<DiagnosticEvent>
    val connectedDevice: ScannerDevice?
        get() = connectionState.connectedDevice
    val isConnected: Boolean
        get() = connectionState.isConnected
    val isReadyForScanning: Boolean
        get() = isConnected && configurationState.isReady
    /**
     * Whether a connected scanner has a verified baseline and can begin a
     * restricted scan session.
     *
     * A production adapter may keep [isReadyForScanning] false until its
     * QR/Code 128 restriction has been written and read back. Keeping this
     * pre-session capability separate avoids a circular dependency where the
     * UI cannot select Bluetooth until after the session it needs to start.
     */
    val isReadyToStartSession: Boolean
        get() = isConnected && configurationState.isReady
    /**
     * Whether the host should expose external-scanner controls.
     *
     * The release placeholder overrides this to false.  A real adapter and
     * the debug Fake keep it true even while the radio is unavailable, so the
     * user can see a specific state and retry instead of silently losing the
     * recovery action.
     */
    val supportsConnectionControls: Boolean
        get() = true
    val expectedFormat: ScanFormat?
    val illuminationState: IlluminationState
        get() = IlluminationState.UNSUPPORTED

    /** Returns whether the asynchronous request was accepted. */
    fun setIllumination(enabled: Boolean): Boolean = false

    val tuningState: TuningState
        get() = TuningState.UNSUPPORTED

    var listener: ExternalScannerListener?

    /**
     * Add an independent observer without replacing the legacy [listener].
     *
     * Implementations that predate this method remain source-compatible via
     * the default identity-safe multiplexer.  Scanner adapters should migrate
     * to these methods so Settings and Scan can observe one transport at the
     * same time.  The Boolean is false only when the exact listener was
     * already registered.
     */
    fun addListener(listener: ExternalScannerListener): Boolean =
        ExternalScannerListenerMultiplexer.add(this, listener)

    /** Remove a listener previously registered with [addListener]. */
    fun removeListener(listener: ExternalScannerListener): Boolean =
        ExternalScannerListenerMultiplexer.remove(this, listener)

    fun startDiscovery(): Boolean
    fun stopDiscovery(): Boolean
    fun connect(device: ScannerDevice): Boolean
    fun disconnect(): Boolean
    fun reconnectKnownDevice(): Boolean
    fun setExpectedFormat(format: ScanFormat?): Boolean

    /**
     * Notes in the diagnostics how the app handled a Bluetooth read: the scan
     * log's event kind and rejection reason only, never the value read.
     */
    fun recordScanOutcome(event: String, reason: String?) {}

    // Naming aliases keep the contract easy to bridge from the Swift service
    // and from adapters that call discovery a scan.
    fun discover(): Boolean = startDiscovery()
    fun stopScanning(): Boolean = stopDiscovery()
    fun reconnectPreferredDevice(): Boolean = reconnectKnownDevice()
    fun setExpectedCode(format: ScanFormat?): Boolean = setExpectedFormat(format)
}

/**
 * Backwards-compatible listener fan-out for adapters that only implement the
 * original single [ExternalScanner.listener] property.
 *
 * The registry is identity based (not data-class equality based) and restores
 * the original listener after the last subscription is removed.  New
 * implementations may provide a native equivalent; callers use only the
 * [ExternalScanner] contract.
 */
private object ExternalScannerListenerMultiplexer {
    private class Entry(
        var original: ExternalScannerListener?,
    ) {
        val listeners = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<ExternalScannerListener, Boolean>(),
        )
        lateinit var composite: ExternalScannerListener
    }

    private val lock = Any()
    private val entries = java.util.WeakHashMap<ExternalScanner, Entry>()

    fun add(scanner: ExternalScanner, listener: ExternalScannerListener): Boolean {
        requireNotNull(listener) { "listener must not be null" }
        synchronized(lock) {
            val entry = entries[scanner] ?: Entry(scanner.listener).also { created ->
                created.composite = compositeFor(created)
                entries[scanner] = created
            }
            // A direct legacy assignment may have happened between adds. Keep
            // it as the compatibility listener rather than dropping it.
            if (scanner.listener !== entry.composite) {
                entry.original = scanner.listener
            }
            if (!entry.listeners.add(listener)) return false
            scanner.listener = entry.composite
            return true
        }
    }

    fun remove(scanner: ExternalScanner, listener: ExternalScannerListener): Boolean {
        synchronized(lock) {
            val entry = entries[scanner] ?: return false
            if (!entry.listeners.remove(listener)) return false
            if (entry.listeners.isEmpty()) {
                scanner.listener = entry.original
                entries.remove(scanner)
            } else {
                scanner.listener = entry.composite
            }
            return true
        }
    }

    private fun compositeFor(entry: Entry): ExternalScannerListener =
        object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                snapshot(entry).forEach { it.onConnectionStateChanged(state) }
            }

            override fun onConfigurationStateChanged(state: ConfigurationState) {
                snapshot(entry).forEach { it.onConfigurationStateChanged(state) }
            }

            override fun onScanPayload(payload: ScanPayload) {
                snapshot(entry).forEach { it.onScanPayload(payload) }
            }

            override fun onIlluminationStateChanged(state: IlluminationState) {
                snapshot(entry).forEach { it.onIlluminationStateChanged(state) }
            }

            override fun onTuningStateChanged(state: TuningState) {
                snapshot(entry).forEach { it.onTuningStateChanged(state) }
            }
        }

    private fun snapshot(entry: Entry): List<ExternalScannerListener> = synchronized(lock) {
        buildList {
            entry.original?.let(::add)
            entry.listeners.forEach { candidate ->
                if (none { it === candidate }) add(candidate)
            }
        }
    }
}
