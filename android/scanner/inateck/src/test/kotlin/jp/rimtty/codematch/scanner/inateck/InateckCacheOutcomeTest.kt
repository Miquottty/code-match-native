package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertEquals
import org.junit.Test

class InateckCacheOutcomeTest {
    @Test fun diagnosticLineReportsCountsOnly() {
        assertEquals("Scanner cache: unavailable", InateckCacheOutcome(stored = null).diagnosticLine())
        assertEquals("Scanner cache: 0 stored", InateckCacheOutcome(stored = 0).diagnosticLine())
        assertEquals(
            "Scanner cache: 3 stored, cleared, 0 remain",
            InateckCacheOutcome(stored = 3, cleared = true, remaining = 0).diagnosticLine(),
        )
        assertEquals(
            "Scanner cache: 3 stored, cleared, 2 remain",
            InateckCacheOutcome(stored = 3, cleared = true, remaining = 2).diagnosticLine(),
        )
        assertEquals(
            "Scanner cache: 3 stored, cleared, remain unknown",
            InateckCacheOutcome(stored = 3, cleared = true).diagnosticLine(),
        )
        assertEquals(
            "Scanner cache: 3 stored, clear failed",
            InateckCacheOutcome(stored = 3, cleared = false).diagnosticLine(),
        )
    }

    @Test fun dataModesComeFromTheReportedInventoryAndMissingItemsAreUnknown() {
        fun item(name: String, value: String) = mapOf("area" to "1", "name" to name, "value" to value)

        val reported = InateckDataModes.from(
            listOf(
                item("inventory_mode", "0"),
                item("auto_upload_cache", "0"),
                item("start_up_clean_cache", "1"),
                item("d_click_upload_flag", "1"),
                item("code128_on", "1"),
            ),
        )
        assertEquals(
            "Scanner data modes: inventory=0 autoUpload=0 clearAtStartup=1 doubleClick=1",
            reported.diagnosticLine(),
        )

        val withoutCacheManagement = InateckDataModes.from(listOf(item("code128_on", "1")))
        assertEquals(
            "Scanner data modes: inventory=unknown autoUpload=unknown clearAtStartup=unknown doubleClick=unknown",
            withoutCacheManagement.diagnosticLine(),
        )
    }
}
