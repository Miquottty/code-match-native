package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.ble.BleTransportReadiness

/** SDK device identity kept outside the application-facing scanner API. */
internal data class InateckSdkDevice(
    val id: String,
    val name: String,
)

/**
 * Narrow seam around the official Inateck Android SDK.
 *
 * The adapter deliberately exposes neither raw command replies nor vendor
 * exceptions to UI/diagnostics. Scan bytes have a separate callback and are
 * consumed only by the payload decoder.
 */
internal interface InateckSdkGateway {
    val readiness: BleTransportReadiness

    fun startDiscovery(
        onDevice: (InateckSdkDevice) -> Unit,
        onFinished: () -> Unit,
    ): Boolean

    fun stopDiscovery(): Boolean

    fun connect(
        deviceId: String,
        onScanBytes: (ByteArray) -> Unit,
        onDisconnected: (unexpected: Boolean) -> Unit,
        completion: (Result<Unit>) -> Unit,
    ): Boolean

    fun disconnect(deviceId: String, completion: (Result<Unit>) -> Unit): Boolean

    fun readSettings(
        deviceId: String,
        completion: (Result<List<Map<String, String>>>) -> Unit,
    ): Boolean

    fun writeSettings(
        deviceId: String,
        commandJson: String,
        completion: (Result<Unit>) -> Unit,
    ): Boolean

    /** Reads the live identity, changes only illumination, then verifies it. */
    fun setIllumination(
        deviceId: String,
        enabled: Boolean,
        completion: (Result<Unit>) -> Unit,
    ): Boolean = false

    /**
     * Reads the inventory, writes only the tuning items that differ from
     * [InateckTuningSettings.profile], then reads back to confirm.
     */
    fun applyTuning(
        deviceId: String,
        completion: (InateckTuningOutcome) -> Unit,
    ): Boolean = false

    /**
     * Receives the connect-time Bluetooth-mode check (#137). Before the link
     * is reported connected, the gateway reads the settings; a scanner that is
     * not in GATT mode is switched with [InateckGattMode.SWITCH_COMMAND],
     * restarted, and its connection then fails so the host can follow it.
     */
    fun setGattModeListener(listener: ((InateckGattModeEvent) -> Unit)?) {}

    fun close()
}
