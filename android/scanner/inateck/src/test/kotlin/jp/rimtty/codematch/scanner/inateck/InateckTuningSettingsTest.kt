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
        // BCST-36 as the official app shows it out of the box (#155): every
        // buffer item already has the profile's value, so nothing is written.
        // What piled up while disconnected is counted and erased on connect.
        val factory = listOf(
            item("inventory_mode", "0"),
            item("start_up_clean_cache", "0"),
            item("auto_upload_cache", "0"),
            item("d_click_upload_flag", "1"),
        )
        assertTrue(InateckTuningSettings.differences(factory).isEmpty())
        assertTrue(InateckTuningSettings.confirmed(factory))

        val inventoryModeLeftOn = listOf(item("inventory_mode", "1"), item("auto_upload_cache", "1"))
        assertEquals(
            listOf(
                InateckTuningSettings.Item("inventory_mode", 0),
                InateckTuningSettings.Item("auto_upload_cache", 0),
            ),
            InateckTuningSettings.differences(inventoryModeLeftOn),
        )

        // A BCST-36 (V2.6.16, spelling `dclick_upload_flag`) as the #156 build
        // left it. With erase-at-power-on ON it stops scanning after a cold
        // start on battery (#162), so that item goes back to OFF; double-click
        // upload is left alone.
        val writtenBy156 = listOf(
            item("inventory_mode", "0"),
            item("start_up_clean_cache", "1"),
            item("auto_upload_cache", "0"),
            item("dclick_upload_flag", "0"),
        )
        val differences = InateckTuningSettings.differences(writtenBy156)
        assertEquals(listOf(InateckTuningSettings.Item("start_up_clean_cache", 0)), differences)
        val command = JsonParser.parseString(InateckTuningSettings.command(writtenBy156, differences)).asJsonArray
        assertEquals(
            listOf(item("start_up_clean_cache", "0")),
            command.map { entry -> entry.asJsonObject.entrySet().associate { it.key to it.value.asString } },
        )
        assertFalse(InateckTuningSettings.confirmed(writtenBy156))
        assertEquals(
            "Scanner data modes: inventory=0 autoUpload=0 clearAtStartup=1 doubleClick=0",
            InateckDataModes.from(writtenBy156).diagnosticLine(),
        )
        assertTrue(InateckTuningSettings.profile.none { it.name.contains("click_upload_flag") })

        // A model without cache management reports none of them: nothing is written.
        assertTrue(InateckTuningSettings.differences(inventory).none { it.name.contains("cache") })
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

    @Test
    fun diagnosticValuesLineListsOnlyTheReadQualityIntegersInTheIosWording() {
        val inventory = listOf(
            mapOf("area" to "1", "name" to "qrcode_on", "value" to "1"),
            mapOf("area" to "2", "name" to "lighting_lamp_control", "value" to "2"),
            mapOf("area" to "3", "name" to "auto_close_mode", "value" to "20"),
            mapOf("area" to "4", "name" to "scan_mode", "value" to "0"),
            // Text settings and names outside the list never reach the log.
            mapOf("area" to "5", "name" to "prefix", "value" to "ABC"),
            mapOf("area" to "6", "name" to "security_level", "value" to "not-a-number"),
        )
        assertEquals(
            "Scanner tuning values: auto_close_mode=20,lighting_lamp_control=2,scan_mode=0",
            InateckTuningSettings.diagnosticValuesLine(inventory),
        )
        assertEquals(
            null,
            InateckTuningSettings.diagnosticValuesLine(
                listOf(mapOf("area" to "1", "name" to "qrcode_on", "value" to "1")),
            ),
        )
    }
}
