package jp.rimtty.codematch.feature.scan

import android.content.res.Configuration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerIssue
import java.util.Locale

/**
 * Stateless scan screen. The host owns [ScanUiState] and translates
 * [ScanUiAction] into reducer/coordinator calls.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ScanScreen(
    state: ScanUiState,
    onAction: (ScanUiAction) -> Unit,
    modifier: Modifier = Modifier,
    /** Explicit opt-in for debug-only demo controls. Defaults to production-safe hidden. */
    showDebugDemoTools: Boolean = false,
    /** Optional CameraX-free preview supplied by the application host. */
    cameraPreview: CameraPreviewContent? = null,
    /** Receives normalized taps for CameraX focus/metering. */
    onCameraFocus: (CameraFocusPoint) -> Unit = {},
    /** Opens the app's camera permission page for a permanently denied permission. */
    onOpenCameraSettings: () -> Unit = {},
    /** Host-owned, testable hook for opening platform Bluetooth settings. */
    onOpenBluetoothSettings: () -> Unit = {},
    /**
     * Optional language owned by the host's immutable settings state.
     *
     * The application normally applies its per-app locale at the root. A
     * feature-level override keeps this stateless surface deterministic for
     * hosts that render the language as state before Android recreates the
     * Activity (and for direct Compose tests).
     */
    language: AppLanguage? = null,
) {
    val content: @Composable () -> Unit = {
        ScanScreenContent(
            state = state,
            onAction = onAction,
            modifier = modifier,
            showDebugDemoTools = showDebugDemoTools,
            cameraPreview = cameraPreview,
            onCameraFocus = onCameraFocus,
            onOpenCameraSettings = onOpenCameraSettings,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
        )
    }
    if (language == null) {
        content()
    } else {
        ScanLocalized(language, content)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ScanScreenContent(
    state: ScanUiState,
    onAction: (ScanUiAction) -> Unit,
    modifier: Modifier,
    showDebugDemoTools: Boolean,
    cameraPreview: CameraPreviewContent?,
    onCameraFocus: (CameraFocusPoint) -> Unit,
    onOpenCameraSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val screenDescription = stringResource(R.string.scan_accessibility_description)
    val countDescription = stringResource(R.string.scan_count_format, state.matchedCount)
    val defaultSessionName = stringResource(R.string.scan_session_default_name)
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = screenDescription
                testTagsAsResourceId = true
            },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.sessionActive) {
                            state.sessionName?.takeIf { it.isNotBlank() }
                                ?: defaultSessionName
                        } else {
                            defaultSessionName
                        },
                        modifier = Modifier.testTag("scan_screen_title"),
                    )
                },
                actions = {
                    if (state.sessionActive) {
                        Text(
                            text = "${state.matchedCount}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .semantics {
                                    contentDescription = countDescription
                                }
                                .testTag("scan_session_match_count"),
                        )
                        TextButton(
                            onClick = { onAction(ScanUiAction.EndSession) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("scan_end_session"),
                        ) {
                            Text(stringResource(R.string.scan_end_button))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.sessionActive) {
            ScanSessionContent(
                state = state,
                onAction = onAction,
                showDebugDemoTools = showDebugDemoTools,
                contentPadding = innerPadding,
                cameraPreview = cameraPreview,
                onCameraFocus = onCameraFocus,
                onOpenCameraSettings = onOpenCameraSettings,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
            )
        } else {
            ScanStartContent(
                state = state,
                onAction = onAction,
                contentPadding = innerPadding,
            )
        }
    }
}

/** Alias used by navigation hosts that call feature roots `Route`. */
@Composable
fun ScanRoute(
    state: ScanUiState,
    onAction: (ScanUiAction) -> Unit,
    modifier: Modifier = Modifier,
    showDebugDemoTools: Boolean = false,
    cameraPreview: CameraPreviewContent? = null,
    onCameraFocus: (CameraFocusPoint) -> Unit = {},
    onOpenCameraSettings: () -> Unit = {},
    onOpenBluetoothSettings: () -> Unit = {},
    language: AppLanguage? = null,
) = ScanScreen(
    state = state,
    onAction = onAction,
    modifier = modifier,
    showDebugDemoTools = showDebugDemoTools,
    cameraPreview = cameraPreview,
    onCameraFocus = onCameraFocus,
    onOpenCameraSettings = onOpenCameraSettings,
    onOpenBluetoothSettings = onOpenBluetoothSettings,
    language = language,
)

