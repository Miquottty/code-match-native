package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Bluetooth-mode rules shared with iOS `BluetoothScannerService` (#134, #137).
 *
 * The scanner reports its mode as two general settings; `bt_mode_low +
 * (bt_mode_high << 1)` is 2 in GATT mode. A scanner in another mode is switched
 * with [SWITCH_COMMAND] and restarted. Some models then advertise under a new
 * name and identity (`Hyper 160B-4F5F-UNI` becomes `HPRT-4F5F`), which
 * [successors] recognises.
 */
internal object InateckGattMode {
    const val GATT = 2

    /** Exactly the command iOS writes: low bit 0, high bit 1. */
    const val SWITCH_COMMAND =
        """[{"area":"1","value":"0","name":"bt_mode_low"},""" +
            """{"area":"31","value":"1","name":"bt_mode_high"}]"""

    /** The reported mode, or null when either bit is missing or unreadable. */
    fun bluetoothMode(settings: List<Map<String, String>>): Int? {
        val low = settingValue(settings, "bt_mode_low") ?: return null
        val high = settingValue(settings, "bt_mode_high") ?: return null
        return low + (high shl 1)
    }

    /**
     * Scanners that may be [origin] restarted in GATT mode under a new identity:
     * a device other than [origin] whose name's last `-`-separated component is
     * exactly four hex digits (MAC-derived) and equals one of the `-`-separated
     * components of [origin]'s name, compared trimmed and case-insensitively.
     * `HPRT-160B` is not a successor of `Hyper 160B-4F5F-UNI`, because `160B`
     * is not a whole component there. Each id is returned once, in order.
     */
    fun successors(origin: ScannerDevice, among: List<ScannerDevice>): List<ScannerDevice> {
        val originComponents = components(origin.name).toSet()
        val seen = mutableSetOf<String>()
        return among.filter { device ->
            if (device.id == origin.id || !seen.add(device.id)) return@filter false
            val parts = components(device.name)
            val suffix = parts.lastOrNull() ?: return@filter false
            parts.size >= 2 &&
                suffix.length == 4 &&
                suffix.all { it in '0'..'9' || it in 'A'..'F' } &&
                suffix in originComponents
        }
    }

    private fun components(name: String): List<String> =
        // Like Swift's split(separator:), empty pieces are dropped before trimming.
        name.split('-').filter(String::isNotEmpty).map { it.trim().uppercase() }

    private fun settingValue(settings: List<Map<String, String>>, name: String): Int? =
        settings.firstOrNull { it["name"] == name }?.get("value")?.trim()?.toIntOrNull()
}

/** Payload-free progress of the connect-time Bluetooth-mode check. */
internal sealed interface InateckGattModeEvent {
    val deviceId: String

    data class Confirmed(override val deviceId: String) : InateckGattModeEvent

    data class Unknown(override val deviceId: String) : InateckGattModeEvent

    /** The scanner reported [mode]; the GATT command is being written. */
    data class SwitchStarted(
        override val deviceId: String,
        val deviceName: String,
        val mode: Int,
    ) : InateckGattModeEvent

    /** The command was accepted and the scanner restart was requested. */
    data class Restarting(override val deviceId: String) : InateckGattModeEvent

    data class SwitchFailed(override val deviceId: String) : InateckGattModeEvent
}
