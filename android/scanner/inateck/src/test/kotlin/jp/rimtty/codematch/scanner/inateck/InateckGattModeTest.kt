package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonParser
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors iOS `BluetoothScannerServiceTests` GATT-successor cases (#134, #137). */
class InateckGattModeTest {
    @Test
    fun gattModeSuccessorIsTheScannerRenamedWithTheSameSuffix() {
        val origin = ScannerDevice("2B94A2A1", "Hyper 160B-4F5F-UNI")
        val renamed = ScannerDevice("4CD0F65B", "HPRT-4F5F")
        val unrelated = ScannerDevice("6680C70B", "HPRT-636E")
        // "160B" is not a whole '-'-separated component of "Hyper 160B", so
        // HPRT-160B is another scanner and must not be taken for the successor.
        val modelNumber = ScannerDevice("11111111", "HPRT-160B")

        assertEquals(
            listOf(renamed),
            InateckGattMode.successors(
                origin,
                listOf(origin, unrelated, renamed, modelNumber, renamed),
            ),
        )
        assertEquals(
            listOf(renamed),
            InateckGattMode.successors(
                ScannerDevice("2B94A2A1", "hyper 160b-4f5f-uni"),
                listOf(renamed),
            ),
        )
        assertEquals(
            listOf(ScannerDevice("4CD0F65B", " hprt - 4f5f ")),
            InateckGattMode.successors(origin, listOf(ScannerDevice("4CD0F65B", " hprt - 4f5f "))),
        )
    }

    @Test
    fun gattModeSuccessorIsEmptyWhenTheIdentityDoesNotChange() {
        val origin = ScannerDevice("4CD0F65B", "HPRT-4F5F")
        assertTrue(
            InateckGattMode.successors(
                origin,
                listOf(origin, ScannerDevice("6680C70B", "HPRT-636E")),
            ).isEmpty(),
        )
        assertTrue(
            InateckGattMode.successors(
                ScannerDevice("A", "Inateck Scanner"),
                listOf(ScannerDevice("B", "Inateck Scanner")),
            ).isEmpty(),
        )
    }

    @Test
    fun successorSuffixMustBeExactlyFourHexDigitsAfterADash() {
        val origin = ScannerDevice("O", "Hyper 160B-4F5F-UNI")
        listOf(
            "4F5F", // no '-' separated prefix
            "HPRT-4F5G", // not hex
            "HPRT-4F5", // three digits
            "HPRT-04F5F", // five digits
            "HPRT-UNI", // component present in origin but not hex
        ).forEach { name ->
            assertTrue(name, InateckGattMode.successors(origin, listOf(ScannerDevice("X", name))).isEmpty())
        }
    }

    @Test
    fun bluetoothModeCombinesTheTwoReportedBits() {
        fun settings(low: String?, high: String?) = buildList {
            add(mapOf("name" to "qrcode_on", "area" to "42", "value" to "1"))
            low?.let { add(mapOf("name" to "bt_mode_low", "area" to "1", "value" to it)) }
            high?.let { add(mapOf("name" to "bt_mode_high", "area" to "31", "value" to it)) }
        }
        assertEquals(2, InateckGattMode.bluetoothMode(settings("0", "1")))
        assertEquals(3, InateckGattMode.bluetoothMode(settings("1", "1")))
        assertEquals(0, InateckGattMode.bluetoothMode(settings("0", "0")))
        assertEquals(1, InateckGattMode.bluetoothMode(settings("1", "0")))
        assertNull(InateckGattMode.bluetoothMode(settings(null, "1")))
        assertNull(InateckGattMode.bluetoothMode(settings("0", null)))
        assertNull(InateckGattMode.bluetoothMode(settings("x", "1")))
    }

    @Test
    fun switchCommandWritesLowZeroAndHighOneLikeIos() {
        val items = JsonParser.parseString(InateckGattMode.SWITCH_COMMAND).asJsonArray
            .map { it.asJsonObject }
        assertEquals(
            listOf(
                Triple("1", "0", "bt_mode_low"),
                Triple("31", "1", "bt_mode_high"),
            ),
            items.map {
                Triple(it["area"].asString, it["value"].asString, it["name"].asString)
            },
        )
        val applied = items.map { item ->
            mapOf("name" to item["name"].asString, "value" to item["value"].asString)
        }
        assertEquals(InateckGattMode.GATT, InateckGattMode.bluetoothMode(applied))
    }
}
