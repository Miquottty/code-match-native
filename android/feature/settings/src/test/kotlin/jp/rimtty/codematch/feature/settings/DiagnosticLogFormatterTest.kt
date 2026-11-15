package jp.rimtty.codematch.feature.settings

import java.time.Instant
import java.time.ZoneId
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticCategory
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogFormatterTest {
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private val header = DiagnosticLogFormatter.Header(
        appVersion = "0.1.0 (1)",
        device = "Google Pixel 7",
        system = "Android 16 (API 36)",
        connection = "Connected",
        configuration = "Ready",
        illumination = "OFF",
        tuning = "MATCHED",
    )

    @Test fun formatsHeaderAndEventsInChronologicalOrder() {
        val events = listOf(
            DiagnosticEvent(DiagnosticCategory.CONFIGURATION, "symbology-ready", 1_757_000_001_500L, 2),
            DiagnosticEvent(DiagnosticCategory.CONNECTION, "connected", 1_757_000_000_000L, 1),
            DiagnosticEvent(DiagnosticCategory.ERROR, "timeout", 1_757_000_002_000L, 3),
        )
        val text = DiagnosticLogFormatter.format(events, header, zone)
        val lines = text.lines()
        assertEquals("CodeMatch Bluetooth diagnostics (Android)", lines[0])
        assertEquals("app: 0.1.0 (1)", lines[1])
        assertEquals("tuning: MATCHED", lines[7])
        assertEquals("events: 3", lines[8])
        assertEquals("", lines[9])
        assertEquals("2025-09-05T00:33:20.000+09:00 #1 connection: connected", lines[10])
        assertEquals("2025-09-05T00:33:21.500+09:00 #2 configuration: symbology-ready", lines[11])
        assertEquals("2025-09-05T00:33:22.000+09:00 #3 error: timeout", lines[12])
    }

    @Test fun stateLabelsDoNotDependOnClassNamesOrReasons() {
        assertEquals("Connected", DiagnosticLogFormatter.connectionLabel(
            ConnectionState.Connected(ScannerDevice(id = "id", name = "HPRT")),
        ))
        assertEquals("Failed", DiagnosticLogFormatter.connectionLabel(ConnectionState.Failed("raw reason")))
        assertEquals("Idle", DiagnosticLogFormatter.connectionLabel(ConnectionState.Idle))
        assertEquals("Ready", DiagnosticLogFormatter.configurationLabel(ConfigurationState.Ready))
        assertEquals("Failed", DiagnosticLogFormatter.configurationLabel(ConfigurationState.Failed("raw reason")))
    }

    @Test fun fileNameUsesLocalTimestamp() {
        val name = DiagnosticLogFormatter.fileName(Instant.ofEpochMilli(1_757_000_000_000L), zone)
        assertEquals("codematch-ble-diagnostics-20250905-003320.txt", name)
    }

    @Test
    fun recentRowsAreNewestFirstWithLocalTimeAndStatusText() {
        val events = listOf(
            DiagnosticEvent(DiagnosticCategory.CONFIGURATION, "symbology-ready", 1_757_000_001_500L, 2),
            DiagnosticEvent(DiagnosticCategory.CONNECTION, "connected", 1_757_000_000_000L, 1),
            DiagnosticEvent(DiagnosticCategory.ERROR, "timeout", 1_757_000_002_000L, 3),
        )

        assertEquals(
            listOf(
                DiagnosticLogFormatter.Row("00:33:22", "timeout", isError = true),
                DiagnosticLogFormatter.Row("00:33:21", "symbology-ready", isError = false),
                DiagnosticLogFormatter.Row("00:33:20", "connected", isError = false),
            ),
            DiagnosticLogFormatter.recentRows(events, limit = 20, zoneId = zone),
        )
        // Only the newest `limit` events are listed.
        assertEquals(
            listOf("timeout", "symbology-ready"),
            DiagnosticLogFormatter.recentRows(events, limit = 2, zoneId = zone).map { it.message },
        )
        assertEquals(emptyList<DiagnosticLogFormatter.Row>(), DiagnosticLogFormatter.recentRows(emptyList(), 20, zone))
    }
}
