package jp.rimtty.codematch.feature.settings

import android.content.res.Configuration
import jp.rimtty.codematch.scanner.api.IlluminationState
import jp.rimtty.codematch.scanner.api.TuningState

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.FailureSound
import jp.rimtty.codematch.core.model.SuccessSound
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticCategory
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ScannerDevice
import kotlin.math.floor
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Stateless settings destination. The caller owns persistence, scanner
 * lifecycles, and navigation, and translates [SettingsUiAction] to those
 * effects. The feature never creates a scanner or a DataStore by itself.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Host-owned callback for opening the platform Bluetooth settings page.
     * The feature never launches an external Activity by itself.
     */
    onOpenBluetoothSettings: () -> Unit = {},
) {
    SettingsLocalized(state.language) {
        val screenDescription = stringResource(R.string.settings_accessibility_description)
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .semantics {
                    this.contentDescription = screenDescription
                    this.testTagsAsResourceId = true
                }
                .testTag(SettingsTestTags.SCREEN),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.settings_title),
                            modifier = Modifier.semantics {
                                stateDescription = screenDescription
                            },
                        )
                    },
                )
            },
        ) { innerPadding ->
            SettingsContent(
                state = state,
                onAction = onAction,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
                contentPadding = innerPadding,
            )
        }
    }
}

/** Alias used by navigation hosts that call feature roots `Route`. */
@Composable
fun SettingsRoute(
    state: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpenBluetoothSettings: () -> Unit = {},
) = SettingsScreen(
    state = state,
    onAction = onAction,
    modifier = modifier,
    onOpenBluetoothSettings = onOpenBluetoothSettings,
)

/** Alias kept small so app integration can choose either naming convention. */
@Composable
fun SettingsDestination(
    state: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpenBluetoothSettings: () -> Unit = {},
) = SettingsScreen(
    state = state,
    onAction = onAction,
    modifier = modifier,
    onOpenBluetoothSettings = onOpenBluetoothSettings,
)

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_eyebrow),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.8.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.settings_headline),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.settings_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.isReleaseCameraOnly) {
            CameraOnlyCard()
        } else {
            if (state.setupGuideVisible) {
                ScannerSetupGuide(
                    onClose = { onAction(SettingsUiAction.CloseSetupGuide) },
                )
            } else {
                TextButton(
                    onClick = { onAction(SettingsUiAction.OpenSetupGuide) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.SETUP_GUIDE_OPEN),
                ) {
                    Text(stringResource(R.string.settings_setup_guide_open))
                }
            }
            ScannerCard(
                state = state,
                onAction = onAction,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
            )
        }

        DiagnosticsCard(events = state.diagnosticEvents, onAction = onAction)
        AutoAdvanceCard(state = state, onAction = onAction)
        VolumeCard(volume = state.feedbackVolume, onVolumeChanged = {
            onAction(SettingsUiAction.SetFeedbackVolume(it))
        })
        SoundCard(
            title = stringResource(R.string.settings_success_sound_title),
            description = stringResource(R.string.settings_success_sound_description),
            choices = SuccessSound.entries,
            selected = state.successSound,
            choiceLabel = { successSoundLabel(it) },
            choiceTag = SettingsTestTags.SUCCESS_SOUND,
            previewTag = SettingsTestTags.SUCCESS_PREVIEW,
            onSelected = { onAction(SettingsUiAction.SetSuccessSound(it)) },
            onPreview = { onAction(SettingsUiAction.PreviewSuccessSound(it)) },
            modifier = Modifier.testTag(SettingsTestTags.SUCCESS_SOUNDS),
        )
        SoundCard(
            title = stringResource(R.string.settings_failure_sound_title),
            description = stringResource(R.string.settings_failure_sound_description),
            choices = FailureSound.entries,
            selected = state.failureSound,
            choiceLabel = { failureSoundLabel(it) },
            choiceTag = SettingsTestTags.FAILURE_SOUND,
            previewTag = SettingsTestTags.FAILURE_PREVIEW,
            onSelected = { onAction(SettingsUiAction.SetFailureSound(it)) },
            onPreview = { onAction(SettingsUiAction.PreviewFailureSound(it)) },
            modifier = Modifier.testTag(SettingsTestTags.FAILURE_SOUNDS),
        )
        LanguageCard(language = state.language, onLanguageChanged = {
            onAction(SettingsUiAction.SetLanguage(it))
        })
        ScanLogCard(count = state.scanLogCount, onAction = onAction)
    }
}

