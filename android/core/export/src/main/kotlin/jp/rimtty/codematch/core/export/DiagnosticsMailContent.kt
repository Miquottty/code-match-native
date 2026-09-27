package jp.rimtty.codematch.core.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Subject, body and attachment name of the diagnostics mail (#143). */
data class DiagnosticsMail(
    val subject: String,
    val body: String,
    val fileName: String,
)

/**
 * Builds the pre-filled e-mail that hands the Bluetooth diagnostics to the
 * administrator (#143). The mail app opens with everything filled in and the
 * operator only taps Send; the app itself never sends anything and has no
 * network permission.
 *
 * Only identification and status labels go into the subject and body: the
 * attached diagnostics are payload-free, no scan value, scan log or scanner
 * MAC address is added here. The recipient is deliberately separate from
 * [ReportMailContent.RECIPIENT], which must not change.
 */
object DiagnosticsMailContent {
    /** Where diagnostics are sent; not the report recipient. */
    const val RECIPIENT: String = "contact-codematch@googlegroups.com"

    const val MIME_TYPE: String = "text/plain"

    val recipients: Array<String>
        get() = arrayOf(RECIPIENT)

    /** Values the module cannot read itself; the host supplies them. */
    data class Summary(
        val versionName: String,
        val versionCode: Long,
        /** `Build.MODEL`, e.g. `Pixel 7`. */
        val deviceModel: String,
        /** e.g. `Android 16 (API 36)`. */
        val system: String,
        /** The connection label the diagnostics header prints. */
        val connection: String,
        /** The configuration label the diagnostics header prints. */
        val configuration: String,
        val eventCount: Int,
    )

    private val subjectTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    private val fileNameTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** `codematch-bluetooth-diagnostics-20260927-2230.txt` */
    fun fileName(generatedAt: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "$FILE_NAME_PREFIX${fileNameTime.format(generatedAt.atZone(zoneId))}$FILE_NAME_SUFFIX"

    fun build(
        summary: Summary,
        generatedAt: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DiagnosticsMail {
        val time = subjectTime.format(generatedAt.atZone(zoneId))
        val appVersion = "${summary.versionName.oneLine()} (${summary.versionCode})"
        val model = summary.deviceModel.oneLine()
        val fileName = fileName(generatedAt, zoneId)
        // `CodeMatch 診断ログ 0.1.2 (3) Pixel 7 2026/09/27 22:30`: short and
        // sortable per device in the administrator's inbox.
        val subject = listOf("CodeMatch 診断ログ", appVersion, model, time)
            .filter(String::isNotEmpty)
            .joinToString(" ")
        val body = listOf(
            "CodeMatch の Bluetooth スキャナ診断ログをお送りします。",
            "診断ログには読み取った値は含まれていません。",
            "",
            "アプリ: $appVersion",
            "端末: $model / ${summary.system.oneLine()}",
            "接続状態: ${summary.connection.oneLine()}",
            "読み取り設定: ${summary.configuration.oneLine()}",
            "記録件数: ${summary.eventCount} 件",
            "作成日時: $time",
            "",
            "添付ファイル:",
            fileName,
        ).joinToString("\n")
        return DiagnosticsMail(subject = subject, body = body, fileName = fileName)
    }

    internal const val FILE_NAME_PREFIX = "codematch-bluetooth-diagnostics-"
    internal const val FILE_NAME_SUFFIX = ".txt"

    /** A header value never breaks the subject or a body line. */
    private fun String.oneLine(): String =
        replace(Regex("[\\r\\n\\t]+"), " ").trim()
}
