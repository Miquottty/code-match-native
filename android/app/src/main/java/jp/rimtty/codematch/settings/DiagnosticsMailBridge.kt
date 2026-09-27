package jp.rimtty.codematch.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import jp.rimtty.codematch.core.export.DiagnosticsMail
import jp.rimtty.codematch.core.export.DiagnosticsMailContent

/**
 * Hands the Bluetooth diagnostics to the administrator by e-mail (#143).
 *
 * The mail app opens with the recipient, subject, summary body and the full
 * diagnostics text attached; the operator only taps Send. Nothing is sent by
 * the app itself (it has no INTERNET permission). When no mail app can take
 * the intent, or the attachment cannot be prepared, the previous text share
 * is used instead so the log can still leave the device.
 */
internal object DiagnosticsMailBridge {
    fun createIntent(
        context: Context,
        file: File?,
        mail: DiagnosticsMail,
        fallbackSubject: String,
        diagnosticsText: String,
    ): Intent = createIntent(
        file = file,
        mail = mail,
        fallbackSubject = fallbackSubject,
        diagnosticsText = diagnosticsText,
        uriForFile = { current ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", current)
        },
        hasMailApp = { intent ->
            // A resolver failure only means "no mail app to hand off to".
            runCatching { context.packageManager.queryIntentActivities(intent, 0).isNotEmpty() }
                .getOrDefault(false)
        },
    )

    /** Injectable URI and resolver seams so both branches are JVM-testable. */
    internal fun createIntent(
        file: File?,
        mail: DiagnosticsMail,
        fallbackSubject: String,
        diagnosticsText: String,
        uriForFile: (File) -> Uri,
        hasMailApp: (Intent) -> Boolean,
    ): Intent {
        val uri = file?.let { runCatching { uriForFile(it) }.getOrNull() }
        val mailIntent = uri?.let { createMailIntent(it, mail) }
        return if (mailIntent != null && hasMailApp(mailIntent)) {
            mailIntent
        } else {
            createShareChooser(fallbackSubject, diagnosticsText)
        }
    }

    /**
     * `ACTION_SEND` with the mail fields and the attachment, restricted to
     * mail apps through a `mailto:` selector, exactly like the report mail
     * (`HistoryPdfBridge.createMailIntent`).
     */
    internal fun createMailIntent(uri: Uri, mail: DiagnosticsMail): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = DiagnosticsMailContent.MIME_TYPE
            putExtra(Intent.EXTRA_EMAIL, DiagnosticsMailContent.recipients)
            putExtra(Intent.EXTRA_SUBJECT, mail.subject)
            putExtra(Intent.EXTRA_TEXT, mail.body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uri)
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }

    /** The previous behaviour: share the diagnostics text through any app. */
    internal fun createShareChooser(subject: String, diagnosticsText: String): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = DiagnosticsMailContent.MIME_TYPE
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, diagnosticsText)
        }
        return Intent.createChooser(intent, subject)
    }
}