/**
 * The scan log lives at the very bottom of Settings: it is a debugging aid,
 * not a preference, and unlike the diagnostics above it contains the values
 * that were actually read. Sharing and saving are host-owned; only the clear
 * confirmation is handled here, so the dialog stays testable without Hilt.
 */
@Composable
private fun ScanLogCard(
    count: Int,
    onAction: (SettingsUiAction) -> Unit,
) {
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.SCAN_LOG),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Article, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_scan_log_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.settings_scan_log_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_scan_log_count, count),
                modifier = Modifier.testTag(SettingsTestTags.SCAN_LOG_COUNT),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { onAction(SettingsUiAction.ShareScanLog) },
                    enabled = count > 0,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.SCAN_LOG_SHARE),
                ) { Text(stringResource(R.string.settings_scan_log_share)) }
                TextButton(
                    onClick = { onAction(SettingsUiAction.SaveScanLog) },
                    enabled = count > 0,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.SCAN_LOG_SAVE),
                ) { Text(stringResource(R.string.settings_scan_log_save)) }
            }
            TextButton(
                onClick = { confirmingClear = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(SettingsTestTags.SCAN_LOG_CLEAR),
            ) { Text(stringResource(R.string.settings_scan_log_clear)) }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.settings_scan_log_clear_confirm_title)) },
            text = { Text(stringResource(R.string.settings_scan_log_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        onAction(SettingsUiAction.ClearScanLog)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.SCAN_LOG_CLEAR_CONFIRM),
                ) { Text(stringResource(R.string.settings_scan_log_clear_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingClear = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.settings_scan_log_clear_cancel)) }
            },
        )
    }
}

