package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Connect-time read tuning applied through the official SDK inventory API.
 *
 * Names and values were confirmed on HPRT-4F5F (2026-09-05, see
 * docs/ios/IMPLEMENTATION_GUIDE.md): the multi-code and inverse items are
 * the per-symbology forms of the generic flags 1049 / 1020, and
 * `auto_close_mode` is the red-light auto-off time (flag 1023, 0.2 s units;
 * 20 keeps the light on for about 4 s instead of 2 s). Only items present in
 * the reported inventory are considered and only differing items are written,
 * always with the area the device reported. Values persist on the scanner and
 * are not restored on disconnect, like illumination.
 *
 * The last four items keep the scanner's built-in buffer from filling up
 * (#155): inventory mode off, erase the buffer at power-on, and never replay
 * buffered reads into the app (auto upload and double-click upload off). A
 * scanner stores what it reads while no host is connected, and once the
 * buffer is full it rejects every read.
 */
internal object InateckTuningSettings {
    data class Item(val name: String, val value: Int)

    val profile: List<Item> = listOf(
        Item("qrcode_read_more_code", 0),
        Item("datamatrix_read_multi", 0),
        Item("pdf417_read_more_code", 0),
        Item("read_inverse_color", 0),
        Item("qrcode_read_phase", 0),
        Item("datamatrix_read_phase", 0),
        Item("hanxin_read_phase", 0),
        Item("pdf417_read_phase", 0),
        Item("auto_close_mode", 20),
        Item("inventory_mode", 0),
        Item("start_up_clean_cache", 1),
        Item("auto_upload_cache", 0),
        // The double-click upload item is spelled differently across models
        // (the BCST-36 reports `dclick_upload_flag`); only the reported one applies.
        Item("d_click_upload_flag", 0),
        Item("dclick_upload_flag", 0),
    )

    private data class Reported(val area: String, val value: Int)

    private fun reported(inventory: List<Map<String, String>>): Map<String, Reported> {
        val byName = HashMap<String, Reported?>()
        for (entry in inventory) {
            val name = entry["name"] ?: continue
            if (profile.none { it.name == name }) continue
            val area = entry["area"]?.takeIf { it.isNotBlank() }
            val value = entry["value"]?.toIntOrNull()
            // A duplicated name is ambiguous; drop it rather than guess the area.
            byName[name] = if (byName.containsKey(name) || area == null || value == null) null else Reported(area, value)
        }
        return byName.filterValues { it != null }.mapValues { it.value!! }
    }

    /** Profile items the scanner reports, with their current values. */
    fun present(inventory: List<Map<String, String>>): List<Item> {
        val current = reported(inventory)
        return profile.mapNotNull { desired -> current[desired.name]?.let { Item(desired.name, it.value) } }
    }

    /** Profile items the scanner reports whose current value differs. */
    fun differences(inventory: List<Map<String, String>>): List<Item> {
        val current = reported(inventory)
        return profile.filter { desired -> current[desired.name]?.let { it.value != desired.value } == true }
    }

    fun command(inventory: List<Map<String, String>>, items: List<Item>): String? {
        if (items.isEmpty()) return null
        val current = reported(inventory)
        val array = JsonArray()
        for (item in items) {
            val area = current[item.name]?.area ?: return null
            array.add(JsonObject().apply {
                addProperty("area", area)
                addProperty("name", item.name)
                addProperty("value", item.value.toString())
            })
        }
        return array.toString()
    }

    fun confirmed(inventory: List<Map<String, String>>): Boolean =
        present(inventory).isNotEmpty() && differences(inventory).isEmpty()
}

internal enum class InateckTuningOutcome { UNSUPPORTED, MATCHED, APPLIED, FAILED }
