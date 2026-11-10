package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticCategory
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Availability reported by a platform BLE adapter.
 *
 * The Android adapter maps BluetoothManager/BluetoothAdapter details to this
 * small model. No Android framework or scanner-vendor type crosses the
 * boundary.
 */
sealed interface BleAvailability {
    data object Ready : BleAvailability
    data object Unknown : BleAvailability
    data object PoweredOff : BleAvailability
    data object Unauthorized : BleAvailability
    data object Unsupported : BleAvailability
    data class Failed(val reason: String) : BleAvailability
}

/**
 * Lifecycle state supplied by a platform adapter.
 *
 * The BLE core does not own an Android [Lifecycle] and must not import one.
 * An adapter maps its host/activity lifecycle to this small value before
 * starting discovery or a connection. A backgrounded or destroyed adapter is
 * never considered usable, even if the radio is powered on.
 */
enum class BleAdapterLifecycleState {
    FOREGROUND,
    BACKGROUND,
    DESTROYED,
}

/** Permission result supplied by the platform adapter, without Android types. */
enum class BlePermissionState {
    GRANTED,
    DENIED,
    UNKNOWN,
}

/**
 * Adapter-owned readiness inputs for operations that require BLE access.
 *
 * `scanner:ble` intentionally does not name Android permission constants. The
 * Android adapter can map BLUETOOTH_SCAN/CONNECT (or a vendor SDK's own gate)
 * to these fields after observing the actual target SDK/device behavior. The
 * defaults preserve the existing SDK-neutral test transports, which have no
 * platform permission gate.
 */
data class BleTransportReadiness(
    val lifecycle: BleAdapterLifecycleState = BleAdapterLifecycleState.FOREGROUND,
    val availability: BleAvailability = BleAvailability.Ready,
    val discoveryPermission: BlePermissionState = BlePermissionState.GRANTED,
    val connectionPermission: BlePermissionState = BlePermissionState.GRANTED,
) {
    fun failureReason(forConnection: Boolean): String? = when {
        lifecycle == BleAdapterLifecycleState.DESTROYED ->
            "Bluetooth adapter is closed"
        lifecycle == BleAdapterLifecycleState.BACKGROUND ->
            "Bluetooth adapter is inactive"
        availability !is BleAvailability.Ready ->
            "Bluetooth is unavailable"
        !forConnection && discoveryPermission != BlePermissionState.GRANTED ->
            "Bluetooth discovery permission is required"
        forConnection && connectionPermission != BlePermissionState.GRANTED ->
            "Bluetooth connection permission is required"
        else -> null
    }

    val isUsable: Boolean
        get() = failureReason(forConnection = true) == null
}

/** A device discovered by the platform adapter. */
data class BleDiscoveredDevice(
    val device: ScannerDevice,
    /** Advertised service UUIDs, if the platform adapter exposed them. */
    val serviceUuids: Set<String> = emptySet(),
)

/**
 * Events emitted by an Android/Core-Bluetooth implementation.
 *
 * Scan text is deliberately not carried in diagnostics. A payload is emitted
 * only as a [ScanPayload] so the application can validate and persist it
 * without accidentally putting it into a diagnostic log.
 */
sealed interface BleTransportEvent {
    data object DiscoveryStarted : BleTransportEvent
    data class DeviceFound(val device: BleDiscoveredDevice) : BleTransportEvent
    data object DiscoveryStopped : BleTransportEvent
    data class Connected(
        val device: ScannerDevice,
        /** Optional adapter/coordinator token for this physical link. */
        val linkGeneration: Long? = null,
        /** Optional token for the connect request that produced this link. */
        val requestGeneration: Long? = null,
    ) : BleTransportEvent
    data class ConnectionFailed(
        val device: ScannerDevice,
        val reason: String,
        val linkGeneration: Long? = null,
        val requestGeneration: Long? = null,
    ) : BleTransportEvent
    data class Disconnected(
        val device: ScannerDevice,
        val reason: String? = null,
        val unexpected: Boolean = true,
        val linkGeneration: Long? = null,
        val requestGeneration: Long? = null,
    ) : BleTransportEvent
    /**
     * A disconnect request failed before the adapter could prove that the
     * physical link was closed. Consumers must retain the link identity and
     * must not start a replacement connection from this event.
     */
    data class DisconnectFailed(
        val device: ScannerDevice,
        val linkGeneration: Long? = null,
        val requestGeneration: Long? = null,
    ) : BleTransportEvent
    data class ScanReceived(
        val payload: ScanPayload,
        /** Required from adapters that can deliver callbacks from old links. */
        val device: ScannerDevice? = null,
        val linkGeneration: Long? = null,
        val requestGeneration: Long? = null,
    ) : BleTransportEvent {
        companion object {
            /**
             * Converts an unwrapped/raw adapter callback through the decoder
             * boundary before creating a transport event.
             */
            fun fromRawCallback(
                callbackValue: String,
                source: InputSource,
                format: ScanFormat,
                timestampMillis: Long = 0L,
                device: ScannerDevice? = null,
                linkGeneration: Long? = null,
                requestGeneration: Long? = null,
                decoder: BleScanCallbackDecoder = BleScanPayloadFactory.observedIosDecoder,
            ): ScanReceived? = BleScanPayloadFactory.fromRawCallback(
                callbackValue = callbackValue,
                source = source,
                format = format,
                timestampMillis = timestampMillis,
                decoder = decoder,
            )?.let {
                ScanReceived(
                    payload = it,
                    device = device,
                    linkGeneration = linkGeneration,
                    requestGeneration = requestGeneration,
                )
            }
        }
    }
    data class AvailabilityChanged(val availability: BleAvailability) : BleTransportEvent
}