@Composable
private fun ScannerSetupGuide(onClose: () -> Unit) {
    val setupCodes = BluetoothScannerSetupCode.entries
    var currentStep by rememberSaveable { mutableStateOf(0) }
    var enlargedCode by remember { mutableStateOf<BluetoothScannerSetupCode?>(null) }
    val currentCode = setupCodes[currentStep]

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.SETUP_GUIDE),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_setup_guide_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                TextButton(
                    onClick = onClose,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.SETUP_GUIDE_CLOSE),
                ) {
                    Text(stringResource(R.string.settings_setup_guide_close))
                }
            }
            Text(
                text = stringResource(
                    R.string.settings_setup_progress,
                    currentStep + 1,
                    setupCodes.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            SetupCodeStep(
                code = currentCode,
                number = currentStep + 1,
                title = setupCodeTitle(currentCode),
                description = stringResource(setupCodeDescription(currentCode)),
                guidance = stringResource(setupCodeGuidance(currentCode)),
                onEnlarge = { enlargedCode = currentCode },
                modifier = Modifier.testTag(
                    when (currentStep) {
                        0 -> SettingsTestTags.SETUP_GUIDE_STEP_1
                        1 -> SettingsTestTags.SETUP_GUIDE_STEP_2
                        else -> SettingsTestTags.SETUP_GUIDE_STEP_3
                    },
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (currentStep > 0) {
                    OutlinedButton(
                        onClick = {
                            enlargedCode = null
                            currentStep -= 1
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(SettingsTestTags.SETUP_PREVIOUS),
                    ) {
                        Text(stringResource(R.string.settings_setup_previous))
                    }
                }
                Button(
                    onClick = {
                        enlargedCode = null
                        if (currentStep == setupCodes.lastIndex) {
                            onClose()
                        } else {
                            currentStep += 1
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.SETUP_NEXT),
                ) {
                    Text(
                        stringResource(
                            if (currentStep == setupCodes.lastIndex) {
                                R.string.settings_setup_finish
                            } else {
                                R.string.settings_setup_next
                            },
                        ),
                    )
                }
            }
        }
    }

    enlargedCode?.let { code ->
        FullscreenScannerSetupBarcode(
            code = code,
            title = setupCodeTitle(code),
            onDismiss = { enlargedCode = null },
        )
    }
}

private fun setupCodeDescription(code: BluetoothScannerSetupCode): Int = when (code) {
    BluetoothScannerSetupCode.ENTER_SETUP -> R.string.settings_setup_step_1_description
    BluetoothScannerSetupCode.GATT_MODE -> R.string.settings_setup_step_2_description
    BluetoothScannerSetupCode.SAVE_AND_EXIT -> R.string.settings_setup_step_3_description
}

private fun setupCodeGuidance(code: BluetoothScannerSetupCode): Int = when (code) {
    BluetoothScannerSetupCode.ENTER_SETUP -> R.string.settings_setup_step_1_guidance
    BluetoothScannerSetupCode.GATT_MODE -> R.string.settings_setup_step_2_guidance
    BluetoothScannerSetupCode.SAVE_AND_EXIT -> R.string.settings_setup_step_3_guidance
}

@Composable
private fun SetupCodeStep(
    code: BluetoothScannerSetupCode,
    number: Int,
    title: String,
    description: String,
    guidance: String,
    onEnlarge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stepDescription = stringResource(
        R.string.settings_setup_step_accessibility,
        number,
        title,
        description,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = stepDescription },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(number.toString(), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_setup_scan_instruction),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Code128SetupBarcode(
            code = code,
            title = title,
        )
        OutlinedButton(
            onClick = onEnlarge,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(SettingsTestTags.setupEnlarge(code)),
        ) {
            Icon(Icons.Outlined.Fullscreen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_setup_enlarge))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.shapes.small,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_setup_scanner_display_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = code.scannerDisplayText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = code.scannerDisplayText },
            )
        }
        Text(
            text = guidance,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    MaterialTheme.shapes.small,
                )
                .padding(12.dp),
        )
        Text(
            text = stringResource(R.string.settings_setup_after_scan),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Renders the same Code 128 vector as iOS without a bitmap or barcode SDK.
 * The outer white surface is intentionally opaque so the quiet zone stays
 * white on every Material theme and device contrast setting.
 */
@Composable
fun Code128SetupBarcode(
    code: BluetoothScannerSetupCode,
    title: String,
    modifier: Modifier = Modifier,
) {
    val barcode = remember(code) { Code128Encoder.encode(code) }
    val accessibilityLabel = stringResource(
        R.string.settings_setup_barcode_accessibility,
        title,
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .semantics {
                contentDescription = accessibilityLabel
            }
            .testTag(SettingsTestTags.SETUP_BARCODE),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        contentColor = Color.Black,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Match the iOS x4/y8 transform while keeping the image within a
            // reasonable height on tablets and landscape displays.
            val targetWidth = minOf(
                maxWidth,
                220.dp * barcode.iOSRenderAspectRatio,
            )
            val targetHeight = targetWidth / barcode.iOSRenderAspectRatio
            Code128BarcodeCanvas(
                barcode = barcode,
                modifier = Modifier
                    .size(targetWidth, targetHeight)
                    .testTag(SettingsTestTags.setupBarcode(code)),
            )
        }
    }
}

