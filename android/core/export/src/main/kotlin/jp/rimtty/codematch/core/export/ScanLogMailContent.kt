package jp.rimtty.codematch.core.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds the pre-filled e-mail that hands the scan log to the administrator,
 * the same way [DiagnosticsMailContent] hands over the Bluetooth diagnostics:
 * the mail app opens with recipient, subject, body and the JSON Lines export
 * attached, and the operator only taps Send. The app itself never sends
 * anything and has no network permission.
 *
 * Unlike the diagnostics, the attachment carries the raw QR / Code 128
 * values, and the body says so. The subject and body themselves hold only
 * identification and the event count, never a scanned value.
 */
object ScanLogMailContent {
    /** The administrator who reads the diagnostics also reads the scan log. */
    const val RECIPIENT: String = DiagnosticsMailContent.RECIPIENT

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
        val eventCount: Int,
    )

    private val subjectTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

    /** The attachment is the export [ScanLogJsonExporter.writeToCache] writes. */
    fun build(
        summary: Summary,
        exportedAt: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DiagnosticsMail {
        val time = subjectTime.format(exportedAt.atZone(zoneId))
        val appVersion = "${summary.versionName.oneLine()} (${summary.versionCode})"
        val model = summary.deviceModel.oneLine()
        val fileName = ScanLogJsonExporter.fileName(exportedAt, zoneId)
        // `CodeMatch 照合ログ 0.1.6 (7) SC-53F 2026/11/20 16:44`
        val subject = listOf("CodeMatch 照合ログ", appVersion, model, time)
            .filter(String::isNotEmpty)
            .joinToString(" ")
        val body = listOf(
            "CodeMatch の照合ログをお送りします。",
            "照合ログには読み取った QR コード・バーコードの値が含まれています。",
            "",
            "アプリ: $appVersion",
            "端末: $model / ${summary.system.oneLine()}",
            "記録件数: ${summary.eventCount} 件",
            "作成日時: $time",
            "",
            "添付ファイル:",
            fileName,
        ).joinToString("\n")
        return DiagnosticsMail(subject = subject, body = body, fileName = fileName)
    }

    /** A header value never breaks the subject or a body line. */
    private fun String.oneLine(): String =
        replace(Regex("[\\r\\n\\t]+"), " ").trim()
}
