package jp.rimtty.codematch.core.export

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsMailContentTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    // 2026-09-27 22:30:15 JST
    private val generatedAt = Instant.parse("2026-09-27T13:30:15Z")
    private val summary = DiagnosticsMailContent.Summary(
        versionName = "0.1.2",
        versionCode = 3,
        deviceModel = "Pixel 7",
        system = "Android 16 (API 36)",
        connection = "Connected",
        configuration = "Ready",
        eventCount = 42,
    )

    @Test
    fun recipientIsTheAdministratorAndNotTheReportRecipient() {
        assertEquals("contact-codematch@googlegroups.com", DiagnosticsMailContent.RECIPIENT)
        assertEquals(
            listOf("contact-codematch@googlegroups.com"),
            DiagnosticsMailContent.recipients.toList(),
        )
        assertNotEquals(ReportMailContent.RECIPIENT, DiagnosticsMailContent.RECIPIENT)
        assertEquals("takemoto1075@icloud.com", ReportMailContent.RECIPIENT)
    }

    @Test
    fun subjectIsShortAndSortableByVersionDeviceAndTime() {
        val mail = DiagnosticsMailContent.build(summary, generatedAt, tokyo)
        assertEquals("CodeMatch 診断ログ 0.1.2 (3) Pixel 7 2026/09/27 22:30", mail.subject)
    }

    @Test
    fun fileNameUsesTheBluetoothDiagnosticsStemAndMinutePrecision() {
        assertEquals(
            "codematch-bluetooth-diagnostics-20260927-2230.txt",
            DiagnosticsMailContent.fileName(generatedAt, tokyo),
        )
        assertEquals(
            "codematch-bluetooth-diagnostics-20260927-1330.txt",
            DiagnosticsMailContent.fileName(generatedAt, ZoneId.of("UTC")),
        )
        assertEquals(
            DiagnosticsMailContent.fileName(generatedAt, tokyo),
            DiagnosticsMailContent.build(summary, generatedAt, tokyo).fileName,
        )
    }

    @Test
    fun bodySummarisesTheHeaderAndNamesTheAttachment() {
        val mail = DiagnosticsMailContent.build(summary, generatedAt, tokyo)
        assertEquals(
            listOf(
                "CodeMatch の Bluetooth スキャナ診断ログをお送りします。",
                "診断ログには読み取った値は含まれていません。",
                "",
                "アプリ: 0.1.2 (3)",
                "端末: Pixel 7 / Android 16 (API 36)",
                "接続状態: Connected",
                "読み取り設定: Ready",
                "記録件数: 42 件",
                "作成日時: 2026/09/27 22:30",
                "",
                "添付ファイル:",
                "codematch-bluetooth-diagnostics-20260927-2230.txt",
            ).joinToString("\n"),
            mail.body,
        )
    }

    @Test
    fun headerValuesCannotBreakTheSubjectOrBodyLines() {
        val mail = DiagnosticsMailContent.build(
            summary.copy(deviceModel = "Pixel\n7", versionName = "0.1.2\r\n"),
            generatedAt,
            tokyo,
        )
        assertFalse(mail.subject.contains('\n'))
        assertFalse(mail.subject.contains('\r'))
        assertEquals("CodeMatch 診断ログ 0.1.2 (3) Pixel 7 2026/09/27 22:30", mail.subject)
        assertTrue(mail.body.lines().contains("端末: Pixel 7 / Android 16 (API 36)"))
    }

    @Test
    fun attachmentIsWrittenOnceIntoTheExportCacheAndOlderCopiesAreRemoved() {
        val directory = Files.createTempDirectory("codematch-export").toFile()
        try {
            val older = File(directory, "codematch-bluetooth-diagnostics-20260927-2200.txt")
                .apply { writeText("old") }
            val unrelated = File(directory, "codematch-scan-log-20260927-2200.jsonl")
                .apply { writeText("scan log") }
            val name = DiagnosticsMailContent.fileName(generatedAt, tokyo)

            val written = BluetoothDiagnosticsExporter.writeToDirectory(directory, "diagnostics", name)

            assertEquals(File(directory, name).canonicalFile, written.canonicalFile)
            assertEquals("diagnostics", written.readText())
            assertFalse(older.exists())
            assertTrue(unrelated.exists())
            assertEquals(
                HistoryJsonExporter.CACHE_DIRECTORY,
                BluetoothDiagnosticsExporter.CACHE_DIRECTORY,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun attachmentRejectsAnyOtherFileName() {
        val directory = Files.createTempDirectory("codematch-export").toFile()
        try {
            BluetoothDiagnosticsExporter.writeToDirectory(directory, "x", "../escape.txt")
        } finally {
            directory.deleteRecursively()
        }
    }
}