@Composable
private fun Code128BarcodeCanvas(
    barcode: Code128Barcode,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawRect(Color.White)
        // Canvas coordinates are physical pixels. Keeping each horizontal
        // module an integer number of pixels makes every vertical bar edge
        // pixel-aligned, even when the dp-sized container is not divisible by
        // the Code 128 vector width.
        val availableModuleWidth = size.width / barcode.widthModules
        val moduleWidth = if (availableModuleWidth >= 1f) {
            floor(availableModuleWidth)
        } else {
            // A very narrow split-screen may have fewer physical pixels than
            // modules. Fit rather than clip; normal/fullscreen layouts retain
            // integer-aligned bars for scanner acquisition.
            availableModuleWidth
        }
        val renderedWidth = moduleWidth * barcode.widthModules
        val left = ((size.width - renderedWidth) / 2f).roundToInt().toFloat()
        val moduleHeight = size.height / barcode.heightModules
        val top = barcode.quietZoneModules * moduleHeight
        val barHeight = barcode.barHeightModules * moduleHeight

        var blackRunStart = -1
        barcode.modules.forEachIndexed { index, isBlack ->
            if (isBlack && blackRunStart < 0) {
                blackRunStart = index
            } else if (!isBlack && blackRunStart >= 0) {
                drawBlackRun(
                    start = blackRunStart,
                    endExclusive = index,
                    left = left,
                    moduleWidth = moduleWidth,
                    top = top,
                    barHeight = barHeight,
                )
                blackRunStart = -1
            }
        }
        if (blackRunStart >= 0) {
            drawBlackRun(
                start = blackRunStart,
                endExclusive = barcode.modules.size,
                left = left,
                moduleWidth = moduleWidth,
                top = top,
                barHeight = barHeight,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlackRun(
    start: Int,
    endExclusive: Int,
    left: Float,
    moduleWidth: Float,
    top: Float,
    barHeight: Float,
) {
    drawRect(
        color = Color.Black,
        topLeft = Offset(left + start * moduleWidth, top),
        size = Size((endExclusive - start) * moduleWidth, barHeight),
    )
}

@Composable
private fun FullscreenScannerSetupBarcode(
    code: BluetoothScannerSetupCode,
    title: String,
    onDismiss: () -> Unit,
) {
    val barcode = remember(code) { Code128Encoder.encode(code) }
    val accessibilityLabel = stringResource(
        R.string.settings_setup_barcode_accessibility,
        title,
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val widthLimit = (maxWidth - 32.dp).coerceAtLeast(1.dp)
                val heightLimit = (maxHeight - 120.dp).coerceAtLeast(1.dp)
                val targetWidth = minOf(
                    widthLimit,
                    heightLimit * barcode.iOSRenderAspectRatio,
                )
                val targetHeight = targetWidth / barcode.iOSRenderAspectRatio
                Code128BarcodeCanvas(
                    barcode = barcode,
                    modifier = Modifier
                        .size(targetWidth, targetHeight)
                        .align(Alignment.Center)
                        .semantics {
                            contentDescription = accessibilityLabel
                        }
                        .testTag(SettingsTestTags.setupFullscreenBarcode(code)),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                    )
                    Text(
                        text = stringResource(R.string.settings_setup_fullscreen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.SETUP_FULLSCREEN_CLOSE),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_setup_fullscreen_close))
                }
                Text(
                    text = stringResource(R.string.settings_setup_fullscreen_hint),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .semantics { contentDescription = accessibilityLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1B5E20),
                )
            }
        }
    }
}

@Composable
private fun setupCodeTitle(code: BluetoothScannerSetupCode): String = stringResource(
    when (code) {
        BluetoothScannerSetupCode.ENTER_SETUP -> R.string.settings_setup_step_1_title
        BluetoothScannerSetupCode.GATT_MODE -> R.string.settings_setup_step_2_title
        BluetoothScannerSetupCode.SAVE_AND_EXIT -> R.string.settings_setup_step_3_title
    },
)

