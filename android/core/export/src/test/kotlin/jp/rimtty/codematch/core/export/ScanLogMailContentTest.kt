package jp.rimtty.codematch.core.export

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScanLogMailContentTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    // 2026-11-20 16:44:30 JST
    private val exportedAt = Instant.parse("2026-11-20T07:44:30Z")
    private val summary = ScanLogMailContent.Summary(
        versionName = "0.1.6",
        versionCode = 7,
        deviceModel = "SC-53F",
        system = "Android 16 (API 36)",
        eventCount = 1234,
    )

    @Test
    fun recipientIsTheAdministratorAndNotTheReportRecipient() {
        assertEquals("contact-codematch@googlegroups.com", ScanLogMailContent.RECIPIENT)
        assertEquals(
            listOf("contact-codematch@googlegroups.com"),
            ScanLogMailContent.recipients.toList(),
        )
        assertNotEquals(ReportMailContent.RECIPIENT, ScanLogMailContent.RECIPIENT)
    }

    @Test
    fun subjectIsShortAndSortableByVersionDeviceAndTime() {
        val mail = ScanLogMailContent.build(summary, exportedAt, tokyo)
        assertEquals("CodeMatch 照合ログ 0.1.6 (7) SC-53F 2026/11/20 16:44", mail.subject)
    }

    @Test
    fun attachmentNameIsTheScanLogExportName() {
        val mail = ScanLogMailContent.build(summary, exportedAt, tokyo)
        assertEquals("codematch-scan-log-20261120-1644.jsonl", mail.fileName)
        assertEquals(ScanLogJsonExporter.fileName(exportedAt, tokyo), mail.fileName)
    }

    @Test
    fun bodySummarisesTheExportWarnsAboutScannedValuesAndNamesTheAttachment() {
        val mail = ScanLogMailContent.build(summary, exportedAt, tokyo)
        assertEquals(
            listOf(
                "CodeMatch の照合ログをお送りします。",
                "照合ログには読み取った QR コード・バーコードの値が含まれています。",
                "",
                "アプリ: 0.1.6 (7)",
                "端末: SC-53F / Android 16 (API 36)",
                "記録件数: 1234 件",
                "作成日時: 2026/11/20 16:44",
                "",
                "添付ファイル:",
                "codematch-scan-log-20261120-1644.jsonl",
            ).joinToString("\n"),
            mail.body,
        )
    }

    @Test
    fun headerValuesNeverBreakTheSubjectOrABodyLine() {
        val mail = ScanLogMailContent.build(
            summary.copy(deviceModel = "SC\n53F", system = "Android\t16"),
            exportedAt,
            tokyo,
        )
        assertFalse(mail.subject.contains("\n"))
        assertEquals(10, mail.body.lines().size)
    }
}
