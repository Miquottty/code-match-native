package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertEquals
import org.junit.Test

class InateckDeviceInfoTest {
    @Test fun diagnosticLinesReportEachValueOnOneLine() {
        assertEquals(
            listOf(
                "Scanner version: V1.2 B3",
                "Scanner hardware: HW-7",
                "Scanner battery: 42",
            ),
            InateckDeviceInfo(version = "V1.2\nB3 ", hardware = "HW-7", battery = 42).diagnosticLines(),
        )
    }

    @Test fun diagnosticLinesMarkUnansweredAndBlankValuesUnavailable() {
        assertEquals(
            listOf(
                "Scanner version: unavailable",
                "Scanner hardware: unavailable",
                "Scanner battery: unavailable",
            ),
            InateckDeviceInfo(version = null, hardware = " ", battery = null).diagnosticLines(),
        )
    }

    @Test fun diagnosticLinesTruncateLongValues() {
        val line = InateckDeviceInfo(version = "x".repeat(200), hardware = null, battery = null)
            .diagnosticLines().first()
        assertEquals("Scanner version: " + "x".repeat(80), line)
    }
}
