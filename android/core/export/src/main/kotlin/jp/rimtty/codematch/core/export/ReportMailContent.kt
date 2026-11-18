package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId

/** Subject and body of the e-mail a report is shared with. */
data class ReportMail(
    val subject: String,
    val body: String,
)

/**
 * Builds the pre-filled e-mail for sharing a session's PDF.
 *
 * The recipient is fixed: the report is only ever sent to the
 * 澤井製作所 address below. The body repeats the report header (session,
 * destination, box and row counts) so the mail is readable without opening
 * the attachment. Sending itself stays with the mail app the operator uses.
 *
 * This mirrors Swift's `ReportMailContent`.
 */
object ReportMailContent {
    /** The one address every report mail is addressed to. */
    const val RECIPIENT: String = "sizai@sawai-seisakusho.co.jp"

    val recipients: Array<String>
        get() = arrayOf(RECIPIENT)

    fun build(
        session: MatchSession,
        kind: HistoryReportKind,
        fileName: String,
        language: AppLanguage = AppLanguage.JAPANESE,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ReportMail {
        val labels = HistoryExportTextFormatter.labels(language)
        val title = when (kind) {
            HistoryReportKind.MATCH_HISTORY -> labels.reportTitle
            HistoryReportKind.INSPECTION -> labels.inspectionTitle
        }
        val destination = session.resolvedDestination()
        // `検品レポート 2026/09/07 23:17 - 澤井製作所`: the start time, never the
        // session name, so subjects sort and read like the paper sheets.
        val subject = buildString {
            append(title).append(' ')
            append(HistoryExportTextFormatter.dateTime(session.startedAt, language, zoneId))
            if (destination != null) append(" - ").append(labels.destinationName(destination))
        }

        val lines = mutableListOf<String>()
        lines += when (kind) {
            HistoryReportKind.MATCH_HISTORY -> labels.mailIntroHistory
            HistoryReportKind.INSPECTION -> labels.mailIntroInspection
        }
        lines += ""
        lines += labels.mailSessionHeading
        if (session.displayName.isNotEmpty()) {
            lines += "${labels.sessionName}: ${session.displayName}"
        }
        lines += "${labels.start}: ${HistoryExportTextFormatter.dateTime(session.startedAt, language, zoneId)}"
        val endedAt = session.endedAt
        lines += if (endedAt != null) {
            "${labels.end}: ${HistoryExportTextFormatter.dateTime(endedAt, language, zoneId)}"
        } else {
            "${labels.status}: ${labels.inProgress}"
        }
        if (destination != null) {
            lines += "${labels.destination}: ${labels.destinationName(destination)}"
        }
        lines += "${labels.inspectionBoxCount}: ${HistoryExportTextFormatter.boxCount(session.matchedCount, language)}"
        lines += countLines(session, kind, destination, labels, language)
        lines += ""
        lines += labels.mailAttachmentHeading
        lines += fileName

        return ReportMail(subject = subject, body = lines.joinToString("\n"))
    }

    /** The same count lines the report's own header prints. */
    private fun countLines(
        session: MatchSession,
        kind: HistoryReportKind,
        destination: Destination?,
        labels: HistoryExportLabels,
        language: AppLanguage,
    ): List<String> = when (kind) {
        HistoryReportKind.INSPECTION -> {
            val report = InspectionReportContent.build(session)
            val label = when (report.layout) {
                InspectionLayout.SAWAI -> labels.inspectionPartCountBySuffix
                InspectionLayout.MOLTEN -> labels.deliveryNumberCount
                InspectionLayout.DENSO -> labels.inspectionPartCount
            }
            listOf("$label: ${HistoryExportTextFormatter.integer(report.rowCount, language)}")
        }

        HistoryReportKind.MATCH_HISTORY -> buildList {
            add("${labels.inspectionPartCount}: ${HistoryExportTextFormatter.integer(session.groupedEntries.size, language)}")
            if (destination == Destination.MOLTEN) {
                val count = session.entries.moltenDeliveryGroups().size
                add("${labels.deliveryNumberCount}: ${HistoryExportTextFormatter.integer(count, language)}")
            }
        }
    }
}