/** Alias kept small so app integration can choose either naming convention. */
@Composable
fun ScanDestination(
    state: ScanUiState,
    onAction: (ScanUiAction) -> Unit,
    modifier: Modifier = Modifier,
    showDebugDemoTools: Boolean = false,
    cameraPreview: CameraPreviewContent? = null,
    onCameraFocus: (CameraFocusPoint) -> Unit = {},
    onOpenCameraSettings: () -> Unit = {},
    onOpenBluetoothSettings: () -> Unit = {},
    language: AppLanguage? = null,
) = ScanScreen(
    state = state,
    onAction = onAction,
    modifier = modifier,
    showDebugDemoTools = showDebugDemoTools,
    cameraPreview = cameraPreview,
    onCameraFocus = onCameraFocus,
    onOpenCameraSettings = onOpenCameraSettings,
    onOpenBluetoothSettings = onOpenBluetoothSettings,
    language = language,
)

@Composable
private fun ScanStartContent(
    state: ScanUiState,
    onAction: (ScanUiAction) -> Unit,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.scan_eyebrow),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.8.sp,
        )
        Text(
            text = stringResource(R.string.scan_start_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.scan_start_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.sessionNameDraft,
            onValueChange = { onAction(ScanUiAction.SessionNameChanged(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scan_session_name"),
            label = { Text(stringResource(R.string.scan_session_name_label)) },
            placeholder = { Text(stringResource(R.string.scan_session_name_placeholder)) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.scan_name_support)) },
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scan_flow_title), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.scan_flow_summary))
                Text(
                    stringResource(R.string.scan_flow_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = { onAction(ScanUiAction.StartSession) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("scan_start_session"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scan_start_button), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ScanSessionContent(
    state: ScanUiState,
    onAction: (ScanUiAction) -> Unit,
    showDebugDemoTools: Boolean,
    contentPadding: PaddingValues,
    cameraPreview: CameraPreviewContent?,
    onCameraFocus: (CameraFocusPoint) -> Unit,
    onOpenCameraSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.scan_count_format, state.matchedCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("scan_session_count"),
            )
            // The lock exists only after the first accepted QR, so a session
            // that has not identified a slip yet shows no destination at all.
            state.destination?.let { destination ->
                Text(
                    text = stringResource(
                        R.string.scan_session_destination_format,
                        destinationName(destination),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("scan_session_destination"),
                )
            }
        }
        ScanStepper(state.phase)

        // Restoring scanner settings temporarily clears readiness while the
        // physical link remains connected. Keep the picker in place.
        if ((state.bluetoothConnected || state.bluetoothReady) && state.expectedFormat != null) {
            InputSourcePicker(state.inputSource, state.bluetoothReady, onAction)
        }

        ScanMessage(state)

        BluetoothConfigurationStatus(state)

        if (state.bluetoothFallbackActive && state.bluetoothConnected &&
            (state.bluetoothConfigurationState == ConfigurationState.Configuring ||
                state.bluetoothConfigurationState == ConfigurationState.Unavailable)
        ) {
            Text(
                text = stringResource(R.string.scan_bluetooth_reconnected_checking),
                modifier = Modifier.testTag("scan_bluetooth_reconnected_checking"),
                style = MaterialTheme.typography.titleSmall,
            )
        } else if (state.bluetoothFallbackActive) {
            BluetoothFallbackCard(
                issue = state.bluetoothIssue,
                reconnecting = state.bluetoothReconnecting,
                onReconnect = { onAction(ScanUiAction.ReconnectBluetooth) },
                onOpenBluetoothSettings = onOpenBluetoothSettings,
            )
        }

        when (state.scan) {
            ScanState.Idle -> Unit
            is ScanState.WaitingQr,
            is ScanState.WaitingCode128,
            -> ScanWaitingCard(
                state = state,
                onAction = onAction,
                cameraPreview = cameraPreview,
                onCameraFocus = onCameraFocus,
                onOpenCameraSettings = onOpenCameraSettings,
            )
            is ScanState.Result -> ScanResultCard(state, onAction)
        }

        if (state.phase == ScanPhase.WAITING_CODE_128) {
            OutlinedButton(
                onClick = { onAction(ScanUiAction.RereadQr) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("scan_reread_qr"),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_reread_qr))
            }
        }

        AutoAdvanceControls(state, onAction)

        if (showDebugDemoTools && state.debugDemoEnabled) {
            DebugDemoTools(onAction)
        }
    }
}

