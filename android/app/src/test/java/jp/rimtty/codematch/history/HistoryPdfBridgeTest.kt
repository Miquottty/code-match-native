package jp.rimtty.codematch.history

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.content.ActivityNotFoundException
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import jp.rimtty.codematch.core.export.HistoryPdfExporter
import jp.rimtty.codematch.core.export.HistoryReportKind
import jp.rimtty.codematch.core.export.ReportMail
import jp.rimtty.codematch.core.export.ReportMailContent
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryPdfBridgeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun createDocumentUsesPdfMimeTypeAndSanitizedFilename() {
        val session = MatchSession(
            startedAt = 0L,
            name = "../morning/report:09:00?.pdf",
        )
        val fileName = HistoryPdfExporter.fileName(session, AppLanguage.ENGLISH, ZoneId.of("UTC"))
        val document = PendingHistoryPdf(
            bytes = "%PDF-test".toByteArray(),
            fileName = fileName,
        )

        assertEquals(HistoryPdfBridge.PDF_MIME_TYPE, document.mimeType)
        assertTrue(fileName.endsWith(".pdf"))
        assertFalse(fileName.contains('/'))
        assertFalse(fileName.contains('\\'))
        assertFalse(fileName.contains(".."))
    }

    @Test
    fun inspectionReportDocumentUsesTheInspectionPrefixAndStaysAPdf() {
        val session = MatchSession(startedAt = 0L, name = "morning")

        val fileName = HistoryPdfExporter.fileName(
            session,
            AppLanguage.ENGLISH,
            ZoneId.of("UTC"),
            HistoryReportKind.INSPECTION,
        )
        val document = PendingHistoryPdf(bytes = "%PDF-test".toByteArray(), fileName = fileName)

        assertTrue(fileName, fileName.startsWith("InspectionReport_") && fileName.endsWith(".pdf"))
        assertFalse(fileName.contains("morning"))
        assertEquals(HistoryPdfBridge.PDF_MIME_TYPE, document.mimeType)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pendingDocumentRejectsPathTraversal() {
        PendingHistoryPdf(
            bytes = byteArrayOf(1),
            fileName = "../outside.pdf",
        )
    }

    @Test
    fun createDocumentContractSetsPdfTypeAndSuggestedName() {
        val document = PendingHistoryPdf(
            bytes = byteArrayOf(1),
            fileName = "MatchHistory_safe.pdf",
        )

        val intent = HistoryPdfBridge.createDocumentContract()
            .createIntent(context, document.fileName)

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(HistoryPdfBridge.PDF_MIME_TYPE, intent.type)
        assertEquals(document.fileName, intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun writeDocumentCopiesExactlyTheGeneratedBytesToSelectedDestination() {
        val bytes = "%PDF-test-bytes".toByteArray()
        val document = PendingHistoryPdf(bytes, "history.pdf")
        val destination = Uri.parse("content://documents/tree/history.pdf")
        val output = ByteArrayOutputStream()
        var openedUri: Uri? = null

        val result = HistoryPdfBridge.writeDocument(
            destination = destination,
            openOutputStream = { uri ->
                openedUri = uri
                output
            },
            document = document,
        )

        assertTrue(result is HistoryPdfResult.Success)
        assertEquals(destination, openedUri)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun writeDocumentReportsWhenTheSelectedDestinationCannotBeOpened() {
        val document = PendingHistoryPdf(byteArrayOf(1, 2, 3), "history.pdf")

        val result = HistoryPdfBridge.writeDocument(
            destination = Uri.parse("content://documents/missing.pdf"),
            openOutputStream = { null },
            document = document,
        )

        assertEquals(
            HistoryPdfFailure.DESTINATION_OPEN_FAILED,
            (result as HistoryPdfResult.Failure).reason,
        )
    }

    @Test
    fun generationFailureIsTypedWithoutExposingExceptionDetails() {
        val result = HistoryPdfBridge.createDocument(
            session = MatchSession(startedAt = 1L),
            language = AppLanguage.ENGLISH,
            zoneId = ZoneId.of("UTC"),
            generate = { _, _, _ -> error("private payload/path must not escape") },
            fileName = { _, _, _ -> "history.pdf" },
        )

        assertEquals(
            HistoryPdfFailure.GENERATION_FAILED,
            (result as HistoryPdfResult.Failure).reason,
        )
        assertFalse(result.toString().contains("private payload"))
    }

    @Test
    fun documentPickerLaunchFailureIsTypedWithoutPropagatingException() {
        val result = HistoryPdfBridge.launchDocumentPicker(
            launch = { error("picker/path details") },
            fileName = "history.pdf",
        )

        assertEquals(
            HistoryPdfFailure.SAVE_PICKER_LAUNCH_FAILED,
            (result as HistoryPdfResult.Failure).reason,
        )
        assertFalse(result.toString().contains("picker/path"))
    }

    @Test
    fun writeDocumentSeparatesOpenAndWriteFailures() {
        val document = PendingHistoryPdf(byteArrayOf(1, 2, 3), "history.pdf")
        val destination = Uri.parse("content://documents/history.pdf")

        val openFailure = HistoryPdfBridge.writeDocument(
            destination = destination,
            openOutputStream = { error("open path") },
            document = document,
        )
        val writeFailure = HistoryPdfBridge.writeDocument(
            destination = destination,
            openOutputStream = { object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray) = error("write path")
            } },
            document = document,
        )

        assertEquals(
            HistoryPdfFailure.DESTINATION_OPEN_FAILED,
            (openFailure as HistoryPdfResult.Failure).reason,
        )
        assertEquals(
            HistoryPdfFailure.DESTINATION_WRITE_FAILED,
            (writeFailure as HistoryPdfResult.Failure).reason,
        )
    }

    @Test
    fun cacheFailureIsTyped() {
        val result = HistoryPdfBridge.writeShareCache(
            context = context,
            session = MatchSession(startedAt = 1L),
            language = AppLanguage.JAPANESE,
            zoneId = ZoneId.of("UTC"),
            writeToCache = { _, _, _, _ -> error("cache path") },
        )

        assertEquals(
            HistoryPdfFailure.CACHE_WRITE_FAILED,
            (result as HistoryPdfResult.Failure).reason,
        )
    }

    @Test
    fun fileProviderAndShareLaunchFailuresAreTyped() {
        val outsideFile = File(context.filesDir, "not-in-provider-roots.pdf").apply {
            writeBytes(byteArrayOf(1))
        }
        try {
            // Use an authority with no registered provider so this negative
            // case cannot poison FileProvider's per-authority path cache for
            // the valid cache-root test below.
            val contextWithoutProvider = object : ContextWrapper(context) {
                override fun getPackageName(): String = "jp.rimtty.codematch.no_provider"
            }
            val providerResult = HistoryPdfBridge.createShareChooser(contextWithoutProvider, outsideFile)
            assertEquals(
                HistoryPdfFailure.FILE_PROVIDER_FAILED,
                (providerResult as HistoryPdfResult.Failure).reason,
            )
        } finally {
            outsideFile.delete()
        }

        val failingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException()
            }
        }
        val launchResult = HistoryPdfBridge.launchShare(
            context = failingContext,
            chooser = Intent(Intent.ACTION_CHOOSER),
        )
        assertEquals(
            HistoryPdfFailure.SHARE_LAUNCH_FAILED,
            (launchResult as HistoryPdfResult.Failure).reason,
        )
    }

    @Test
    fun pickerCancellationIsNotAnErrorAndSelectionRequiresPendingDocument() {
        val pending = PendingHistoryPdf(byteArrayOf(1), "history.pdf")
        assertTrue(
            HistoryPdfBridge.resolveDocumentPickerResult(destination = null, pending = pending)
                is HistoryPdfPickerResult.Cancelled,
        )
        assertTrue(
            HistoryPdfBridge.resolveDocumentPickerResult(
                destination = Uri.parse("content://documents/history.pdf"),
                pending = pending,
            ) is HistoryPdfPickerResult.Selected,
        )
        assertTrue(
            HistoryPdfBridge.resolveDocumentPickerResult(
                destination = Uri.parse("content://documents/history.pdf"),
                pending = null,
            ) is HistoryPdfPickerResult.MissingPendingDocument,
        )
    }

    @Test
    fun mailIntentCarriesRecipientSubjectBodyAttachmentAndMailtoSelector() {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/history_pdf/report.pdf")
        val mail = ReportMail(subject = "[CodeMatch] 検品レポート 朝便", body = "本文")

        val intent = HistoryPdfBridge.createMailIntent(uri, mail)

        assertEquals(Intent.ACTION_SEND, intent.action)
        // No MIME type: a typed intent with a mailto selector resolves to no
        // mail app on a device, which sent every hand-off to the share chooser.
        assertNull(intent.type)
        assertEquals(listOf(ReportMailContent.RECIPIENT), intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList())
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
    fun mailHandOffOpensTheMailIntentWhenAMailAppExistsAndTheChooserOtherwise() {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/history_pdf/report.pdf")
        val file = File(context.cacheDir, "report.pdf")
        val mail = ReportMail(subject = "s", body = "b")

        val withMailApp = HistoryPdfBridge.createMailOrShareChooser(file, mail, { uri }, { true })
        val withoutMailApp = HistoryPdfBridge.createMailOrShareChooser(file, mail, { uri }, { false })
        val providerFailure = HistoryPdfBridge.createMailOrShareChooser(file, mail, { error("no root") }, { true })

        assertTrue("result=$withMailApp", withMailApp is HistoryPdfResult.Success)
        val mailIntent = (withMailApp as HistoryPdfResult.Success).value
        assertEquals(Intent.ACTION_SEND, mailIntent.action)
        assertEquals(mail.subject, mailIntent.getStringExtra(Intent.EXTRA_SUBJECT))

        assertTrue("result=$withoutMailApp", withoutMailApp is HistoryPdfResult.Success)
        assertEquals(Intent.ACTION_CHOOSER, (withoutMailApp as HistoryPdfResult.Success).value.action)

        assertTrue(providerFailure is HistoryPdfResult.Failure)
        assertEquals(HistoryPdfFailure.FILE_PROVIDER_FAILED, (providerFailure as HistoryPdfResult.Failure).reason)
    }

    @Test
    fun shareChooserUsesFileProviderUriPdfTypeClipDataAndReadGrant() {
        val directory = File(context.cacheDir, HistoryPdfExporter.CACHE_DIRECTORY)
        assertTrue(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "share-contract.pdf").apply {
            writeBytes("%PDF-share".toByteArray())
        }

        try {
            val chooserResult = HistoryPdfBridge.createShareChooser(context, file)
            assertTrue("result=$chooserResult", chooserResult is HistoryPdfResult.Success)
            val chooser = (chooserResult as HistoryPdfResult.Success).value
            assertNotNull(chooser)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)

            @Suppress("DEPRECATION")
            val sendIntent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(sendIntent)
            val actualSendIntent = requireNotNull(sendIntent)
            assertEquals(Intent.ACTION_SEND, actualSendIntent.action)
            assertEquals(HistoryPdfBridge.PDF_MIME_TYPE, actualSendIntent.type)

            @Suppress("DEPRECATION")
            val sharedUri = actualSendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertNotNull(sharedUri)
            assertTrue(sharedUri.toString().startsWith("content://${context.packageName}.fileprovider/"))
            assertTrue(sharedUri.toString().contains("/history_pdf/share-contract.pdf"))
            assertTrue(
                actualSendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            )
            assertNotNull(actualSendIntent.clipData)
            assertEquals(sharedUri, actualSendIntent.clipData?.getItemAt(0)?.uri)
        } finally {
            file.delete()
        }
    }
}