@Composable
private fun CameraOnlyCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.CAMERA_ONLY),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.settings_camera_only_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.settings_camera_only_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScannerCard(
    state: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val selectedDevice = state.selectedDevice
    val searching = state.connectionState is ConnectionState.Searching
    val connected = state.connectionState.connectedDevice
    val canReconnect = selectedDevice != null && !state.connectionState.isConnected
    val scannerIssue = state.resolvedScannerIssue
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.SCANNER_SECTION),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_scanner_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.settings_scanner_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.connectionState is ConnectionState.Connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .testTag(SettingsTestTags.SCANNER_PROGRESS),
                        strokeWidth = 3.dp,
                    )
                }
                Text(
                    text = connectionStatusText(state.connectionState),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SettingsTestTags.SCANNER_STATUS)
                        .semantics(mergeDescendants = true) {},
                )
            }
            Text(
                text = configurationStatusText(state.configurationState),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.SCANNER_CONFIGURATION_STATUS),
            )
            if (state.illuminationState != IlluminationState.UNSUPPORTED) {
                val lampLabel = stringResource(R.string.settings_illumination)
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lampLabel, modifier = Modifier.weight(1f))
                    when (state.illuminationState) {
                        IlluminationState.ON, IlluminationState.OFF -> Switch(
                            checked = state.illuminationState == IlluminationState.ON,
                            onCheckedChange = { onAction(SettingsUiAction.SetIllumination(it)) },
                            enabled = connected != null,
                            modifier = Modifier.testTag("settings_illumination")
                                .semantics { contentDescription = lampLabel },
                        )
                        IlluminationState.FAILED -> TextButton(
                            enabled = connected != null,
                            onClick = { onAction(SettingsUiAction.SetIllumination(false)) },
                            modifier = Modifier.testTag("settings_illumination_retry"),
                        ) { Text(stringResource(R.string.settings_illumination_retry_off)) }
                        else -> if (connected != null) CircularProgressIndicator(
                            modifier = Modifier.testTag("settings_illumination_pending"),
                        )
                    }
                }
                Text(stringResource(when (state.illuminationState) {
                    IlluminationState.APPLYING -> R.string.settings_illumination_applying
                    IlluminationState.FAILED -> R.string.settings_illumination_failed
                    IlluminationState.UNKNOWN -> R.string.settings_illumination_unknown
                    else -> R.string.settings_illumination_description
                }))
            }
            if (state.tuningState != TuningState.UNSUPPORTED && state.tuningState != TuningState.UNKNOWN) {
                Text(
                    text = stringResource(when (state.tuningState) {
                        TuningState.APPLYING -> R.string.settings_tuning_applying
                        TuningState.MATCHED -> R.string.settings_tuning_matched
                        TuningState.APPLIED -> R.string.settings_tuning_applied
                        else -> R.string.settings_tuning_failed
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.tuningState == TuningState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth().testTag("settings_tuning_status"),
                )
            }
            if (scannerIssue.isActionable) {
                ScannerIssueCard(
                    issue = scannerIssue,
                    onRetry = { onAction(SettingsUiAction.RetryScanner) },
                    onOpenBluetoothSettings = onOpenBluetoothSettings,
                )
            }
            if (connected != null) {
                Text(
                    text = connected.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = {
                    onAction(
                        if (searching) SettingsUiAction.StopDiscovery
                        else SettingsUiAction.StartDiscovery,
                    )
                },
                // Searching stays available while a (re)connection is pending:
                // the operator's search closes that attempt (#137).
                enabled = !state.connectionState.isConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag(SettingsTestTags.DISCOVERY),
            ) {
                Icon(
                    imageVector = if (searching) Icons.Outlined.LinkOff else Icons.Outlined.Search,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (searching) R.string.settings_stop_discovery
                        else R.string.settings_start_discovery,
                    ),
                )
            }
            if (state.devices.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.settings_devices_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.devices.forEach { device ->
                        DeviceRow(
                            device = device,
                            selected = selectedDevice?.id == device.id,
                            onClick = { onAction(SettingsUiAction.SelectDevice(device)) },
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.settings_no_devices),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectedDevice != null && !state.connectionState.isConnected) {
                Button(
                    onClick = { onAction(SettingsUiAction.Connect(selectedDevice)) },
                    // Only the device already being connected is disabled; any
                    // other choice pre-empts that attempt (#137).
                    enabled = state.connectingDevice?.id != selectedDevice.id,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag(SettingsTestTags.CONNECT),
                ) {
                    Icon(Icons.Outlined.Link, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_connect))
                }
            }
            if (state.connectRequestRejected) {
                Text(
                    text = stringResource(R.string.settings_connect_rejected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.CONNECT_REJECTED),
                )
            }
            if (state.connectionState.isConnected) {
                OutlinedButton(
                    onClick = { onAction(SettingsUiAction.Disconnect) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag(SettingsTestTags.DISCONNECT),
                ) {
                    Icon(Icons.Outlined.LinkOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_disconnect))
                }
            }
            if (canReconnect) {
                OutlinedButton(
                    onClick = { onAction(SettingsUiAction.Reconnect) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag(SettingsTestTags.RECONNECT),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_reconnect))
                }
            }
        }
    }
}