/**
 * Shows only stable scanner configuration guidance. Adapter failure reasons
 * stay in the typed state and are never rendered by this feature surface.
 */
@Composable
private fun BluetoothConfigurationStatus(state: ScanUiState) {
    if (state.inputSource != InputSource.BLUETOOTH) return

    val messageRes = when (state.bluetoothConfigurationState) {
        ConfigurationState.Unavailable -> return
        // Routine configuration is internal; a temporary row here shifts
        // the waiting card whenever the user switches input sources.
        ConfigurationState.Configuring -> return
        // Ready guidance belongs inside the waiting card, after the current
        // QR/Code 128 instruction. Keeping it as a separate row above the
        // card can push the primary instruction below a compact viewport.
        ConfigurationState.Ready -> return
        is ConfigurationState.Failed -> R.string.scan_bluetooth_configuration_failed
    }
    Text(
        text = stringResource(messageRes),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_bluetooth_configuration_status"),
    )
}

@Composable
private fun ScanStepper(phase: ScanPhase) {
    val activeStep = when (phase) {
        ScanPhase.IDLE, ScanPhase.WAITING_QR -> 1
        ScanPhase.WAITING_CODE_128 -> 2
        ScanPhase.RESULT -> 3
    }
    val description = stringResource(R.string.scan_stepper_description, activeStep)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = description
                stateDescription = description
            }
            .testTag("scan_stepper"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StepItem(1, stringResource(R.string.scan_step_qr), activeStep >= 1, activeStep > 1, Modifier.weight(1f))
        StepConnector(activeStep > 1, Modifier.weight(.35f))
        StepItem(2, stringResource(R.string.scan_step_barcode), activeStep >= 2, activeStep > 2, Modifier.weight(1f))
        StepConnector(activeStep > 2, Modifier.weight(.35f))
        StepItem(3, stringResource(R.string.scan_step_match), activeStep >= 3, activeStep >= 3, Modifier.weight(1f))
    }
}

