package jp.rimtty.codematch.feature.settings

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
import jp.rimtty.codematch.scanner.api.ScannerIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiStateTest {
    @Test
    fun defaultsExposeSafeSettingsAndFakeScannerPresentation() {
        val state = SettingsUiState()

        assertFalse(state.autoAdvanceEnabled)
        assertEquals(AutoAdvanceDelay.THREE_SECONDS, state.autoAdvanceDelay)
        assertEquals(1.0f, state.feedbackVolume)
        assertEquals(SuccessSound.POS_BEEP, state.successSound)
        assertEquals(FailureSound.ALARM, state.failureSound)
        assertEquals(AppLanguage.JAPANESE, state.language)
        assertEquals(SettingsPresentationState.FAKE_BLE, state.presentation)
        assertFalse(state.setupGuideVisible)
    }

    @Test
    fun selectedDeviceIsDerivedByStableIdentifierOrConnectedDevice() {
        val first = ScannerDevice(id = "first", name = "First")
        val second = ScannerDevice(id = "second", name = "Second")
        val selected = SettingsUiState(
            devices = listOf(first, second),
            selectedDeviceId = second.id,
        )
        assertEquals(second, selected.selectedDevice)

        val connected = SettingsUiState(
            connectionState = ConnectionState.Connected(first),
        )
        assertEquals(first, connected.selectedDevice)
    }

    @Test
    fun reconnectTargetIsOnlyAListedSelectionAndConnectingDeviceIsExposed() {
        val first = ScannerDevice(id = "first", name = "First")
        val second = ScannerDevice(id = "second", name = "Second")
        val connecting = SettingsUiState(
            devices = listOf(first, second),
            selectedDeviceId = second.id,
            connectionState = ConnectionState.Connecting(first),
        )
        assertEquals(second, connecting.reconnectTarget)
        assertEquals(first, connecting.connectingDevice)
        assertEquals(null, connecting.copy(selectedDeviceId = "gone").reconnectTarget)
        assertEquals(null, SettingsUiState(connectionState = ConnectionState.Connected(first)).reconnectTarget)
        assertFalse(SettingsUiState().connectRequestRejected)
    }

    @Test
    fun unresolvedGattSwitchAsksForReselection() {
        val state = SettingsUiState(
            connectionState = ConnectionState.Failed(
                jp.rimtty.codematch.scanner.api.ScannerFailureReasons.RESELECT_AFTER_GATT_SWITCH,
            ),
        )
        assertEquals(ScannerIssue.RESELECT_REQUIRED, state.resolvedScannerIssue)
        assertEquals(
            ScannerIssue.CONNECTION_FAILED,
            SettingsUiState(connectionState = ConnectionState.Failed("other")).resolvedScannerIssue,
        )
    }

    @Test
    fun releasePresentationIsExplicitAndCameraOnly() {
        val state = SettingsUiState(
            presentation = SettingsPresentationState.RELEASE_CAMERA_ONLY,
            devices = listOf(ScannerDevice("fake", "Fake")),
            connectionState = ConnectionState.Connected(
                ScannerDevice("fake", "Fake"),
            ),
        )

        assertTrue(state.isReleaseCameraOnly)
        assertTrue(state.cameraOnly)
        assertEquals(SettingsPresentationState.RELEASE_CAMERA_ONLY, state.scannerPresentation)
    }

    @Test
    fun scannerAndDiagnosticValuesRemainPlainData() {
        val event = DiagnosticEvent(
            category = DiagnosticCategory.CONFIGURATION,
            message = "transport state only",
            sequence = 7,
        )
        val state = SettingsUiState(
            settings = AppSettings(autoAdvanceEnabled = true),
            configurationState = ConfigurationState.Ready,
            diagnosticEvents = listOf(event),
        )

        assertEquals(true, state.appSettings.autoAdvanceEnabled)
        assertEquals(listOf(event), state.diagnostics)
        assertEquals(ConfigurationState.Ready, state.scannerConfigurationState)
    }

    @Test
    fun scannerIssueIsTypedAndDoesNotExposeTheAdapterReason() {
        val state = SettingsUiState(
            connectionState = ConnectionState.Unavailable("permission denied: platform detail"),
        )

        assertEquals(ScannerIssue.PERMISSION_DENIED, state.resolvedScannerIssue)
        assertEquals(ScannerIssue.PERMISSION_DENIED, state.bluetoothIssue)
    }

    @Test
    fun productionTransportPoweredOffReasonUsesTheDedicatedIssue() {
        val state = SettingsUiState(
            connectionState = ConnectionState.Unavailable("Bluetooth is off"),
        )

        assertEquals(ScannerIssue.POWERED_OFF, state.resolvedScannerIssue)
    }

    @Test
    fun restoreFailureTakesPriorityOverAStaleConnectionState() {
        val state = SettingsUiState(
            connectionState = ConnectionState.Connected(ScannerDevice("id", "Scanner")),
            configurationState = ConfigurationState.Failed("saved settings restore failed"),
        )

        assertEquals(ScannerIssue.RESTORE_FAILED, state.resolvedScannerIssue)
    }
}
