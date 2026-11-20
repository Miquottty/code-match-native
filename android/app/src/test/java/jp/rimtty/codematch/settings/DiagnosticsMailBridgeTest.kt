package jp.rimtty.codematch.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import jp.rimtty.codematch.core.export.BluetoothDiagnosticsExporter
import jp.rimtty.codematch.core.export.DiagnosticsMailContent
import jp.rimtty.codematch.core.export.ScanLogMailContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsMailBridgeTest {
    private lateinit var context: Context
    private val mail = DiagnosticsMailContent.build(
        summary = DiagnosticsMailContent.Summary(
            versionName = "0.1.2",
            versionCode = 3,
            deviceModel = "Pixel 7",
            system = "Android 16 (API 36)",
            connection = "Connected",
            configuration = "Ready",
            eventCount = 2,
        ),
        generatedAt = Instant.parse("2026-09-27T13:30:00Z"),
        zoneId = ZoneId.of("Asia/Tokyo"),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun mailIntentCarriesRecipientSubjectBodyAttachmentAndMailtoSelector() {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/history_export/${mail.fileName}")
        val intent = DiagnosticsMailBridge.createMailIntent(uri, mail)

        assertEquals(Intent.ACTION_SEND, intent.action)
        // No MIME type: a typed intent with a mailto selector resolves to no
        // mail app on a device, which sent every hand-off to the share sheet.
        assertNull(intent.type)
        assertEquals(
            listOf("contact-codematch@googlegroups.com"),
            intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList(),
        )
        assertEquals(mail.subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(mail.body, intent.getStringExtra(Intent.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        val selector = requireNotNull(intent.selector)
        assertEquals(Intent.ACTION_SENDTO, selector.action)
        assertEquals("mailto", selector.data?.scheme)
    }

    @Test
    fun mailAppGetsTheMailIntentOtherwiseThePreviousTextShareIsUsed() {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/history_export/${mail.fileName}")
        val file = File(context.cacheDir, mail.fileName)
        val text = "CodeMatch Bluetooth diagnostics (Android)\nevents: 0\n"

        val withMailApp = DiagnosticsMailBridge.createIntent(file, mail, "subject", text, { uri }, { true })
        assertEquals(Intent.ACTION_SEND, withMailApp.action)
        assertEquals(mail.subject, withMailApp.getStringExtra(Intent.EXTRA_SUBJECT))

        listOf(
            DiagnosticsMailBridge.createIntent(file, mail, "subject", text, { uri }, { false }),
            DiagnosticsMailBridge.createIntent(null, mail, "subject", text, { uri }, { true }),
            DiagnosticsMailBridge.createIntent(file, mail, "subject", text, { error("no root") }, { true }),
        ).forEach { fallback ->
            assertEquals(Intent.ACTION_CHOOSER, fallback.action)
            @Suppress("DEPRECATION")
            val send = requireNotNull(fallback.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals("subject", send.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(text, send.getStringExtra(Intent.EXTRA_TEXT))
            assertNull(send.getStringArrayExtra(Intent.EXTRA_EMAIL))
        }
    }

    @Test
    fun attachmentIsWrittenIntoTheExportCacheTheFileProviderExposes() {
        val file = BluetoothDiagnosticsExporter.writeToCache(context, "diagnostics", mail.fileName)
        try {
            // `cache/codematch-export/` is the FileProvider's `history_export`
            // root (pinned by the release gate), so no provider change is needed.
            assertEquals(
                File(context.cacheDir, "codematch-export").canonicalFile,
                file.canonicalFile.parentFile,
            )
            assertEquals(mail.fileName, file.name)
            assertEquals("diagnostics", file.readText())
            // Robolectric has no mail app: the previous text share is used.
            val intent = DiagnosticsMailBridge.createIntent(
                context = context,
                file = file,
                mail = mail,
                fallbackSubject = "subject",
                diagnosticsText = "diagnostics",
            )
            assertEquals(Intent.ACTION_CHOOSER, intent.action)
        } finally {
            file.delete()
        }
    }

    @Test
    fun scanLogGoesToTheAdministratorAsAnAttachmentAndNeverAsMailText() {
        val scanLogMail = ScanLogMailContent.build(
            summary = ScanLogMailContent.Summary(
                versionName = "0.1.6",
                versionCode = 7,
                deviceModel = "SC-53F",
                system = "Android 16 (API 36)",
                eventCount = 12,
            ),
            exportedAt = Instant.parse("2026-11-20T07:44:00Z"),
            zoneId = ZoneId.of("Asia/Tokyo"),
        )
        val uri = Uri.parse(
            "content://${context.packageName}.fileprovider/history_export/${scanLogMail.fileName}",
        )
        val file = File(context.cacheDir, scanLogMail.fileName)
        val fileShare = Intent(Intent.ACTION_CHOOSER)

        val intent = requireNotNull(
            DiagnosticsMailBridge.createScanLogIntent(file, scanLogMail, { fileShare }, { uri }, { true }),
        )
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertNull(intent.type)
        assertEquals(
            listOf("contact-codematch@googlegroups.com"),
            intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList(),
        )
        assertEquals(scanLogMail.subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(scanLogMail.body, intent.getStringExtra(Intent.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("mailto", intent.selector?.data?.scheme)

        // No mail app, or no attachment URI: the previous file share.
        assertEquals(
            fileShare,
            DiagnosticsMailBridge.createScanLogIntent(file, scanLogMail, { fileShare }, { uri }, { false }),
        )
        assertEquals(
            fileShare,
            DiagnosticsMailBridge.createScanLogIntent(
                file, scanLogMail, { fileShare }, { error("no root") }, { true },
            ),
        )
        assertNull(
            DiagnosticsMailBridge.createScanLogIntent(file, scanLogMail, { null }, { uri }, { false }),
        )
    }
}
