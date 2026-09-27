package jp.rimtty.codematch.scanner.api

/** The input adapter that produced a scan callback. */
enum class InputSource {
    CAMERA,
    BLUETOOTH,
    ;

    companion object {
        // These aliases make call sites read naturally while keeping the
        // serialized/API values stable and idiomatic for Kotlin.
        val Camera: InputSource get() = CAMERA
        val Bluetooth: InputSource get() = BLUETOOTH
    }
}

/**
 * The two symbologies used by a comparison session.
 *
 * A scanner may physically report either value at any time. The scan reducer
 * is responsible for enforcing the logical QR -> Code 128 order.
 */
enum class ScanFormat {
    QR,
    CODE_128,
    ;

    val isQr: Boolean get() = this == QR

    companion object {
        // Compatibility spellings for adapters that call the second format a
        // barcode or use the compact Code128 spelling.
        val BARCODE: ScanFormat get() = CODE_128
        val CODE128: ScanFormat get() = CODE_128
    }
}

typealias ScanInputSource = InputSource
typealias ScanType = ScanFormat
typealias ExpectedCode = ScanFormat

/** A scanner callback with its source and logical symbology attached. */
data class ScanPayload(
    val value: String,
    val source: InputSource,
    val format: ScanFormat,
    val timestampMillis: Long = 0L,
) {
    /** Allows callers that use `type = ...` terminology to construct a value. */
    constructor(
        value: String,
        type: ScanFormat,
        source: InputSource,
        timestampMillis: Long = 0L,
    ) : this(value, source, type, timestampMillis)

    val rawValue: String get() = value
    val type: ScanFormat get() = format
    val kind: ScanFormat get() = format
    val capturedAtMillis: Long get() = timestampMillis

    companion object {
        fun qr(
            value: String,
            source: InputSource = InputSource.CAMERA,
            timestampMillis: Long = 0L,
        ): ScanPayload = ScanPayload(value, source, ScanFormat.QR, timestampMillis)

        fun code128(
            value: String,
            source: InputSource = InputSource.CAMERA,
            timestampMillis: Long = 0L,
        ): ScanPayload = ScanPayload(value, source, ScanFormat.CODE_128, timestampMillis)

        fun barcode(
            value: String,
            source: InputSource = InputSource.CAMERA,
            timestampMillis: Long = 0L,
        ): ScanPayload = code128(value, source, timestampMillis)
    }
}

/** A discoverable scanner device. */
data class ScannerDevice(
    val id: String,
    val name: String,
) {
    val identifier: String get() = id
}

enum class DiagnosticCategory {
    CONNECTION,
    CONFIGURATION,
    ERROR,
}

typealias DiagnosticKind = DiagnosticCategory

/**
 * A stable, localized-UI independent classification for scanner failures.
 *
 * Adapters may still expose a sanitized [ConnectionState] reason for
 * diagnostics, but application UI must branch on this type rather than
 * displaying an exception or transport string verbatim.  The values are
 * intentionally protocol/OS neutral so a future Android BLE adapter can map
 * its permission and radio callbacks without changing the feature modules.
 */
enum class ScannerIssue {
    NONE,
    PERMISSION_DENIED,
    POWERED_OFF,
    UNSUPPORTED,
    UNAVAILABLE,
    CONNECTION_FAILED,
    CONFIGURATION_FAILED,
    RESTORE_FAILED,
    /**
     * The scanner was switched to GATT mode and restarted, but the restarted
     * scanner could not be identified; the operator picks it from the search
     * results (#137).
     */
    RESELECT_REQUIRED,
    ;

    val isActionable: Boolean get() = this != NONE
    val requiresSystemSettings: Boolean
        get() = this == PERMISSION_DENIED || this == POWERED_OFF
}

/** Adapter failure reasons that carry a typed meaning for the UI. */
object ScannerFailureReasons {
    /** Published by an adapter after an unresolved GATT mode switch. */
    const val RESELECT_AFTER_GATT_SWITCH =
        "Scanner switched to GATT mode; select it from the search results"
}

/** Compatibility spellings for hosts that call this a Bluetooth issue. */
typealias BluetoothScannerIssue = ScannerIssue
typealias ScannerFailureReason = ScannerIssue