/**
 * Localized, actionable scanner failure surface.  Only the typed issue is
 * rendered; adapter exception/reason strings and scan values are omitted.
 */
@Composable
private fun ScannerIssueCard(
    issue: jp.rimtty.codematch.scanner.api.ScannerIssue,
    onRetry: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.SCANNER_ISSUE),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(scannerIssueTitle(issue)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(scannerIssueDescription(issue)),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.testTag(SettingsTestTags.SCANNER_ISSUE_MESSAGE),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.RETRY),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_scanner_retry))
                }
                if (issue.requiresSystemSettings) {
                    OutlinedButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(SettingsTestTags.OPEN_BLUETOOTH_SETTINGS),
                    ) {
                        Text(stringResource(R.string.settings_scanner_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: ScannerDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedText = stringResource(
        if (selected) R.string.settings_device_selected else R.string.settings_device_not_selected,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .testTag(SettingsTestTags.DEVICE_ROW),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = device.name
                    stateDescription = selectedText
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(4.dp))
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(
    events: List<DiagnosticEvent>,
    onAction: (SettingsUiAction) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (expanded) R.string.settings_diagnostics_collapse else R.string.settings_diagnostics_expand,
    )
    val visibleEvents = events.takeLast(MAX_DIAGNOSTIC_EVENTS)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.DIAGNOSTICS),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(SettingsTestTags.DIAGNOSTICS_TOGGLE)
                    .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = toggleLabel,
                )
            }
            if (expanded && visibleEvents.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_diagnostics_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (expanded) {
                visibleEvents.forEach { event ->
                    DiagnosticRow(event = event)
                }
            }
            if (events.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.settings_diagnostics_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { onAction(SettingsUiAction.ShareDiagnostics) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(SettingsTestTags.DIAGNOSTICS_SHARE),
                    ) { Text(stringResource(R.string.settings_diagnostics_share)) }
                    TextButton(
                        onClick = { onAction(SettingsUiAction.SaveDiagnostics) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(SettingsTestTags.DIAGNOSTICS_SAVE),
                    ) { Text(stringResource(R.string.settings_diagnostics_save)) }
                }
            }
        }
    }
}

/**
 * Render only a localized category and sequence. In particular, the adapter's
 * diagnostic message is never shown here: even a faulty adapter cannot put a
 * scan value into the settings UI or an accessibility tree.
 */
@Composable
private fun DiagnosticRow(event: DiagnosticEvent) {
    val category = diagnosticCategoryText(event.category)
    val description = stringResource(
        R.string.settings_diagnostic_accessibility,
        event.sequence,
        category,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(SettingsTestTags.DIAGNOSTIC_ROW)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.settings_diagnostic_row, event.sequence, category))
    }
}

@Composable
private fun AutoAdvanceCard(
    state: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.AUTO_ADVANCE),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_auto_advance_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(
                            if (state.autoAdvanceEnabled) {
                                R.string.settings_auto_advance_enabled_description
                            } else {
                                R.string.settings_auto_advance_disabled_description
                            },
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = state.autoAdvanceEnabled,
                    onCheckedChange = {
                        onAction(SettingsUiAction.SetAutoAdvanceEnabled(it))
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.AUTO_ADVANCE_SWITCH),
                )
            }
            Text(
                text = stringResource(R.string.settings_delay_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .testTag(SettingsTestTags.DELAY_CHOICES),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AutoAdvanceDelay.entries.forEach { delay ->
                    val label = stringResource(R.string.settings_delay_seconds, delay.seconds)
                    FilterChip(
                        selected = state.autoAdvanceDelay == delay,
                        onClick = {
                            onAction(SettingsUiAction.SetAutoAdvanceDelay(delay))
                        },
                        enabled = state.autoAdvanceEnabled,
                        label = { Text(label) },
                        leadingIcon = if (state.autoAdvanceDelay == delay) {
                            {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(SettingsTestTags.DELAY_CHOICE)
                            .semantics(mergeDescendants = true) {},
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(
    volume: Float,
    onVolumeChanged: (Float) -> Unit,
) {
    val percent = (volume.coerceIn(0f, 1f) * 100f).roundToInt()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.VOLUME),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_volume_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.settings_volume_percent, percent),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = volume.coerceIn(0f, 1f),
                onValueChange = onVolumeChanged,
                valueRange = 0f..1f,
                steps = 9,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_volume_minimum))
                Text(stringResource(R.string.settings_volume_maximum))
            }
        }
    }
}