/** Receives platform adapter events on the adapter's serialized callback context. */
fun interface BleTransportListener {
    fun onTransportEvent(event: BleTransportEvent)
}

/**
 * SDK-neutral transport boundary for BLE discovery, connection and GATT I/O.
 *
 * Implementations must serialize their callbacks and must never include scan
 * values in error/diagnostic strings. GATT UUIDs are supplied by the adapter
 * after service discovery; this core intentionally does not hard-code a
 * scanner model's UUID layout.
 */
interface BleTransport {
    val availability: BleAvailability

    /**
     * Readiness is mapped by the platform adapter and remains overrideable for
     * tests. No adapter is required to declare Android permissions until the
     * real scanner and target-SDK behavior have been verified.
     */
    val readiness: BleTransportReadiness
        get() = BleTransportReadiness(availability = availability)

    var listener: BleTransportListener?

    fun startDiscovery(): Boolean
    fun stopDiscovery(): Boolean
    fun connect(device: ScannerDevice): Boolean
    /**
     * Connect using coordinator-owned identities. Tagged adapters must echo
     * these tokens in events rather than assume their private cancellation
     * counter advances at the same rate as the coordinator's request counter.
     * The default keeps untagged/legacy transports source compatible.
     */
    fun connect(
        device: ScannerDevice,
        requestGeneration: Long,
        linkGeneration: Long,
    ): Boolean = connect(device)
    fun disconnect(device: ScannerDevice): Boolean

    /**
     * Write one fully formed GATT command. Completion must be invoked exactly
     * once, even when the underlying API reports a synchronous failure.
     */
    fun write(
        characteristicUuid: String,
        payload: ByteArray,
        completion: (Result<Unit>) -> Unit,
    ): Boolean

    /** Read one characteristic when the adapter/protocol requires it. */
    fun read(
        characteristicUuid: String,
        completion: (Result<ByteArray>) -> Unit,
    ): Boolean
}

/** Extended state used by the BLE layer; [ConnectionState] has no reconnecting case. */
sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object Searching : BleConnectionState
    data class Connecting(val device: ScannerDevice) : BleConnectionState
    data class Connected(val device: ScannerDevice) : BleConnectionState
    data class Reconnecting(val device: ScannerDevice, val attempt: Int) : BleConnectionState
    data class Unavailable(val reason: String) : BleConnectionState
    data class Failed(val reason: String) : BleConnectionState

    val connectedDevice: ScannerDevice?
        get() = when (this) {
            is Connected -> device
            else -> null
        }

    /** Mapping for the existing scanner:api contract. Reconnecting is exposed as connecting. */
    fun asApiState(): ConnectionState = when (this) {
        Idle -> ConnectionState.Idle
        Searching -> ConnectionState.Searching
        is Connecting -> ConnectionState.Connecting(device)
        is Connected -> ConnectionState.Connected(device)
        is Reconnecting -> ConnectionState.Connecting(device)
        is Unavailable -> ConnectionState.Unavailable(reason)
        is Failed -> ConnectionState.Failed(reason)
    }
}

/** A state snapshot emitted by [BleConnectionCoordinator]. */
data class BleScannerState(
    val connection: BleConnectionState = BleConnectionState.Idle,
    val configuration: ConfigurationState = ConfigurationState.Unavailable,
    val devices: List<ScannerDevice> = emptyList(),
    val expectedFormat: ScanFormat? = null,
    val diagnostics: List<DiagnosticEvent> = emptyList(),
)

/**
 * A bounded diagnostic recorder. The API has no method accepting a scan
 * payload; callers can record only sanitized status/reason strings.
 */
class BleDiagnosticLog(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
    }

    private val events = ArrayDeque<DiagnosticEvent>(maxEvents)
    private var nextSequence = 0L

    fun connection(message: String) = append(DiagnosticCategory.CONNECTION, message)

    fun configuration(message: String) = append(DiagnosticCategory.CONFIGURATION, message)

    fun error(message: String) = append(DiagnosticCategory.ERROR, message)

    fun snapshot(): List<DiagnosticEvent> = events.toList()

    private fun append(category: DiagnosticCategory, message: String) {
        require(message.isNotBlank()) { "Diagnostic messages must not be blank" }
        events.addLast(
            DiagnosticEvent(
                category = category,
                message = message,
                timestampMillis = nowMillis(),
                sequence = nextSequence++,
            ),
        )
        while (events.size > maxEvents) events.removeFirst()
    }

    private companion object {
        /** Enough for a long shift; the settings screen shows only the latest 20. */
        const val DEFAULT_MAX_EVENTS = 1000
    }
}

/** Listener for state changes and validated payloads from the coordinator. */
interface BleScannerListener {
    fun onStateChanged(state: BleScannerState) {}
    fun onScanPayload(payload: ScanPayload) {}
}