/**
 * A sanitized connection/configuration event.
 *
 * Scan text is intentionally not a field on this model. Implementations must
 * never put scan payloads into diagnostic messages.
 */
data class DiagnosticEvent(
    val category: DiagnosticCategory,
    val message: String,
    val timestampMillis: Long = 0L,
    val sequence: Long = 0L,
) {
    constructor(
        timestamp: Long,
        message: String,
        category: DiagnosticCategory = DiagnosticCategory.CONNECTION,
        sequence: Long = 0L,
    ) : this(category, message, timestamp, sequence)

    val kind: DiagnosticCategory get() = category
    val dateMillis: Long get() = timestampMillis
}

/** Connection/discovery state exposed by every scanner adapter. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Searching : ConnectionState
    data class Connecting(val device: ScannerDevice) : ConnectionState
    data class Connected(val device: ScannerDevice) : ConnectionState
    data class Unavailable(val reason: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState

    val connectedDevice: ScannerDevice?
        get() = when (this) {
            is Connected -> device
            else -> null
        }

    val isConnected: Boolean get() = connectedDevice != null

    companion object {
        val IDLE: ConnectionState get() = Idle
        val SEARCHING: ConnectionState get() = Searching
        val idle: ConnectionState get() = Idle
        val searching: ConnectionState get() = Searching
    }
}

/** Scanner symbology/configuration state. */
sealed interface ConfigurationState {
    data object Unavailable : ConfigurationState
    data object Configuring : ConfigurationState
    data object Ready : ConfigurationState
    data class Failed(val reason: String) : ConfigurationState

    val isReady: Boolean get() = this === Ready

    companion object {
        val UNAVAILABLE: ConfigurationState get() = Unavailable
        val CONFIGURING: ConfigurationState get() = Configuring
        val READY: ConfigurationState get() = Ready
        val unavailable: ConfigurationState get() = Unavailable
        val configuring: ConfigurationState get() = Configuring
        val ready: ConfigurationState get() = Ready
    }
}

/**
 * Classify scanner state for presentation without exposing adapter strings.
 *
 * The matching is deliberately limited to generic availability/permission
 * terms.  It does not infer a scanner model, protocol, UUID, or payload from
 * a reason string; an unrecognized value remains a generic failure.
 */
fun scannerIssueFor(
    connectionState: ConnectionState,
    configurationState: ConfigurationState,
): ScannerIssue {
    val configurationFailure = (configurationState as? ConfigurationState.Failed)?.reason
    if (configurationFailure != null) {
        return classifyScannerFailure(configurationFailure, ScannerIssue.CONFIGURATION_FAILED)
    }

    return when (connectionState) {
        is ConnectionState.Unavailable -> classifyUnavailable(connectionState.reason)
        is ConnectionState.Failed ->
            if (connectionState.reason == ScannerFailureReasons.RESELECT_AFTER_GATT_SWITCH) {
                ScannerIssue.RESELECT_REQUIRED
            } else {
                classifyScannerFailure(connectionState.reason, ScannerIssue.CONNECTION_FAILED)
            }
        else -> ScannerIssue.NONE
    }
}

private fun classifyUnavailable(reason: String): ScannerIssue {
    val normalized = reason.lowercase()
    return when {
        normalized.containsAny("permission", "unauthor", "denied", "権限", "許可") ->
            ScannerIssue.PERMISSION_DENIED
        normalized.containsAny(
            "powered off",
            "power off",
            "bluetooth off",
            "bluetooth is off",
            "radio off",
            "電源オフ",
            "オフ",
        ) -> ScannerIssue.POWERED_OFF
        normalized.containsAny("unsupported", "not supported", "未対応") ->
            ScannerIssue.UNSUPPORTED
        else -> ScannerIssue.UNAVAILABLE
    }
}

private fun classifyScannerFailure(
    reason: String,
    fallback: ScannerIssue,
): ScannerIssue {
    val normalized = reason.lowercase()
    return if (normalized.containsAny("restore", "recover", "復元", "復旧")) {
        ScannerIssue.RESTORE_FAILED
    } else {
        fallback
    }
}

private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
