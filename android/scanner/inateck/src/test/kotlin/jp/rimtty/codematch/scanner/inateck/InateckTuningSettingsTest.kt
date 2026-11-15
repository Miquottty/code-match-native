package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckTuningSettingsTest {
    private fun item(name: String, value: String, area: String = "area-$name") =
        mapOf("area" to area, "name" to name, "value" to value)

    private val inventory = listOf(
        item("code128_on", "1"),
        item("qrcode_read_more_code", "0"),
        item("datamatrix_read_multi", "1"),
        item("read_inverse_color", "0"),
        item("time_auto_off", "10"),
        item("auto_close_mode", "10"),
        item("lighting_lamp_control", "2"),
    )

    @Test fun presentListsOnlyProfileItemsReportedByTheScanner() {
        assertEquals(
            listOf(
                InateckTuningSettings.Item("qrcode_read_more_code", 0),
                InateckTuningSettings.Item("datamatrix_read_multi", 1),
                InateckTuningSettings.Item("read_inverse_color", 0),
                InateckTuningSettings.Item("auto_close_mode", 10),
            ),
            InateckTuningSettings.present(inventory),
        )
        assertTrue(InateckTuningSettings.present(listOf(item("code128_on", "1"))).isEmpty())
    }

    @Test fun differencesAndCommandUseOnlyDifferingItemsWithReportedAreas() {
        val differences = InateckTuningSettings.differences(inventory)
        assertEquals(
            listOf(
                InateckTuningSettings.Item("datamatrix_read_multi", 0),
                InateckTuningSettings.Item("auto_close_mode", 20),
            ),
            differences,
        )
        val command = JsonParser.parseString(InateckTuningSettings.command(inventory, differences)).asJsonArray
        assertEquals(2, command.size())
        assertEquals(
            item("datamatrix_read_multi", "0"),
            command[0].asJsonObject.entrySet().associate { it.key to it.value.asString },
        )
        assertEquals(
            item("auto_close_mode", "20"),
            command[1].asJsonObject.entrySet().associate { it.key to it.value.asString },
        )
        assertNull(InateckTuningSettings.command(inventory, emptyList()))
    }

    @Test fun matchingInventoryNeedsNoWriteAndConfirms() {
        val matching = inventory.map { entry ->
            when (entry["name"]) {
                "datamatrix_read_multi" -> item("datamatrix_read_multi", "0")
                "auto_close_mode" -> item("auto_close_mode", "20")
                else -> entry
            }
        }
        assertTrue(InateckTuningSettings.differences(matching).isEmpty())
        assertTrue(InateckTuningSettings.confirmed(matching))
        assertFalse(InateckTuningSettings.confirmed(inventory))
        assertFalse(InateckTuningSettings.confirmed(listOf(item("code128_on", "1"))))
    }

    @Test fun bufferSettingsAreAlignedOnlyWhereTheScannerReportsThem() {
        // BCST-36 as the official app shows it out of the box (#155): nothing
        // erases the buffer, so reads made while disconnected pile up.
        val factory = listOf(
            item("inventory_mode", "0"),
            item("start_up_clean_cache", "0"),
            item("auto_upload_cache", "0"),
            item("d_click_upload_flag", "1"),
        )
        val differences = InateckTuningSettings.differences(factory)
        assertEquals(
            listOf(
                InateckTuningSettings.Item("start_up_clean_cache", 1),
                InateckTuningSettings.Item("d_click_upload_flag", 0),
            ),
            differences,
        )
        val command = JsonParser.parseString(InateckTuningSettings.command(factory, differences)).asJsonArray
        assertEquals(
            listOf(item("start_up_clean_cache", "1"), item("d_click_upload_flag", "0")),
            command.map { entry -> entry.asJsonObject.entrySet().associate { it.key to it.value.asString } },
        )

        val inventoryModeLeftOn = listOf(item("inventory_mode", "1"), item("auto_upload_cache", "1"))
        assertEquals(
            listOf(
                InateckTuningSettings.Item("inventory_mode", 0),
                InateckTuningSettings.Item("auto_upload_cache", 0),
            ),
            InateckTuningSettings.differences(inventoryModeLeftOn),
        )

        // The BCST-36 (V2.6.16) spells the double-click item `dclick_upload_flag`.
        val bcst36 = listOf(
            item("inventory_mode", "1"),
            item("start_up_clean_cache", "1"),
            item("auto_upload_cache", "0"),
            item("dclick_upload_flag", "1"),
        )
        assertEquals(
            listOf(
                InateckTuningSettings.Item("inventory_mode", 0),
                InateckTuningSettings.Item("dclick_upload_flag", 0),
            ),
            InateckTuningSettings.differences(bcst36),
        )
        assertEquals(
            "Scanner data modes: inventory=1 autoUpload=0 clearAtStartup=1 doubleClick=1",
            InateckDataModes.from(bcst36).diagnosticLine(),
        )

        // A model without cache management reports none of them: nothing is written.
        assertTrue(InateckTuningSettings.differences(inventory).none { it.name.contains("cache") })
        assertTrue(
            InateckTuningSettings.confirmed(
                listOf(
                    item("inventory_mode", "0"),
                    item("start_up_clean_cache", "1"),
                    item("auto_upload_cache", "0"),
                    item("d_click_upload_flag", "0"),
                ),
            ),
        )
    }

    @Test fun emptyOrPartialInventoryIsIncompleteRatherThanUnsupported() {
        // Device log, 2026-11-15: a disturbed command channel answered the
        // settings read with zero items.
        assertFalse(InateckTuningSettings.isCompleteInventory(emptyList()))
        assertFalse(InateckTuningSettings.isCompleteInventory(listOf(item("code128_on", "1"))))
        assertTrue(
            InateckTuningSettings.isCompleteInventory(
                listOf(item("code128_on", "1"), item("qrcode_on", "1")),
            ),
        )
        // The empty answer has no tuning item either, which is why it used to
        // be reported as an unsupported model.
        assertTrue(InateckTuningSettings.present(emptyList()).isEmpty())
    }

    @Test fun ambiguousOrInvalidEntriesAreIgnored() {
        val duplicated = inventory + item("auto_close_mode", "10", area = "other")
        assertTrue(InateckTuningSettings.present(duplicated).none { it.name == "auto_close_mode" })
        val blankArea = listOf(item("auto_close_mode", "10", area = " "))
        assertTrue(InateckTuningSettings.present(blankArea).isEmpty())
        val notANumber = listOf(item("auto_close_mode", "x"))
        assertTrue(InateckTuningSettings.present(notANumber).isEmpty())
    }
}
