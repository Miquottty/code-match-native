package jp.rimtty.codematch.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.AppSettings
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.FailureSound
import jp.rimtty.codematch.core.model.SuccessSound
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticCategory
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.api.IlluminationState
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @Test
    fun illuminationWaitsForConfirmationAndDisablesDuringWrite() {
        val actions = mutableListOf<SettingsUiAction>()
        val state = mutableStateOf(SettingsUiState(
            connectionState = ConnectionState.Connected(ScannerDevice("lamp-test", "Test scanner")),
            illuminationState = IlluminationState.UNKNOWN,
            setupGuideVisible = false,
        ))
        composeRule.setContent {
            MaterialTheme { SettingsScreen(state.value, onAction = actions::add) }
        }
        val lamp = composeRule.onNodeWithTag("settings_illumination")
        composeRule.onAllNodesWithTag("settings_illumination").assertCountEquals(0)
        composeRule.onAllNodesWithTag("settings_illumination_pending").assertCountEquals(1)
        composeRule.runOnIdle { state.value = state.value.copy(illuminationState = IlluminationState.OFF) }
        lamp.performScrollTo().assertIsEnabled().assertIsOff().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(SettingsUiAction.SetIllumination(true)), actions)
            state.value = state.value.copy(illuminationState = IlluminationState.APPLYING)
        }
        composeRule.onAllNodesWithTag("settings_illumination").assertCountEquals(0)
        composeRule.onAllNodesWithTag("settings_illumination_pending").assertCountEquals(1)
        composeRule.runOnIdle { state.value = state.value.copy(illuminationState = IlluminationState.FAILED) }
        composeRule.onAllNodesWithTag("settings_illumination").assertCountEquals(0)
        composeRule.onNodeWithTag("settings_illumination_retry").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(SettingsUiAction.SetIllumination(false), actions.last())
        }
        composeRule.runOnIdle { state.value = state.value.copy(illuminationState = IlluminationState.ON) }
        lamp.assertIsEnabled().assertIsOn()
        composeRule.runOnIdle { state.value = state.value.copy(connectionState = ConnectionState.Idle) }
        lamp.assertIsNotEnabled()
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun guideIsClosedByDefaultAndCanBeOpened() {
        val state = mutableStateOf(SettingsUiState())
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(state.value, onAction = { action ->
                    if (action == SettingsUiAction.OpenSetupGuide) {
                        state.value = state.value.copy(setupGuideVisible = true)
                    }
                })
            }
        }
        composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_GUIDE).assertCountEquals(0)
        composeRule.onNodeWithTag(SettingsTestTags.SETUP_GUIDE_OPEN).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.SETUP_GUIDE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun explicitlyOpenedGuideShowsThreeStepsAndAllPreferenceGroups() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(SettingsUiState(setupGuideVisible = true), onAction = {})
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SETUP_GUIDE).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.SETUP_GUIDE_STEP_1).assertIsDisplayed()
        composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_BARCODE).assertCountEquals(1)
        composeRule.onAllNodesWithTag(
            SettingsTestTags.setupBarcode(BluetoothScannerSetupCode.ENTER_SETUP),
        ).assertCountEquals(1)
        composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_GUIDE_STEP_2).assertCountEquals(0)
        composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_GUIDE_STEP_3).assertCountEquals(0)
        composeRule.onAllNodesWithTag(SettingsTestTags.AUTO_ADVANCE).assertCountEquals(1)
        composeRule.onAllNodesWithTag(SettingsTestTags.VOLUME).assertCountEquals(1)
        composeRule.onAllNodesWithTag(SettingsTestTags.SUCCESS_SOUNDS).assertCountEquals(1)
        composeRule.onAllNodesWithTag(SettingsTestTags.FAILURE_SOUNDS).assertCountEquals(1)
        composeRule.onAllNodesWithTag(SettingsTestTags.LANGUAGE).assertCountEquals(1)
        composeRule.onAllNodesWithTag(SettingsTestTags.SCAN_LOG).assertCountEquals(1)
        composeRule.onAllNodesWithTag(SettingsTestTags.SUCCESS_SOUND).assertCountEquals(5)
        composeRule.onAllNodesWithTag(SettingsTestTags.FAILURE_SOUND).assertCountEquals(4)
        composeRule.onAllNodesWithTag(SettingsTestTags.DELAY_CHOICE).assertCountEquals(3)
        composeRule.onAllNodesWithTag(SettingsTestTags.LANGUAGE_CHOICE).assertCountEquals(2)
    }

    @Test
    fun primarySettingsControlsKeepAccessibleTouchTargets() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(SettingsUiState(setupGuideVisible = true), onAction = {})
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SETUP_GUIDE_CLOSE)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SettingsTestTags.SETUP_NEXT)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SettingsTestTags.AUTO_ADVANCE_SWITCH)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodesWithTag(SettingsTestTags.LANGUAGE_CHOICE)
            .get(0)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_SHARE)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_SAVE)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_CLEAR)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
    }

    /**
     * The scan log carries the values that were actually read, so sharing and
     * saving stay unavailable until there is something to share, and clearing
     * always goes through a confirmation.
     */
    @Test
    fun scanLogCardReportsItsCountAndGatesSharingSavingAndClearing() {
        val actions = mutableListOf<SettingsUiAction>()
        val state = mutableStateOf(SettingsUiState())
        composeRule.setContent {
            MaterialTheme { SettingsScreen(state.value, onAction = actions::add) }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_COUNT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_SHARE)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_SAVE)
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.runOnIdle { state.value = state.value.copy(scanLogCount = 12) }

        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_COUNT)
            .performScrollTo()
            .assertTextContains("12", substring = true)
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_SHARE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_SAVE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(SettingsUiAction.ShareScanLog, SettingsUiAction.SaveScanLog),
                actions.toList(),
            )
        }

        // Tapping clear only opens the dialog; nothing is emitted yet.
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_CLEAR)
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertTrue(!actions.contains(SettingsUiAction.ClearScanLog))
        }
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_CLEAR_CONFIRM)
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(SettingsUiAction.ClearScanLog, actions.last())
        }
        composeRule.onAllNodesWithTag(SettingsTestTags.SCAN_LOG_CLEAR_CONFIRM).assertCountEquals(0)
    }

    @Test
    fun setupGuideShowsOneCodeAtATimeAndAdvancesInScannerOrder() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(SettingsUiState(setupGuideVisible = true), onAction = {})
            }
        }

        BluetoothScannerSetupCode.entries.forEachIndexed { index, code ->
            composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_BARCODE).assertCountEquals(1)
            composeRule.onAllNodesWithTag(
                SettingsTestTags.setupBarcode(code),
            ).assertCountEquals(1)
            BluetoothScannerSetupCode.entries
                .filterNot { it == code }
                .forEach { otherCode ->
                    composeRule.onAllNodesWithTag(
                        SettingsTestTags.setupBarcode(otherCode),
                    ).assertCountEquals(0)
                }
            if (index > 0) {
                composeRule.onNodeWithTag(SettingsTestTags.SETUP_PREVIOUS).assertIsDisplayed()
            }
            if (index < BluetoothScannerSetupCode.entries.lastIndex) {
                composeRule.onNodeWithTag(SettingsTestTags.SETUP_NEXT)
                    .performScrollTo()
                    .performClick()
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SETUP_PREVIOUS)
            .performScrollTo()
            .performClick()
        composeRule.onAllNodesWithTag(
            SettingsTestTags.setupBarcode(BluetoothScannerSetupCode.GATT_MODE),
        ).assertCountEquals(1)
    }

    @Test
    fun setupBarcodeCanBeEnlargedAndClosedWithoutChangingTheGuide() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(SettingsUiState(setupGuideVisible = true), onAction = {})
            }
        }

        composeRule.onNodeWithTag(
            SettingsTestTags.setupEnlarge(BluetoothScannerSetupCode.ENTER_SETUP),
        ).performScrollTo().performClick()
        composeRule.onNodeWithTag(
            SettingsTestTags.setupFullscreenBarcode(BluetoothScannerSetupCode.ENTER_SETUP),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.SETUP_FULLSCREEN_CLOSE).performClick()
        composeRule.onAllNodesWithTag(
            SettingsTestTags.setupBarcode(BluetoothScannerSetupCode.ENTER_SETUP),
        ).assertCountEquals(1)
    }

    @Test
    fun completingTheThirdSetupCodeEmitsCloseAction() {
        val actions = mutableListOf<SettingsUiAction>()
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(SettingsUiState(setupGuideVisible = true), onAction = actions::add)
            }
        }

        repeat(3) {
            composeRule.onNodeWithTag(SettingsTestTags.SETUP_NEXT)
                .performScrollTo()
                .performClick()
        }

        assertTrue(actions.contains(SettingsUiAction.CloseSetupGuide))
    }

    @Test
    fun scannerActionsAndSoundLanguageCallbacksAreEmitted() {
        val device = ScannerDevice("scanner-1", "Scanner one")
        val actions = mutableListOf<SettingsUiAction>()
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        devices = listOf(device),
                        selectedDeviceId = device.id,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.DISCOVERY).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.CONNECT).performScrollTo().performClick()
        composeRule.onAllNodesWithTag(SettingsTestTags.SUCCESS_PREVIEW).get(0).performScrollTo().performClick()
        composeRule.onAllNodesWithTag(SettingsTestTags.FAILURE_PREVIEW).get(0).performScrollTo().performClick()
        composeRule.onAllNodesWithTag(SettingsTestTags.LANGUAGE_CHOICE).get(1).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.AUTO_ADVANCE_SWITCH).performScrollTo().performClick()

        assertTrue(actions.contains(SettingsUiAction.StartDiscovery))
        assertTrue(actions.contains(SettingsUiAction.Connect(device)))
        assertTrue(actions.contains(SettingsUiAction.PreviewSuccessSound(SuccessSound.SAMPLE_1)))
        assertTrue(actions.contains(SettingsUiAction.PreviewFailureSound(FailureSound.FAIL_SAMPLE)))
        assertTrue(actions.contains(SettingsUiAction.SetLanguage(AppLanguage.ENGLISH)))
        assertTrue(actions.contains(SettingsUiAction.SetAutoAdvanceEnabled(true)))
    }

    /**
     * iOS plays the tapped sound straight from the row's click handler
     * (SettingsScreen.swift's SoundOptionRow action -> FeedbackPlayer.preview),
     * so selecting a sound must emit both the setting write and one preview.
     */
    @Test
    fun selectingASoundRowSetsItAndPreviewsItOnce() {
        val actions = mutableListOf<SettingsUiAction>()
        composeRule.setContent {
            MaterialTheme { SettingsScreen(SettingsUiState(), onAction = actions::add) }
        }

        // The preview is bound to the click, never to a state observer, so the
        // first composition (and any restore/rotation) stays silent.
        composeRule.runOnIdle { assertEquals(emptyList<SettingsUiAction>(), soundActions(actions)) }

        composeRule.onAllNodesWithTag(SettingsTestTags.SUCCESS_SOUND).get(4)
            .performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsUiAction.SetSuccessSound(SuccessSound.CHIME),
                    SettingsUiAction.PreviewSuccessSound(SuccessSound.CHIME),
                ),
                soundActions(actions),
            )
            actions.clear()
        }

        composeRule.onAllNodesWithTag(SettingsTestTags.FAILURE_SOUND).get(1)
            .performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsUiAction.SetFailureSound(FailureSound.BUZZER),
                    SettingsUiAction.PreviewFailureSound(FailureSound.BUZZER),
                ),
                soundActions(actions),
            )
        }
    }

    /** iOS previews unconditionally, including for the already-selected row. */
    @Test
    fun reSelectingTheCurrentSoundPreviewsItAgain() {
        val actions = mutableListOf<SettingsUiAction>()
        composeRule.setContent {
            MaterialTheme { SettingsScreen(SettingsUiState(), onAction = actions::add) }
        }

        // SettingsUiState() defaults to POS_BEEP / ALARM, both at index 2.
        composeRule.onAllNodesWithTag(SettingsTestTags.SUCCESS_SOUND).get(2)
            .performScrollTo().performClick()
        composeRule.onAllNodesWithTag(SettingsTestTags.FAILURE_SOUND).get(2)
            .performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsUiAction.SetSuccessSound(SuccessSound.POS_BEEP),
                    SettingsUiAction.PreviewSuccessSound(SuccessSound.POS_BEEP),
                    SettingsUiAction.SetFailureSound(FailureSound.ALARM),
                    SettingsUiAction.PreviewFailureSound(FailureSound.ALARM),
                ),
                soundActions(actions),
            )
        }
    }

    /** The dedicated play button previews without changing the selection. */
    @Test
    fun theRowPreviewButtonOnlyPreviews() {
        val actions = mutableListOf<SettingsUiAction>()
        composeRule.setContent {
            MaterialTheme { SettingsScreen(SettingsUiState(), onAction = actions::add) }
        }

        composeRule.onAllNodesWithTag(SettingsTestTags.SUCCESS_PREVIEW).get(1)
            .performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(SettingsUiAction.PreviewSuccessSound(SuccessSound.SAMPLE_2)),
                soundActions(actions),
            )
        }
    }

    private fun soundActions(actions: List<SettingsUiAction>): List<SettingsUiAction> =
        actions.filter {
            it is SettingsUiAction.SetSuccessSound ||
                it is SettingsUiAction.PreviewSuccessSound ||
                it is SettingsUiAction.SetFailureSound ||
                it is SettingsUiAction.PreviewFailureSound
        }

    @Test
    fun languageStateRedrawsSettingsTextWithoutWaitingForActivityRecreation() {
        val state = mutableStateOf(SettingsUiState())
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = state.value,
                    onAction = { action ->
                        if (action is SettingsUiAction.SetLanguage) {
                            state.value = state.value.copy(
                                settings = state.value.settings.copy(language = action.language),
                            )
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("表示言語").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Display language").assertCountEquals(0)

        composeRule.onAllNodesWithTag(SettingsTestTags.LANGUAGE_CHOICE)
            .get(1)
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("Display language").assertIsDisplayed()
        composeRule.onAllNodesWithText("表示言語").assertCountEquals(0)
    }

    @Test
    fun enabledAutoAdvanceOffersOneThreeAndFiveSeconds() {
        val actions = mutableListOf<SettingsUiAction>()
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        settings = AppSettings(autoAdvanceEnabled = true),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onAllNodesWithTag(SettingsTestTags.DELAY_CHOICE).get(0).performScrollTo().performClick()

        assertTrue(actions.contains(SettingsUiAction.SetAutoAdvanceDelay(AutoAdvanceDelay.ONE_SECOND)))
    }

    @Test
    fun connectedScannerCanDisconnectAndSelectedScannerCanReconnect() {
        val device = ScannerDevice("scanner-1", "Scanner one")
        val actions = mutableListOf<SettingsUiAction>()
        val state = mutableStateOf(
            SettingsUiState(
                devices = listOf(device),
                selectedDeviceId = device.id,
                connectionState = ConnectionState.Connected(device),
                configurationState = ConfigurationState.Ready,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(state = state.value, onAction = { action ->
                    actions += action
                    if (action == SettingsUiAction.Disconnect) {
                        state.value = state.value.copy(connectionState = ConnectionState.Idle)
                    }
                })
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SCANNER_STATUS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.DISCONNECT).performScrollTo().performClick()
        assertTrue(actions.contains(SettingsUiAction.Disconnect))

        composeRule.onNodeWithTag(SettingsTestTags.RECONNECT).performScrollTo().performClick()
        assertTrue(actions.contains(SettingsUiAction.Reconnect))
    }

    @Test
    fun refusedConnectRequestIsShown() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(connectRequestRejected = true),
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithTag(SettingsTestTags.CONNECT_REJECTED)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun connectingScannerShowsProgressUntilConnectionCompletes() {
        val device = ScannerDevice("scanner-1", "Scanner one")
        val state = mutableStateOf(
            SettingsUiState(
                devices = listOf(device),
                selectedDeviceId = device.id,
                connectionState = ConnectionState.Connecting(device),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(state = state.value, onAction = {})
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SCANNER_PROGRESS)
            .performScrollTo()
            .assertIsDisplayed()
        // #137: searching and choosing another scanner stay available while a
        // connection is pending; only the device being connected is disabled.
        composeRule.onNodeWithTag(SettingsTestTags.DISCOVERY)
            .performScrollTo()
            .assertIsEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.CONNECT)
            .performScrollTo()
            .assertIsNotEnabled()
        val other = ScannerDevice("scanner-2", "Scanner two")
        state.value = state.value.copy(devices = listOf(device, other), selectedDeviceId = other.id)
        composeRule.onNodeWithTag(SettingsTestTags.CONNECT)
            .performScrollTo()
            .assertIsEnabled()

        state.value = state.value.copy(connectionState = ConnectionState.Connected(device))
        composeRule.onAllNodesWithTag(SettingsTestTags.SCANNER_PROGRESS).assertCountEquals(0)
        composeRule.onNodeWithTag(SettingsTestTags.DISCOVERY).assertIsNotEnabled()

        state.value = state.value.copy(connectionState = ConnectionState.Idle)
        composeRule.onNodeWithTag(SettingsTestTags.DISCOVERY).assertIsEnabled()
    }

    @Test
    fun configurationStatusIsVisibleAndFailureReasonStaysHidden() {
        val privateReason = "private adapter setting detail"
        val state = mutableStateOf(
            SettingsUiState(
                connectionState = ConnectionState.Connected(
                    ScannerDevice("scanner-1", "Scanner one"),
                ),
                configurationState = ConfigurationState.Configuring,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(state = state.value, onAction = {})
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SCANNER_CONFIGURATION_STATUS)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle {
            state.value = state.value.copy(
                configurationState = ConfigurationState.Failed(privateReason),
            )
        }

        composeRule.onNodeWithTag(SettingsTestTags.SCANNER_CONFIGURATION_STATUS)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(privateReason).assertCountEquals(0)
        composeRule.onNodeWithTag(SettingsTestTags.SCANNER_ISSUE_MESSAGE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun diagnosticsNeverExposeDiagnosticMessageOrScanPayload() {
        val payload = "private-scan-payload-123"
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        diagnosticEvents = listOf(
                            DiagnosticEvent(
                                category = DiagnosticCategory.CONNECTION,
                                message = payload,
                                sequence = 1,
                            ),
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onAllNodesWithTag(SettingsTestTags.DIAGNOSTICS).assertCountEquals(1)
        composeRule.onAllNodesWithTag(SettingsTestTags.DIAGNOSTIC_ROW).assertCountEquals(0)
        composeRule.onNodeWithTag(SettingsTestTags.DIAGNOSTICS_TOGGLE).performScrollTo().performClick()
        composeRule.onAllNodesWithTag(SettingsTestTags.DIAGNOSTIC_ROW).assertCountEquals(1)
        composeRule.onAllNodesWithText(payload).assertCountEquals(0)
        composeRule.onNodeWithTag(SettingsTestTags.DIAGNOSTICS_TOGGLE).performScrollTo().performClick()
        composeRule.onAllNodesWithTag(SettingsTestTags.DIAGNOSTIC_ROW).assertCountEquals(0)
    }

    @Test
    fun releasePresentationShowsCameraOnlyAndHidesBluetoothControls() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        presentation = SettingsPresentationState.RELEASE_CAMERA_ONLY,
                        devices = listOf(ScannerDevice("scanner-1", "Scanner one")),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.CAMERA_ONLY).assertIsDisplayed()
        composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_GUIDE).assertCountEquals(0)
        composeRule.onAllNodesWithTag(SettingsTestTags.SCANNER_SECTION).assertCountEquals(0)
    }

    @Test
    fun scannerIssueUsesLocalizedCopyAndHostOwnedSettingsAction() {
        val actions = mutableListOf<SettingsUiAction>()
        var settingsOpenCount = 0
        val privateReason = "permission denied: transport detail that must stay hidden"
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        connectionState = ConnectionState.Unavailable(privateReason),
                    ),
                    onAction = actions::add,
                    onOpenBluetoothSettings = { settingsOpenCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SCANNER_ISSUE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.SCANNER_ISSUE_MESSAGE).assertIsDisplayed()
        composeRule.onAllNodesWithText(privateReason).assertCountEquals(0)
        composeRule.onNodeWithTag(SettingsTestTags.RETRY).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.OPEN_BLUETOOTH_SETTINGS).performClick()

        assertTrue(actions.contains(SettingsUiAction.RetryScanner))
        assertEquals(1, settingsOpenCount)
    }
}
