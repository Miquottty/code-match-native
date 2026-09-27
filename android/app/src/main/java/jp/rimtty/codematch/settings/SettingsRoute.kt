package jp.rimtty.codematch.settings

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import jp.rimtty.codematch.core.export.BluetoothDiagnosticsExporter
import jp.rimtty.codematch.core.export.DiagnosticsMailContent
import jp.rimtty.codematch.core.export.ScanLogJsonExporter
import jp.rimtty.codematch.history.HistoryJsonBridge
import jp.rimtty.codematch.history.HistoryJsonResult
import jp.rimtty.codematch.feature.settings.DiagnosticLogFormatter
import jp.rimtty.codematch.feature.settings.R
import jp.rimtty.codematch.feature.settings.SettingsScreen
import jp.rimtty.codematch.feature.settings.SettingsUiAction
import jp.rimtty.codematch.feature.settings.SettingsUiState
import jp.rimtty.codematch.core.model.ScanLogEvent
import jp.rimtty.codematch.navigation.CodeMatchBackHandler
import jp.rimtty.codematch.scanner.api.ScannerIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    /** Host-owned, testable hook for opening platform Bluetooth settings. */
    onOpenBluetoothSettings: (ScannerIssue) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshScannerState() }
    // The scanner guide is an inline settings destination. Predictive/system
    // back closes it first; once closed, the Activity owns root back behavior.
    CodeMatchBackHandler(
        enabled = state.setupGuideVisible,
        onBack = { viewModel.onAction(SettingsUiAction.CloseSetupGuide) },
    )

    // Diagnostic log export is host-owned: the feature module never starts an
    // external Activity or touches ContentResolver. The text contains only the
    // sanitized status events plus app/device identification; scan values
    // cannot reach it because the scanner API has no payload-logging entry.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingLog by remember { mutableStateOf<String?>(null) }
    val savedMessage = stringResource(R.string.settings_diagnostics_saved)
    val saveFailedMessage = stringResource(R.string.settings_diagnostics_save_failed)
    val shareSubject = stringResource(R.string.settings_diagnostics_subject)
    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        val text = pendingLog
        pendingLog = null
        if (destination == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(destination, "wt")?.use { stream ->
                        stream.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("no output stream")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (written) savedMessage else saveFailedMessage,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // The scan log is a separate document with its own toasts, so it gets its
    // own SAF launcher rather than sharing the diagnostics one.
    var pendingScanLog by remember { mutableStateOf<String?>(null) }
    val scanLogSavedMessage = stringResource(R.string.settings_scan_log_saved)
    val scanLogSaveFailedMessage = stringResource(R.string.settings_scan_log_save_failed)
    val scanLogShareFailedMessage = stringResource(R.string.settings_scan_log_share_failed)
    val createScanLogDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        val text = pendingScanLog
        pendingScanLog = null
        if (destination == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(destination, "wt")?.use { stream ->
                        stream.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("no output stream")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (written) scanLogSavedMessage else scanLogSaveFailedMessage,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    SettingsScreen(
        state = state,
        onAction = { action ->
            when (action) {
                SettingsUiAction.ShareScanLog -> scope.launch {
                    val exportedAt = Instant.now()
                    val file = withContext(Dispatchers.IO) {
                        runCatching {
                            ScanLogJsonExporter.writeToCache(
                                context = context,
                                text = scanLogText(context, viewModel.exportScanLog(), exportedAt),
                                exportedAt = exportedAt,
                            )
                        }.getOrNull()
                    }
                    val chooser = file?.let {
                        HistoryJsonBridge.createShareChooser(
                            context = context,
                            file = it,
                            mimeType = ScanLogJsonExporter.MIME_TYPE,
                        )
                    }
                    val launched = when (chooser) {
                        is HistoryJsonResult.Success ->
                            runCatching { context.startActivity(chooser.value) }.isSuccess
                        else -> false
                    }
                    if (!launched) {
                        Toast.makeText(
                            context,
                            scanLogShareFailedMessage,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                SettingsUiAction.SaveScanLog -> scope.launch {
                    val exportedAt = Instant.now()
                    pendingScanLog = withContext(Dispatchers.IO) {
                        scanLogText(context, viewModel.exportScanLog(), exportedAt)
                    }
                    runCatching {
                        createScanLogDocument.launch(
                            ScanLogJsonExporter.fileName(exportedAt),
                        )
                    }.onFailure {
                        pendingScanLog = null
                        Toast.makeText(
                            context,
                            scanLogSaveFailedMessage,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                SettingsUiAction.ShareDiagnostics -> scope.launch {
                    // #143: open a mail pre-filled for the administrator; the
                    // operator taps Send. Falls back to the plain text share.
                    val generatedAt = Instant.now()
                    val text = diagnosticLogText(context, state)
                    val mail = DiagnosticsMailContent.build(
                        summary = diagnosticsMailSummary(context, state),
                        generatedAt = generatedAt,
                    )
                    val file = withContext(Dispatchers.IO) {
                        runCatching {
                            BluetoothDiagnosticsExporter.writeToCache(context, text, mail.fileName)
                        }.getOrNull()
                    }
                    val intent = DiagnosticsMailBridge.createIntent(
                        context = context,
                        file = file,
                        mail = mail,
                        fallbackSubject = shareSubject,
                        diagnosticsText = text,
                    )
                    runCatching { context.startActivity(intent) }
                }
                SettingsUiAction.SaveDiagnostics -> {
                    pendingLog = diagnosticLogText(context, state)
                    runCatching {
                        createDocument.launch(DiagnosticLogFormatter.fileName(Instant.now()))
                    }.onFailure {
                        pendingLog = null
                        Toast.makeText(context, saveFailedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
        onOpenBluetoothSettings = { onOpenBluetoothSettings(state.resolvedScannerIssue) },
    )
}

/** Serialize the scan log with the app version the module cannot read itself. */
private fun scanLogText(
    context: Context,
    events: List<ScanLogEvent>,
    exportedAt: Instant,
): String = ScanLogJsonExporter.buildJsonLines(
    events = events,
    header = ScanLogJsonExporter.ExportHeader(
        appVersion = HistoryJsonBridge.appVersion(context),
        exportedAt = exportedAt,
    ),
)

private fun appVersion(context: Context): Pair<String, Long> {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    return (packageInfo?.versionName ?: "?") to (packageInfo?.longVersionCode ?: 0L)
}

private fun systemLabel(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

/** The mail summary repeats the diagnostics header's labels, nothing more. */
private fun diagnosticsMailSummary(
    context: Context,
    state: SettingsUiState,
): DiagnosticsMailContent.Summary {
    val (versionName, versionCode) = appVersion(context)
    return DiagnosticsMailContent.Summary(
        versionName = versionName,
        versionCode = versionCode,
        deviceModel = Build.MODEL,
        system = systemLabel(),
        connection = DiagnosticLogFormatter.connectionLabel(state.connectionState),
        configuration = DiagnosticLogFormatter.configurationLabel(state.configurationState),
        eventCount = state.diagnosticEvents.size,
    )
}

private fun diagnosticLogText(context: Context, state: SettingsUiState): String {
    val (versionName, versionCode) = appVersion(context)
    return DiagnosticLogFormatter.format(
        events = state.diagnosticEvents,
        header = DiagnosticLogFormatter.Header(
            appVersion = "$versionName ($versionCode)",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            system = systemLabel(),
            connection = DiagnosticLogFormatter.connectionLabel(state.connectionState),
            configuration = DiagnosticLogFormatter.configurationLabel(state.configurationState),
            illumination = state.illuminationState.name,
            tuning = state.tuningState.name,
        ),
    )
}