@Composable
private fun StepItem(
    number: Int,
    label: String,
    active: Boolean,
    complete: Boolean,
    modifier: Modifier,
) {
    val status = stringResource(
        if (complete) R.string.scan_step_complete
        else if (active) R.string.scan_step_current
        else R.string.scan_step_pending,
    )
    val stepDescription = stringResource(R.string.scan_step_item, number, label, status)
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .semantics {
                contentDescription = stepDescription
            }
            .testTag("scan_step_$number"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = when {
                complete -> MaterialTheme.colorScheme.primary
                active -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (complete) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        number.toString(),
                        fontWeight = FontWeight.Bold,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StepConnector(active: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier
            .height(2.dp)
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    )
}

@Composable
private fun InputSourcePicker(
    selected: InputSource,
    bluetoothReady: Boolean,
    onAction: (ScanUiAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .testTag("scan_input_source_picker"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.scan_input_source_label), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChoice(
                source = InputSource.CAMERA,
                selected = selected == InputSource.CAMERA,
                modifier = Modifier.weight(1f),
                onClick = { onAction(ScanUiAction.SelectInputSource(InputSource.CAMERA)) },
            )
            SourceChoice(
                source = InputSource.BLUETOOTH,
                selected = selected == InputSource.BLUETOOTH,
                enabled = bluetoothReady,
                modifier = Modifier.weight(1f),
                onClick = { onAction(ScanUiAction.SelectInputSource(InputSource.BLUETOOTH)) },
            )
        }
    }
}

@Composable
private fun SourceChoice(
    source: InputSource,
    selected: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val label = stringResource(
        if (source == InputSource.CAMERA) R.string.scan_input_camera else R.string.scan_input_bluetooth,
    )
    Surface(
        modifier = modifier
            .heightIn(min = 52.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .testTag("scan_input_${source.name.lowercase()}"),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (selected) 2.dp else 0.dp,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Icon(
                if (source == InputSource.CAMERA) Icons.Outlined.CameraAlt else Icons.Outlined.QrCode2,
                contentDescription = null,
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Explains an automatic BLE-to-camera fallback while preserving the logical
 * QR/Code 128 step. Retry and system-settings actions are host-owned so this
 * feature remains Android/Bluetooth independent.
 */
@Composable
private fun BluetoothFallbackCard(
    issue: ScannerIssue,
    reconnecting: Boolean,
    onReconnect: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val descriptionRes = when (issue) {
        ScannerIssue.PERMISSION_DENIED -> R.string.scan_bluetooth_fallback_permission
        ScannerIssue.POWERED_OFF -> R.string.scan_bluetooth_fallback_powered_off
        ScannerIssue.UNAVAILABLE,
        ScannerIssue.UNSUPPORTED,
        -> R.string.scan_bluetooth_fallback_unavailable
        ScannerIssue.RESTORE_FAILED -> R.string.scan_bluetooth_fallback_restore_failed
        ScannerIssue.CONFIGURATION_FAILED -> R.string.scan_bluetooth_fallback_configuration
        ScannerIssue.NONE,
        ScannerIssue.CONNECTION_FAILED,
        ScannerIssue.RESELECT_REQUIRED,
        -> R.string.scan_bluetooth_fallback_connection
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_bluetooth_fallback"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.scan_bluetooth_fallback_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = stringResource(descriptionRes),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.testTag("scan_bluetooth_fallback_message"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onReconnect,
                    enabled = !reconnecting,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("scan_bluetooth_reconnect"),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (reconnecting) R.string.scan_bluetooth_reconnecting else R.string.scan_bluetooth_reconnect))
                }
                if (issue.requiresSystemSettings) {
                    OutlinedButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("scan_bluetooth_open_settings"),
                    ) {
                        Text(stringResource(R.string.scan_bluetooth_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanWaitingCard(
    state: ScanUiState,
    onAction: (ScanUiAction) -> Unit,
    cameraPreview: CameraPreviewContent?,
    onCameraFocus: (CameraFocusPoint) -> Unit,
    onOpenCameraSettings: () -> Unit,
) {
    val expected = state.expectedFormat ?: ScanFormat.QR
    val title = stringResource(
        if (expected == ScanFormat.QR) R.string.scan_wait_qr_title else R.string.scan_wait_code128_title,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_waiting_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (expected == ScanFormat.QR) {
                    Icons.Outlined.QrCode2
                } else {
                    ImageVector.vectorResource(R.drawable.ic_barcode)
                },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (state.inputSource == InputSource.BLUETOOTH) {
                Text(
                    text = stringResource(
                        R.string.scan_bluetooth_from,
                        state.bluetoothDeviceName ?: stringResource(R.string.scan_bluetooth_device_fallback),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.inputSource == InputSource.BLUETOOTH &&
                state.bluetoothConfigurationState === ConfigurationState.Ready
            ) {
                Text(
                    text = stringResource(R.string.scan_bluetooth_restriction_session),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("scan_bluetooth_configuration_status"),
                )
            }
            if (state.inputSource == InputSource.CAMERA) {
                CameraStage(
                    format = expected,
                    running = state.isCameraRunning,
                    previewContent = cameraPreview,
                    onFocus = onCameraFocus,
                )
                when {
                    state.cameraPermissionPermanentlyDenied -> {
                        Text(
                            stringResource(R.string.scan_camera_permanently_denied),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("scan_camera_permission_permanently_denied"),
                        )
                        TextButton(
                            onClick = onOpenCameraSettings,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("scan_camera_open_settings"),
                        ) {
                            Text(stringResource(R.string.scan_camera_open_settings))
                        }
                    }
                    state.cameraPermissionDenied -> {
                        Text(
                            stringResource(R.string.scan_camera_denied),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("scan_camera_permission_denied"),
                        )
                    }
                    state.cameraPermissionState == CameraPermissionState.REQUESTING -> {
                        Text(
                            stringResource(R.string.scan_camera_permission_requesting),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("scan_camera_permission_requesting"),
                        )
                    }
                    state.cameraStartFailed -> {
                        Text(
                            stringResource(R.string.scan_camera_start_failed),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("scan_camera_start_failed"),
                        )
                    }
                    !state.cameraAvailable -> {
                        Text(
                            stringResource(R.string.scan_camera_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("scan_camera_unavailable"),
                        )
                    }
                }
                Button(
                    onClick = {
                        onAction(
                            if (state.isCameraRunning || state.isCameraStarting) {
                                ScanUiAction.StopCamera
                            } else {
                                ScanUiAction.StartCamera
                            },
                        )
                    },
                    enabled = state.cameraAvailable &&
                        (!state.cameraPermissionPermanentlyDenied ||
                            state.isCameraRunning ||
                            state.isCameraStarting),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("scan_camera_button"),
                ) {
                    Icon(
                        if (state.isCameraRunning || state.isCameraStarting) {
                            Icons.Outlined.StopCircle
                        } else {
                            Icons.Outlined.CameraAlt
                        },
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (state.isCameraRunning || state.isCameraStarting) {
                                R.string.scan_camera_stop
                            } else {
                                R.string.scan_camera_start
                            },
                        ),
                    )
                }
                if (state.isCameraStarting) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scan_camera_preparing"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanResultCard(
    state: ScanUiState,
    onAction: (ScanUiAction) -> Unit,
) {
    val result = state.scan as? ScanState.Result ?: return
    val isMatch = result.result == MatchResult.MATCH
    val isDuplicate = result.result == MatchResult.DUPLICATE
    val countdownSeconds = state.countdownSeconds
    // The locked destination decides the printed form: a Denso part number is
    // 6-4, every other destination keeps 4-2-4 / 4-2-3.
    val destination = state.destination
    val qrPart = CodeMatcher.partNumberFromQr(result.qrPayload)
        ?.let { CodeMatcher.formatPartNumber(it, destination) }
        ?: result.qrPayload
    val barcodePart = CodeMatcher.partNumberFromBarcode(result.barcodePayload)
        ?.let { CodeMatcher.formatPartNumber(it, destination) }
        ?: result.barcodePayload
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                // Scan feedback is replaced in place while focus remains on
                // the camera controls. A polite live region lets TalkBack
                // announce the new guidance without interrupting a spoken
                // instruction or moving accessibility focus.
                liveRegion = LiveRegionMode.Polite
            }
            .testTag("scan_result_card"),
        colors = CardDefaults.cardColors(
            containerColor = if (isMatch) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        isMatch -> Icons.Outlined.CheckCircle
                        isDuplicate -> Icons.Outlined.Refresh
                        else -> Icons.Outlined.WarningAmber
                    },
                    contentDescription = null,
                    tint = if (isMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        stringResource(
                            if (isMatch) {
                                R.string.scan_status_complete_match
                            } else if (isDuplicate) {
                                R.string.scan_status_duplicate
                            } else {
                                R.string.scan_status_mismatch
                            },
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            if (isMatch) {
                                R.string.scan_result_match_description
                            } else if (isDuplicate) {
                                R.string.scan_result_duplicate_description
                            } else {
                                R.string.scan_result_mismatch_description
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            HorizontalDivider()
            ResultPartRow(
                stringResource(R.string.scan_result_qr_part),
                qrPart,
                "scan_result_qr_part",
            )
            ResultPartRow(
                stringResource(R.string.scan_result_barcode_part),
                barcodePart,
                "scan_result_barcode_part",
            )
            // A Molten slip repeats for every box of one delivery number, so
            // the operator needs the running box count and quantity here. A
            // Sawai slip is box specific and keeps the plain two-row card.
            state.session.moltenResultSummary?.let { summary ->
                Text(
                    text = stringResource(
                        R.string.scan_result_molten_box_summary,
                        summary.deliveryNumber,
                        summary.boxNumber,
                        summary.cumulativeQuantity,
                    ),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("scan_result_molten_box_summary"),
                )
            }
            if (countdownSeconds != null && isMatch) {
                CountdownCard(countdownSeconds, state.autoAdvanceDelay.seconds)
            }
            Button(
                onClick = { onAction(ScanUiAction.ManualNext) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("scan_manual_next"),
            ) {
                Text(stringResource(R.string.scan_manual_next), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The localized display name of a destination. */
@Composable
private fun destinationName(destination: Destination): String = stringResource(
    when (destination) {
        Destination.SAWAI -> R.string.scan_destination_sawai
        Destination.MOLTEN -> R.string.scan_destination_molten
        Destination.DENSO -> R.string.scan_destination_denso
    },
)

@Composable
private fun ResultPartRow(label: String, value: String, tag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "$label $value" }
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("$tag.label"))
        Text(value, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("$tag.value"))
    }
}

@Composable
private fun CountdownCard(remaining: Int, total: Int) {
    val progress = if (total > 0) remaining.toFloat() / total else 0f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_countdown"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(52.dp),
                )
                Text(remaining.toString(), fontWeight = FontWeight.Black)
            }
            Column {
                Text(
                    stringResource(R.string.scan_countdown_format, remaining),
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.scan_countdown_manual_hint))
            }
        }
    }
}

@Composable
private fun AutoAdvanceControls(
    state: ScanUiState,
    onAction: (ScanUiAction) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_auto_advance_controls"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable {
                        onAction(ScanUiAction.SetAutoAdvanceEnabled(!state.autoAdvanceEnabled))
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.scan_auto_advance_title), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(
                            if (state.autoAdvanceEnabled) {
                                R.string.scan_auto_on_description
                            } else {
                                R.string.scan_auto_off_description
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = state.autoAdvanceEnabled,
                    onCheckedChange = { onAction(ScanUiAction.SetAutoAdvanceEnabled(it)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoDelayOption(
                    seconds = 1,
                    selected = state.autoAdvanceDelay.seconds == 1,
                    enabled = state.autoAdvanceEnabled,
                    onClick = { onAction(ScanUiAction.SetAutoAdvanceDelay(jp.rimtty.codematch.core.model.AutoAdvanceDelay.ONE_SECOND)) },
                    modifier = Modifier.weight(1f),
                )
                AutoDelayOption(
                    seconds = 3,
                    selected = state.autoAdvanceDelay.seconds == 3,
                    enabled = state.autoAdvanceEnabled,
                    onClick = { onAction(ScanUiAction.SetAutoAdvanceDelay(jp.rimtty.codematch.core.model.AutoAdvanceDelay.THREE_SECONDS)) },
                    modifier = Modifier.weight(1f),
                )
                AutoDelayOption(
                    seconds = 5,
                    selected = state.autoAdvanceDelay.seconds == 5,
                    enabled = state.autoAdvanceEnabled,
                    onClick = { onAction(ScanUiAction.SetAutoAdvanceDelay(jp.rimtty.codematch.core.model.AutoAdvanceDelay.FIVE_SECONDS)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AutoDelayOption(
    seconds: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(stringResource(R.string.scan_delay_seconds, seconds)) },
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("scan_auto_delay_$seconds"),
    )
}

@Composable
private fun ScanMessage(state: ScanUiState) {
    val invalid = state.lastInvalidReason
    if (invalid == null && state.message.isNullOrBlank()) return
    // A camera QR that is simply not a business label collapses into one
    // instruction. A wrong destination is deliberately excluded: the operator
    // scanned a valid slip and needs to know which destination is locked.
    val invalidCameraQr = state.inputSource == InputSource.CAMERA &&
        state.phase == ScanPhase.WAITING_QR && invalid in setOf(
            InvalidScanReason.EMPTY_PAYLOAD,
            InvalidScanReason.INCOMPLETE_QR_PAYLOAD,
            InvalidScanReason.OVERLONG_QR_PAYLOAD,
            InvalidScanReason.INVALID_PAYLOAD,
        )
    val invalidCameraCode128 = state.inputSource == InputSource.CAMERA &&
        state.phase == ScanPhase.WAITING_CODE_128 && invalid in setOf(
            InvalidScanReason.EMPTY_PAYLOAD,
            InvalidScanReason.INVALID_PAYLOAD,
        )
    val invalidText = if (invalidCameraQr) {
        stringResource(R.string.scan_invalid_camera_qr)
    } else if (invalidCameraCode128) {
        stringResource(R.string.scan_invalid_camera_code128)
    } else when (invalid) {
        InvalidScanReason.SESSION_NOT_STARTED -> stringResource(R.string.scan_invalid_session)
        InvalidScanReason.WRONG_ORDER -> stringResource(R.string.scan_invalid_order)
        InvalidScanReason.EMPTY_PAYLOAD -> stringResource(R.string.scan_invalid_empty)
        // The expected length is only known once the session is locked to a
        // destination that has a fixed record length; before that — and for a
        // Denso session, whose record length varies — the message names the
        // observed length alone.
        InvalidScanReason.INCOMPLETE_QR_PAYLOAD ->
            state.lastInvalidPayloadLength?.let { observedLength ->
                state.destination?.let { CodeMatcher.expectedQrLength(it) }?.let { expectedLength ->
                    stringResource(
                        R.string.scan_invalid_qr_incomplete_length,
                        observedLength,
                        expectedLength,
                    )
                } ?: stringResource(
                    R.string.scan_invalid_qr_incomplete_length_unlocked,
                    observedLength,
                )
            } ?: stringResource(R.string.scan_invalid_qr_incomplete)
        InvalidScanReason.OVERLONG_QR_PAYLOAD ->
            state.lastInvalidPayloadLength?.let { observedLength ->
                state.destination?.let { CodeMatcher.expectedQrLength(it) }?.let { expectedLength ->
                    stringResource(
                        R.string.scan_invalid_qr_overlong_length,
                        observedLength,
                        expectedLength,
                    )
                } ?: stringResource(
                    R.string.scan_invalid_qr_overlong_length_unlocked,
                    observedLength,
                )
            } ?: stringResource(R.string.scan_invalid_qr_overlong)
        InvalidScanReason.INVALID_PAYLOAD -> stringResource(R.string.scan_invalid_payload)
        InvalidScanReason.WRONG_DESTINATION -> stringResource(
            R.string.scan_invalid_wrong_destination,
            destinationName(state.destination ?: Destination.SAWAI),
        )
        null -> null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                // Invalid/recoverable scan guidance is dynamic content. Keep
                // it discoverable to screen readers without stealing focus.
                liveRegion = LiveRegionMode.Polite
            }
            .testTag("scan_message"),
        colors = CardDefaults.cardColors(
            containerColor = if (invalid != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (invalid != null) Icons.Outlined.ErrorOutline else Icons.Outlined.QrCodeScanner,
                contentDescription = null,
            )
            Spacer(Modifier.width(10.dp))
            Text(invalidText ?: state.message.orEmpty())
        }
    }
}

@Composable
private fun DebugDemoTools(onAction: (ScanUiAction) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_debug_demo_tools"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.scan_demo_title), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onAction(ScanUiAction.DemoMatch) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("scan_demo_match"),
                ) { Text(stringResource(R.string.scan_demo_match)) }
                OutlinedButton(
                    onClick = { onAction(ScanUiAction.DemoMismatch) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("scan_demo_mismatch"),
                ) { Text(stringResource(R.string.scan_demo_mismatch)) }
            }
        }
    }
}

/** Resolve scan resources from an optional immutable language override. */
@Composable
private fun ScanLocalized(language: AppLanguage, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val localizedContext = remember(baseContext, baseConfiguration, language) {
        val configuration = Configuration(baseConfiguration).apply {
            setLocale(Locale.forLanguageTag(language.code))
        }
        baseContext.createConfigurationContext(configuration)
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        content = content,
    )
}

@Preview(showBackground = true, showSystemUi = false)
@Composable
private fun ScanStartPreview() {
    ScanScreen(state = ScanUiState(), onAction = {})
}

@Preview(showBackground = true, showSystemUi = false)
@Composable
private fun ScanResultPreview() {
    ScanScreen(
        state = ScanUiState.fromSession(
            session = ScanSessionState(
                scan = ScanState.Result(
                    qrPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*",
                    barcodePayload = "BCJH-52-81GG@1N5X0C",
                    result = MatchResult.MATCH,
                    matchedCount = 1,
                ),
                autoAdvanceEnabled = true,
                autoAdvanceSecondsRemaining = 3,
            ),
            sessionActive = true,
        ),
        onAction = {},
    )
}
