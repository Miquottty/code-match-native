package jp.rimtty.codematch.feature.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticCategory
import jp.rimtty.codematch.scanner.api.DiagnosticEvent

/**
 * Renders the retained BLE diagnostic events as plain text for the share
 * sheet or a SAF document. Events carry only sanitized status strings (the
 * scanner API has no way to record a scan payload), so the export is safe to
 * hand to a third party. Mirrors the iOS diagnostics export. The same text
 * is the attachment of the administrator mail (#143).
 */
object DiagnosticLogFormatter {
    data class Header(
        val appVersion: String,
        val device: String,
        val system: String,
        val connection: String,
        val configuration: String,
        val illumination: String,
        val tuning: String,
    )

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val fileNameFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun format(
        events: List<DiagnosticEvent>,
        header: Header,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("CodeMatch Bluetooth diagnostics (Android)")
        appendLine("app: ${header.appVersion}")
        appendLine("device: ${header.device}")
        appendLine("system: ${header.system}")
        appendLine("connection: ${header.connection}")
        appendLine("configuration: ${header.configuration}")
        appendLine("illumination: ${header.illumination}")
        appendLine("tuning: ${header.tuning}")
        appendLine("events: ${events.size}")
        appendLine()
        events
            .sortedWith(compareBy<DiagnosticEvent> { it.timestampMillis }.thenBy { it.sequence })
            .forEach { event ->
                val timestamp = timestampFormatter.format(
                    Instant.ofEpochMilli(event.timestampMillis).atZone(zoneId),
                )
                appendLine("$timestamp #${event.sequence} ${categoryLabel(event.category)}: ${event.message}")
            }
    }

    /**
     * Stable labels for the header. Release builds are minified, so class
     * names cannot be used; adapter reason strings are deliberately omitted.
     */
    fun connectionLabel(state: ConnectionState): String = when (state) {
        ConnectionState.Idle -> "Idle"
        ConnectionState.Searching -> "Searching"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Unavailable -> "Unavailable"
        is ConnectionState.Failed -> "Failed"
    }

    fun configurationLabel(state: ConfigurationState): String = when (state) {
        ConfigurationState.Unavailable -> "Unavailable"
        ConfigurationState.Configuring -> "Configuring"
        ConfigurationState.Ready -> "Ready"
        is ConfigurationState.Failed -> "Failed"
    }

    fun fileName(now: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "codematch-ble-diagnostics-${fileNameFormatter.format(now.atZone(zoneId))}.txt"

    private fun categoryLabel(category: DiagnosticCategory): String = when (category) {
        DiagnosticCategory.CONNECTION -> "connection"
        DiagnosticCategory.CONFIGURATION -> "configuration"
        DiagnosticCategory.ERROR -> "error"
    }
}
