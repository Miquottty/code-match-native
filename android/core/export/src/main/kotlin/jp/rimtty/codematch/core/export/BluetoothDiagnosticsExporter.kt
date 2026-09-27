package jp.rimtty.codematch.core.export

import android.content.Context
import java.io.File

/**
 * Writes the diagnostics mail attachment (#143) into the scoped export cache
 * that the FileProvider already exposes (`cache/codematch-export/`, shared
 * with the history JSON and scan-log exports). Earlier diagnostics
 * attachments are removed first, so at most one copy stays in the cache.
 * The text is the existing payload-free diagnostics export.
 */
object BluetoothDiagnosticsExporter {
    const val CACHE_DIRECTORY: String = HistoryJsonExporter.CACHE_DIRECTORY

    fun writeToCache(context: Context, text: String, fileName: String): File =
        writeToDirectory(File(context.cacheDir, CACHE_DIRECTORY), text, fileName)

    /** JVM-testable core of [writeToCache]. */
    internal fun writeToDirectory(directory: File, text: String, fileName: String): File {
        require(
            fileName.startsWith(DiagnosticsMailContent.FILE_NAME_PREFIX) &&
                fileName.endsWith(DiagnosticsMailContent.FILE_NAME_SUFFIX),
        ) { "Diagnostics attachment must use the diagnostics file name" }
        check(directory.isDirectory || directory.mkdirs()) {
            "Diagnostics export cache directory could not be created"
        }
        val output = File(directory, fileName)
        check(output.canonicalFile.parentFile == directory.canonicalFile) {
            "Diagnostics filename escaped its private cache directory"
        }
        directory.listFiles()?.forEach { stale ->
            if (stale.isFile && stale.name != fileName &&
                stale.name.startsWith(DiagnosticsMailContent.FILE_NAME_PREFIX) &&
                stale.name.endsWith(DiagnosticsMailContent.FILE_NAME_SUFFIX)
            ) {
                stale.delete()
            }
        }
        output.outputStream().use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        }
        return output
    }
}
