package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportMailContentTest {
    private val utc = ZoneId.of("UTC")
    private val sawaiQr =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val moltenQrPAF1 =
        "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"

    @Test
    fun recipientIsTheFixedOperatorAddress() {
        assertEquals("sizai@sawai-seisakusho.co.jp", ReportMailContent.RECIPIENT)
        assertEquals(listOf("sizai@sawai-seisakusho.co.jp"), ReportMailContent.recipients.toList())
    }

    @Test
    fun inspectionMailRepeatsTheReportHeaderAndNamesTheAttachment() {
        val session = MatchSession(
            startedAt = 1_700_000_000_000L,
            endedAt = 1_700_000_120_000L,
            name = "朝便",
            destination = Destination.SAWAI,
            entries = listOf(
                entry("one", "BCJH-52-81GG", sawaiQr, "BCJH-52-81GG@1N5X0C"),
                entry("two", "BCJH-52-81GG", sawaiQr, "BCJH-52-81GG@1N5X0D"),
            ),
        )

        val mail = ReportMailContent.build(
            session,
            HistoryReportKind.INSPECTION,
            "検品レポート_朝便.pdf",
            AppLanguage.JAPANESE,
            utc,
        )

        val start = HistoryExportTextFormatter.dateTime(1_700_000_000_000L, AppLanguage.JAPANESE, utc)
        assertEquals("検品レポート $start - 澤井製作所", mail.subject)
        assertFalse(mail.subject.contains("朝便"))
        val expectedBody = listOf(
            "検品レポートをお送りします。",
            "",
            "■ セッション",
            "セッション名: 朝便",
            "開始: ${HistoryExportTextFormatter.dateTime(1_700_000_000_000L, AppLanguage.JAPANESE, utc)}",
            "終了: ${HistoryExportTextFormatter.dateTime(1_700_000_120_000L, AppLanguage.JAPANESE, utc)}",
            "仕向地: 澤井製作所",
            "検査箱数: 2箱",
            "品番数（枝番別）: 1",
            "",
            "■ 添付",
            "検品レポート_朝便.pdf",
        ).joinToString("\n")
        assertEquals(expectedBody, mail.body)
        assertFalse("the mail never carries a raw payload", mail.body.contains(sawaiQr))
    }

    @Test
    fun historyMailOfAMoltenSessionAddsTheDeliveryNumberCountAndUsesTheStartDateWhenUnnamed() {
        val session = MatchSession(
            startedAt = 1_700_000_000_000L,
            destination = Destination.MOLTEN,
            entries = listOf(entry("one", "PAF1-15-422", moltenQrPAF1, "PAF1-15-422@0NKD3C")),
        )

        val mail = ReportMailContent.build(
            session,
            HistoryReportKind.MATCH_HISTORY,
            "MatchHistory_x.pdf",
            AppLanguage.ENGLISH,
            utc,
        )

        val start = HistoryExportTextFormatter.dateTime(1_700_000_000_000L, AppLanguage.ENGLISH, utc)
        assertEquals("Match History Report $start - Molten", mail.subject)
        assertTrue(mail.body.startsWith("Please find the match history report attached.\n\nSession\n"))
        assertTrue(mail.body.contains("Status: In progress"))
        assertTrue(mail.body.contains("Boxes: 1 box"))
        assertTrue(mail.body.contains("Part numbers: 1"))
        assertTrue(mail.body.contains("Delivery numbers: 1"))
        assertTrue(mail.body.endsWith("Attachment\nMatchHistory_x.pdf"))
        assertFalse(mail.body.contains("CodeMatch."))
        assertFalse(mail.body.contains("Session name"))
    }

    private fun entry(id: String, code: String, qrPayload: String?, barcodePayload: String?) = MatchEntry(
        id = id,
        code = code,
        matchedAt = 1_700_000_001_000L,
        qrPayload = qrPayload,
        barcodePayload = barcodePayload,
    )
}
