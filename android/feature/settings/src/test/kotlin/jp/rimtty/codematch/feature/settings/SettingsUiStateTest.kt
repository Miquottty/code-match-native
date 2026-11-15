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
    fun disconnectFirstHintShowsOnlyWhileAnotherScannerThanTheSelectionIsConnected() {
        val first = ScannerDevice(id = "first", name = "First")
        val second = ScannerDevice(id = "second", name = "Second")
        val connectedToFirst = SettingsUiState(
            devices = listOf(first, second),
            connectionState = ConnectionState.Connected(first),
        )
        // Nothing selected, or the connected scanner itself is selected.
        assertFalse(connectedToFirst.showsDisconnectFirstHint)
        assertFalse(connectedToFirst.copy(selectedDeviceId = first.id).showsDisconnectFirstHint)
        // A selection that is not listed any more is not a target.
        assertFalse(connectedToFirst.copy(selectedDeviceId = "gone").showsDisconnectFirstHint)
        // A different scanner is selected: the link has to be closed first.
        assertTrue(connectedToFirst.copy(selectedDeviceId = second.id).showsDisconnectFirstHint)

        // Nothing is connected: the selection can simply be connected.
        listOf(
            ConnectionState.Idle,
            ConnectionState.Searching,
            ConnectionState.Connecting(first),
            ConnectionState.Failed("lost"),
            ConnectionState.Unavailable("Bluetooth is off"),
        ).forEach { connection ->
            assertFalse(
                connectedToFirst
                    .copy(selectedDeviceId = second.id, connectionState = connection)
                    .showsDisconnectFirstHint,
            )
        }
    }

    @Test
    fun knownScannerIsOfferedOnlyWhileNothingIsConnectedOrSelected() {
        val known = ScannerDevice(id = "known", name = "HPRT-4F5F")
        val other = ScannerDevice(id = "other", name = "HPRT-636E")

        // After a restart: nothing listed, nothing selected.
        val restarted = SettingsUiState(knownDevice = known)
        assertEquals(known, restarted.knownScannerToReconnect)
        assertTrue(restarted.canReconnectKnownScanner)
        // Reconnect then falls back to the adapter's known device.
        assertEquals(null, restarted.reconnectTarget)

        // Still offered while searching, after a failure, or with a list but
        // no selection (also a selection that is not listed any more).
        listOf(
            restarted.copy(connectionState = ConnectionState.Searching),
            restarted.copy(connectionState = ConnectionState.Failed("lost")),
            restarted.copy(connectionState = ConnectionState.Unavailable("Bluetooth is off")),
            restarted.copy(devices = listOf(known, other)),
            restarted.copy(devices = listOf(other), selectedDeviceId = "gone"),
        ).forEach { state ->
            assertEquals(known, state.knownScannerToReconnect)
            assertTrue(state.canReconnectKnownScanner)
        }

        // Already connecting it: shown, but a second tap is not offered.
        val connecting = restarted.copy(
            devices = listOf(known),
            connectionState = ConnectionState.Connecting(known),
        )
        assertEquals(known, connecting.knownScannerToReconnect)
        assertFalse(connecting.canReconnectKnownScanner)
        // Connecting another scanner does not disable it.
        assertTrue(
            restarted.copy(connectionState = ConnectionState.Connecting(other))
                .canReconnectKnownScanner,
        )

        // A selection owns Connect/Reconnect; a connected scanner needs neither.
        assertEquals(
            null,
            restarted.copy(devices = listOf(known, other), selectedDeviceId = other.id)
                .knownScannerToReconnect,
        )
        assertEquals(
            null,
            restarted.copy(devices = listOf(known), selectedDeviceId = known.id)
                .knownScannerToReconnect,
        )
        val connected = restarted.copy(connectionState = ConnectionState.Connected(known))
        assertEquals(null, connected.knownScannerToReconnect)
        assertFalse(connected.canReconnectKnownScanner)

        // No remembered scanner (never connected, or forgotten after a GATT
        // mode switch): nothing is offered.
        assertEquals(null, SettingsUiState().knownScannerToReconnect)
        assertFalse(SettingsUiState().canReconnectKnownScanner)
        assertEquals(
            null,
            SettingsUiState(
                connectionState = ConnectionState.Failed(
                    jp.rimtty.codematch.scanner.api.ScannerFailureReasons.RESELECT_AFTER_GATT_SWITCH,
                ),
            ).knownScannerToReconnect,
        )
    }

    @Test
    fun newlyConnectedScannerBecomesTheSelection() {
        val origin = ScannerDevice(id = "origin", name = "Hyper 160B-4F5F-UNI")
        val successor = ScannerDevice(id = "successor", name = "HPRT-4F5F")
        val other = ScannerDevice(id = "other", name = "HPRT-636E")

        // The GATT switch connects the scanner under another identity.
        assertEquals(
            successor.id,
            selectedDeviceIdAfterConnectionChange(
                selectedDeviceId = origin.id,
                previous = ConnectionState.Connecting(successor),
                current = ConnectionState.Connected(successor),
            ),
        )
        // The known scanner connected on its own: it becomes the selection.
        assertEquals(
            successor.id,
            selectedDeviceIdAfterConnectionChange(null, ConnectionState.Idle, ConnectionState.Connected(successor)),
        )
        // A link replaced without a state in between still follows the scanner.
        assertEquals(
            successor.id,
            selectedDeviceIdAfterConnectionChange(
                origin.id,
                ConnectionState.Connected(origin),
                ConnectionState.Connected(successor),
            ),
        )
        // While the same scanner stays connected the operator may select
        // another row; that choice is kept.
        assertEquals(
            other.id,
            selectedDeviceIdAfterConnectionChange(
                other.id,
                ConnectionState.Connected(successor),
                ConnectionState.Connected(successor),
            ),
        )
        // No connection: the selection never changes.
        listOf(
            ConnectionState.Idle,
            ConnectionState.Searching,
            ConnectionState.Connecting(successor),
            ConnectionState.Failed("lost"),
            ConnectionState.Unavailable("Bluetooth is off"),
        ).forEach { current ->
            assertEquals(
                origin.id,
                selectedDeviceIdAfterConnectionChange(
                    origin.id,
                    ConnectionState.Connected(successor),
                    current,
                ),
            )
        }
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