@Composable
private fun <T> SoundCard(
    title: String,
    description: String,
    choices: List<T>,
    selected: T,
    choiceLabel: @Composable (T) -> String,
    choiceTag: String,
    previewTag: String,
    onSelected: (T) -> Unit,
    onPreview: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            choices.forEach { choice ->
                SoundChoiceRow(
                    label = choiceLabel(choice),
                    selected = choice == selected,
                    choiceTag = choiceTag,
                    previewTag = previewTag,
                    onSelected = { onSelected(choice) },
                    onPreview = { onPreview(choice) },
                )
            }
        }
    }
}

@Composable
private fun SoundChoiceRow(
    label: String,
    selected: Boolean,
    choiceTag: String,
    previewTag: String,
    onSelected: () -> Unit,
    onPreview: () -> Unit,
) {
    val selectedText = stringResource(
        if (selected) R.string.settings_choice_selected else R.string.settings_choice_not_selected,
    )
    val previewDescription = stringResource(R.string.settings_preview_sound, label)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                // iOS previews the tapped sound from the row's own click
                // handler, including when the current option is re-selected.
                // Playing from the click (never from a state observer) keeps
                // restore/rotation silent.
                onClick = {
                    onSelected()
                    onPreview()
                },
                role = Role.RadioButton,
            )
            .testTag(choiceTag),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = selectedText
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(4.dp))
            Text(label, modifier = Modifier.weight(1f))
            IconButton(
                onClick = onPreview,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(previewTag)
                    .semantics {
                        contentDescription = previewDescription
                    },
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            }
        }
    }
}

@Composable
private fun LanguageCard(
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.LANGUAGE),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Language, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_language_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.settings_language_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLanguage.entries.forEach { choice ->
                    val label = languageLabel(choice)
                    FilterChip(
                        selected = choice == language,
                        onClick = { onLanguageChanged(choice) },
                        label = { Text(label) },
                        leadingIcon = if (choice == language) {
                            {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(SettingsTestTags.LANGUAGE_CHOICE)
                            .semantics(mergeDescendants = true) {},
                    )
                }
            }
        }
    }
}

@Composable
private fun successSoundLabel(sound: SuccessSound): String = stringResource(
    when (sound) {
        SuccessSound.SAMPLE_1 -> R.string.settings_success_sound_sample_1
        SuccessSound.SAMPLE_2 -> R.string.settings_success_sound_sample_2
        SuccessSound.POS_BEEP -> R.string.settings_success_sound_pos_beep
        SuccessSound.DOUBLE_BEEP -> R.string.settings_success_sound_double_beep
        SuccessSound.CHIME -> R.string.settings_success_sound_chime
    },
)

@Composable
private fun failureSoundLabel(sound: FailureSound): String = stringResource(
    when (sound) {
        FailureSound.FAIL_SAMPLE -> R.string.settings_failure_sound_sample
        FailureSound.BUZZER -> R.string.settings_failure_sound_buzzer
        FailureSound.ALARM -> R.string.settings_failure_sound_alarm
        FailureSound.DESCEND -> R.string.settings_failure_sound_descend
    },
)

@Composable
private fun languageLabel(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.JAPANESE -> R.string.settings_language_japanese
        AppLanguage.ENGLISH -> R.string.settings_language_english
    },
)

@Composable
private fun connectionStatusText(state: ConnectionState): String = stringResource(
    when (state) {
        ConnectionState.Idle -> R.string.settings_status_idle
        ConnectionState.Searching -> R.string.settings_status_searching
        is ConnectionState.Connecting -> R.string.settings_status_connecting
        is ConnectionState.Connected -> R.string.settings_status_connected
        is ConnectionState.Unavailable -> R.string.settings_status_unavailable
        is ConnectionState.Failed -> R.string.settings_status_failed
    },
)

@Composable
private fun configurationStatusText(state: ConfigurationState): String = stringResource(
    when (state) {
        ConfigurationState.Unavailable -> R.string.settings_configuration_unavailable
        ConfigurationState.Configuring -> R.string.settings_configuration_configuring
        ConfigurationState.Ready -> R.string.settings_configuration_ready
        is ConfigurationState.Failed -> R.string.settings_configuration_failed
    },
)

@Composable
private fun diagnosticCategoryText(category: DiagnosticCategory): String = stringResource(
    when (category) {
        DiagnosticCategory.CONNECTION -> R.string.settings_diagnostic_connection
        DiagnosticCategory.CONFIGURATION -> R.string.settings_diagnostic_configuration
        DiagnosticCategory.ERROR -> R.string.settings_diagnostic_error
    },
)

@androidx.annotation.StringRes
private fun scannerIssueTitle(
    issue: jp.rimtty.codematch.scanner.api.ScannerIssue,
): Int = when (issue) {
    jp.rimtty.codematch.scanner.api.ScannerIssue.PERMISSION_DENIED ->
        R.string.settings_scanner_permission_title
    jp.rimtty.codematch.scanner.api.ScannerIssue.POWERED_OFF ->
        R.string.settings_scanner_powered_off_title
    jp.rimtty.codematch.scanner.api.ScannerIssue.UNSUPPORTED ->
        R.string.settings_scanner_unsupported_title
    jp.rimtty.codematch.scanner.api.ScannerIssue.UNAVAILABLE ->
        R.string.settings_scanner_unavailable_title
    jp.rimtty.codematch.scanner.api.ScannerIssue.CONNECTION_FAILED ->
        R.string.settings_scanner_connection_failed_title
    jp.rimtty.codematch.scanner.api.ScannerIssue.CONFIGURATION_FAILED ->
        R.string.settings_scanner_configuration_failed_title
    jp.rimtty.codematch.scanner.api.ScannerIssue.RESTORE_FAILED ->
        R.string.settings_scanner_restore_failed_title
    jp.rimtty.codematch.scanner.api.ScannerIssue.RESELECT_REQUIRED ->
        R.string.settings_scanner_reselect_title
    jp.rimtty.codematch.scanner.api.ScannerIssue.NONE -> R.string.settings_scanner_title
}

@androidx.annotation.StringRes
private fun scannerIssueDescription(
    issue: jp.rimtty.codematch.scanner.api.ScannerIssue,
): Int = when (issue) {
    jp.rimtty.codematch.scanner.api.ScannerIssue.PERMISSION_DENIED ->
        R.string.settings_scanner_permission_description
    jp.rimtty.codematch.scanner.api.ScannerIssue.POWERED_OFF ->
        R.string.settings_scanner_powered_off_description
    jp.rimtty.codematch.scanner.api.ScannerIssue.UNSUPPORTED ->
        R.string.settings_scanner_unsupported_description
    jp.rimtty.codematch.scanner.api.ScannerIssue.UNAVAILABLE ->
        R.string.settings_scanner_unavailable_description
    jp.rimtty.codematch.scanner.api.ScannerIssue.CONNECTION_FAILED ->
        R.string.settings_scanner_connection_failed_description
    jp.rimtty.codematch.scanner.api.ScannerIssue.CONFIGURATION_FAILED ->
        R.string.settings_scanner_configuration_failed_description
    jp.rimtty.codematch.scanner.api.ScannerIssue.RESTORE_FAILED ->
        R.string.settings_scanner_restore_failed_description
    jp.rimtty.codematch.scanner.api.ScannerIssue.RESELECT_REQUIRED ->
        R.string.settings_scanner_reselect_description
    jp.rimtty.codematch.scanner.api.ScannerIssue.NONE -> R.string.settings_scanner_description
}

/** Resolve settings resources from the language carried by immutable UI state. */
@Composable
private fun SettingsLocalized(language: AppLanguage, content: @Composable () -> Unit) {
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

private const val MAX_DIAGNOSTIC_EVENTS = 20
