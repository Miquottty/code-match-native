package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.core.model.ScanLogEvent
import jp.rimtty.codematch.core.model.ScanLogEventKind
import jp.rimtty.codematch.core.model.ScanLogReason
import jp.rimtty.codematch.core.model.ScanLogSource
import jp.rimtty.codematch.core.model.ScanLogStep
import jp.rimtty.codematch.core.model.ScanCheckpointInputSource
import jp.rimtty.codematch.core.model.ScanCheckpointPhase
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.api.ScannerIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionCoordinatorTest {
    @Test
    fun repeatedReconnectDoesNotInterruptConnectingOrConfiguringAndFailureAllowsRetry() {
        val scanner = TestScanner()
        val coordinator = ScanSessionCoordinator(scanner)
        scanner.markConnecting()
        repeat(20) { assertEquals(false, coordinator.reconnectKnownDevice()) }
        scanner.markReady()
        scanner.configurationState = ConfigurationState.Configuring
        repeat(20) { assertEquals(false, coordinator.reconnectKnownDevice()) }
        assertEquals(0, scanner.reconnectCalls)
        assertEquals(0, scanner.disconnectCalls)
        scanner.markDisconnected()
        assertTrue(coordinator.reconnectKnownDevice())
        assertEquals(1, scanner.reconnectCalls)
    }

    private val qrPayload =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val barcodePayload = "BCJH-52-81GG@1N5X0C"

    // A different part number in the same series; a valid tag that must not
    // match the slip above.
    private val mismatchBarcodePayload = "BCJH-55-81GG@1KVQ0C"

    // Destination Molten, with a nine-character part number printed as a
    // 4-2-3 tag. The QR's trailing spaces are record data.
    private val moltenQrPayload =
        "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private val moltenShortPartBarcode = "PAF1-15-422@0NKD3C"

    // A Sawai label with a nine-character part number and a 4-2-3 tag
    // (2026-09-08 field labels, #129).
    private val sawaiShortPartQrPayload =
        "DAH4093540BCJH5281F   0002000000020000H      000000BHB01LHA28   0*"
    private val sawaiShortPartBarcode = "BCJH-52-81F@01R95K"

    // Destination Denso, whose product tag prints a 6-4 part number. The QR's
    // runs of spaces are blank item values.
    private val densoQrPayload =
        "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
    private val densoBarcodePayload = "860150-7722@1DZ50O"

    @Test
    fun restartOnMatchKeepsResultAndCountThenManualNextResumesQr() {
        val scanner = TestScanner().apply {
            requireExpectedFormatForPayloadReadiness = true
            markReady()
        }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH))
        coordinator.submitScanPayload(ScanPayload.code128(barcodePayload, InputSource.BLUETOOTH))
        val matched = coordinator.state
        assertEquals(ScanPhase.RESULT, matched.phase)
        assertEquals(1, matched.matchedCount)
        scanner.markDisconnected()
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        scanner.markConnecting()
        scanner.markReady()
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(matched.scan, coordinator.state.scan)
        assertEquals(matched.matchedCount, coordinator.state.matchedCount)
        assertNull(scanner.expectedFormat)
        coordinator.dispatch(ScanEvent.ManualNext)
        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun readyBluetoothIsSelectedAtSessionStart() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)

        coordinator.startSession()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun baselineReadyBluetoothStartsRestrictionBeforePayloadReady() {
        val scanner = TestScanner().apply {
            requireExpectedFormatForPayloadReadiness = true
            markReady()
        }
        val coordinator = ScanSessionCoordinator(scanner)

        assertTrue(scanner.isReadyToStartSession)
        assertTrue(!scanner.isReadyForScanning)

        coordinator.startSession()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
        assertTrue(scanner.isReadyForScanning)
    }

    @Test
    fun coordinatorUsesFanOutWithoutReplacingAnExistingScannerObserver() {
        val scanner = TestScanner().apply { markReady() }
        val legacyStates = mutableListOf<ConnectionState>()
        val settingsStates = mutableListOf<ConnectionState>()
        scanner.listener = object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                legacyStates += state
            }
        }
        val settingsObserver = object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                settingsStates += state
            }
        }
        assertTrue(scanner.addListener(settingsObserver))

        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        scanner.markDisconnected()

        assertTrue(legacyStates.contains(ConnectionState.Idle))
        assertTrue(settingsStates.contains(ConnectionState.Idle))
        assertEquals(InputSource.CAMERA, coordinator.inputSource)

        coordinator.dispose()
        assertTrue(scanner.removeListener(settingsObserver))
    }

    @Test
    fun explicitCameraChoiceWinsOverLaterReadyCallbacks() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()

        assertTrue(coordinator.selectInputSource(InputSource.CAMERA))
        scanner.markReady()

        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertTrue(coordinator.cameraWasSelectedByUser)
    }

    @Test
    fun disconnectFallsBackToCameraWithoutDiscardingCurrentQrStep() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        var fallbackRequests = 0
        coordinator.onBluetoothFallback = { fallbackRequests++ }
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH))
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)

        scanner.markDisconnected()

        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
        assertEquals(1, fallbackRequests)
    }

    @Test
    fun configurationFailureKeepsTypedIssueWhenBaselineRestoreMakesScannerReady() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        var fallbackIssue = ScannerIssue.NONE
        var fallbackRequests = 0
        coordinator.onBluetoothFallbackIssue = { fallbackIssue = it }
        coordinator.onBluetoothFallback = { fallbackRequests++ }
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH))

        scanner.markConfigurationFailed("scanner settings rejected")

        assertEquals(ScannerIssue.CONFIGURATION_FAILED, fallbackIssue)
        assertEquals(1, fallbackRequests)
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
        // The baseline restore reported Ready from inside the fallback; that
        // re-entrant callback does not flip the source back mid-fallback.
        assertEquals(ConfigurationState.Ready, scanner.configurationState)

        // #145: the next Ready returns to Bluetooth without an explicit
        // reconnect (it used to stay on camera for the rest of the session).
        scanner.markReady()
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(ScanFormat.CODE_128, scanner.expectedFormat)
    }

    @Test
    fun cameraChoiceLastsOneBoxThenTheNextBoxStartsOnBluetooth() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)

        assertTrue(coordinator.selectInputSource(InputSource.CAMERA))
        scanner.markReady()
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        completeBoxWithCamera(coordinator, 0L)
        assertEquals(ScanPhase.RESULT, coordinator.state.phase)
        assertEquals(InputSource.CAMERA, coordinator.inputSource)

        coordinator.manualNext()

        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(false, coordinator.cameraWasSelectedByUser)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun autoAdvanceToTheNextBoxAlsoEndsTheCameraChoice() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner, autoAdvanceEnabled = true)
        coordinator.startSession()
        assertTrue(coordinator.selectInputSource(InputSource.CAMERA))
        completeBoxWithCamera(coordinator, 0L)
        assertEquals(ScanPhase.RESULT, coordinator.state.phase)

        coordinator.tickAutoAdvance(10)

        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(false, coordinator.cameraWasSelectedByUser)
    }

    @Test
    fun qrRereadIsTheSameBoxAndKeepsTheCameraChoice() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        assertTrue(coordinator.selectInputSource(InputSource.CAMERA))
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.CAMERA, 0L))
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)

        coordinator.rereadQr()

        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertTrue(coordinator.cameraWasSelectedByUser)
    }

    @Test
    fun qrRereadWithoutACameraChoiceUsesBluetooth() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH))
        assertEquals(ScanFormat.CODE_128, scanner.expectedFormat)

        coordinator.rereadQr()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun configuringScannerIsSelectedAtStartAndWhenItConnects() {
        val configuring = TestScanner().apply { markConfiguring() }
        val atStart = ScanSessionCoordinator(configuring)
        atStart.startSession()
        assertEquals(InputSource.BLUETOOTH, atStart.inputSource)
        configuring.markReady()
        assertEquals(InputSource.BLUETOOTH, atStart.inputSource)
        assertEquals(ScanFormat.QR, configuring.expectedFormat)

        val scanner = TestScanner()
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        scanner.markConnecting()
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        scanner.markConfiguring()
        // Configuring shows the Bluetooth input instead of staying on camera.
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        scanner.markReady()
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun selectingBluetoothWhileConfiguringIsAccepted() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        assertTrue(coordinator.selectInputSource(InputSource.CAMERA))
        scanner.markConfiguring()
        assertEquals(InputSource.CAMERA, coordinator.inputSource)

        assertTrue(coordinator.selectInputSource(InputSource.BLUETOOTH))
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
    }

    @Test
    fun repeatedConfigurationFailuresStillReturnToBluetoothOnReady() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        repeat(5) {
            scanner.markConfigurationFailed("scanner settings rejected")
            assertEquals(InputSource.CAMERA, coordinator.inputSource)
            scanner.markReady()
            assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
            assertEquals(ScanFormat.QR, scanner.expectedFormat)
        }
        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
    }

    @Test
    fun aRestrictionThatNeverSucceedsPausesTheReturnOnlyUntilTheNextBox() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        scanner.failRestriction = true

        scanner.markConfigurationFailed("scanner settings rejected")
        repeat(ScanSessionCoordinator.MAX_CONSECUTIVE_CONFIGURATION_FALLBACKS - 1) {
            // Every Ready retries Bluetooth; the restriction fails again.
            scanner.markReady()
            assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
            scanner.rejectRestriction()
            assertEquals(InputSource.CAMERA, coordinator.inputSource)
        }
        val attempts = scanner.restrictionAttempts
        scanner.markReady()
        // The streak pauses the automatic return instead of looping.
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(attempts, scanner.restrictionAttempts)

        scanner.failRestriction = false
        completeBoxWithCamera(coordinator, 0L)
        coordinator.manualNext()
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun foregroundReturnReselectsAConnectedScanner() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()

        coordinator.onBackgrounded()
        scanner.markDisconnected()
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        scanner.markReady()
        // Nothing is restarted while the scan screen is in the background.
        assertEquals(InputSource.CAMERA, coordinator.inputSource)

        coordinator.onForegrounded()
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun scanTabReturnSelectsBluetoothWhileTheRestoreWriteStillConfigures() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        coordinator.onBackgrounded()
        // Leaving the tab restores the scanner baseline: momentarily not ready.
        scanner.markConfiguring()

        coordinator.onForegrounded()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        scanner.markReady()
        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun restoredCameraFallbackReturnsToAConnectedScannerOnRestore() {
        val scanner = TestScanner().apply { markConfiguring() }
        val coordinator = ScanSessionCoordinator(
            scanner = scanner,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_CODE_128,
                qrPayload = qrPayload,
                matchedCount = 2,
                inputSource = ScanCheckpointInputSource.CAMERA,
            ),
        )

        coordinator.startSession()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
    }

    private fun completeBoxWithCamera(coordinator: ScanSessionCoordinator, start: Long) {
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.CAMERA, start))
        coordinator.submitScanPayload(ScanPayload.code128(barcodePayload, InputSource.CAMERA, start + 1_000L))
        coordinator.submitScanPayload(ScanPayload.code128(barcodePayload, InputSource.CAMERA, start + 1_250L))
    }

    @Test
    fun explicitAsynchronousReconnectAllowsLaterReadyPromotion() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH))
        scanner.markConfigurationFailed("scanner settings rejected")
        scanner.reconnectSynchronously = false

        assertTrue(coordinator.reconnectKnownDevice())
        assertEquals(InputSource.CAMERA, coordinator.inputSource)

        scanner.markReady()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
    }

    @Test
    fun manualCameraSelectionDoesNotRequestAutomaticFallbackStart() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        var fallbackRequests = 0
        coordinator.onBluetoothFallback = { fallbackRequests++ }
        coordinator.startSession()

        assertTrue(coordinator.selectInputSource(InputSource.CAMERA))

        assertEquals(0, fallbackRequests)
    }

    @Test
    fun cameraCode128UsesStrictStabilizationBeforeDispatch() {
        val coordinator = ScanSessionCoordinator(TestScanner())
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, timestampMillis = 0L))

        assertNull(
            coordinator.submitScanPayload(
                ScanPayload.code128(barcodePayload, timestampMillis = 250L),
            ),
        )
        val accepted = coordinator.submitScanPayload(
            ScanPayload.code128(barcodePayload, timestampMillis = 1_749L),
        )

        assertEquals(ScanPhase.RESULT, accepted?.state?.phase)
    }

    @Test
    fun cameraCode128OutsideBusinessFormatIsRejectedWithoutStabilization() {
        val coordinator = ScanSessionCoordinator(TestScanner())
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, timestampMillis = 0L))

        val rejected = coordinator.submitScanPayload(
            ScanPayload.code128("HELLO-WORLD", timestampMillis = 300L),
        )
        assertTrue(rejected?.effects?.single() is ScanEffect.InvalidScan)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)

        // The rejected value never became a stabilizer candidate, so a valid
        // tag still needs its own two observations before comparison.
        assertNull(
            coordinator.submitScanPayload(
                ScanPayload.code128(barcodePayload, timestampMillis = 400L),
            ),
        )
        val accepted = coordinator.submitScanPayload(
            ScanPayload.code128(barcodePayload, timestampMillis = 500L),
        )
        assertEquals(ScanPhase.RESULT, accepted?.state?.phase)
    }

    @Test
    fun backgroundStopsScannerAndForegroundResumesCurrentFormat() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()

        coordinator.onBackgrounded()
        assertTrue(coordinator.isBackgrounded)
        assertNull(scanner.expectedFormat)
        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)

        coordinator.onForegrounded()
        assertTrue(!coordinator.isBackgrounded)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
    }

    @Test
    fun delayedCameraPayloadIsIgnoredWhileBackgrounded() {
        val coordinator = ScanSessionCoordinator(TestScanner())
        coordinator.startSession()
        coordinator.onBackgrounded()

        val result = coordinator.submitScanPayload(
            ScanPayload.qr(qrPayload, timestampMillis = 100L),
        )

        assertNull(result)
        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
        assertNull(coordinator.state.qrPayload)
    }

    @Test
    fun coordinatorCanStartWithRestoredMatchCount() {
        val coordinator = ScanSessionCoordinator(TestScanner(), existingMatchedCount = 4)

        coordinator.startSession()

        assertEquals(4, coordinator.state.matchedCount)
    }

    @Test
    fun restoredWaitingCode128KeepsStepAndFallsBackToCameraWhenBluetoothIsUnavailable() {
        val coordinator = ScanSessionCoordinator(
            scanner = TestScanner(),
            existingMatchedCount = 3,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_CODE_128,
                qrPayload = qrPayload,
                matchedCount = 3,
                inputSource = ScanCheckpointInputSource.BLUETOOTH,
            ),
        )

        coordinator.startSession()

        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
        assertEquals(3, coordinator.state.matchedCount)
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(InputSource.CAMERA, coordinator.state.inputSource)
        assertNull(coordinator.lastEffects.filterIsInstance<ScanEffect.RecordMatch>().firstOrNull())
    }

    @Test
    fun restoredBluetoothFallbackPromotesBackWhenReadyWithoutLosingQr() {
        val scanner = TestScanner()
        val coordinator = ScanSessionCoordinator(
            scanner = scanner,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_CODE_128,
                qrPayload = qrPayload,
                matchedCount = 2,
                inputSource = ScanCheckpointInputSource.BLUETOOTH,
            ),
        )

        coordinator.startSession()
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(qrPayload, coordinator.state.qrPayload)

        scanner.markReady()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
        assertEquals(2, coordinator.state.matchedCount)
    }

    @Test
    fun restoredBluetoothWaitsForKnownDeviceConnectionInsteadOfFallingBackImmediately() {
        val scanner = TestScanner().apply { markConnecting() }
        var fallbackRequests = 0
        val coordinator = ScanSessionCoordinator(
            scanner = scanner,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_QR,
                matchedCount = 1,
                inputSource = ScanCheckpointInputSource.BLUETOOTH,
            ),
        )
        coordinator.onBluetoothFallback = { fallbackRequests++ }

        coordinator.startSession()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(InputSource.BLUETOOTH, coordinator.state.inputSource)
        assertEquals(0, fallbackRequests)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)

        scanner.markReady()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(0, fallbackRequests)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun explicitRestoredCameraChoiceStaysCameraAfterBluetoothBecomesReady() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(
            scanner = scanner,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_QR,
                inputSource = ScanCheckpointInputSource.CAMERA,
                cameraWasSelectedByUser = true,
            ),
        )

        coordinator.startSession()
        scanner.markReady()

        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertTrue(coordinator.cameraWasSelectedByUser)
    }

    @Test
    fun selectingAlreadyActiveCameraPersistsIntentAndSurvivesReadyCallback() {
        val scanner = TestScanner()
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()

        // The source is already camera, but this selection changes the policy
        // bit that must survive process recreation.
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        coordinator.selectInputSource(InputSource.CAMERA)
        val checkpoint = coordinator.state.toScanSessionCheckpoint(
            sessionId = "session",
            cameraWasSelectedByUser = coordinator.cameraWasSelectedByUser,
        )
        assertTrue(checkpoint?.cameraWasSelectedByUser == true)

        val restoredScanner = TestScanner()
        val restored = ScanSessionCoordinator(
            scanner = restoredScanner,
            restoredCheckpoint = checkpoint,
        )
        restored.startSession()
        restoredScanner.markReady()

        assertEquals(InputSource.CAMERA, restored.inputSource)
        assertTrue(restored.cameraWasSelectedByUser)
    }

    @Test
    fun restoredResultDoesNotReplayMatchOrRestartAutoAdvance() {
        val coordinator = ScanSessionCoordinator(
            scanner = TestScanner(),
            autoAdvanceEnabled = true,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.RESULT,
                qrPayload = qrPayload,
                barcodePayload = barcodePayload,
                result = MatchResult.MATCH,
                matchedCount = 1,
                inputSource = ScanCheckpointInputSource.CAMERA,
            ),
        )

        val reduction = coordinator.startSession()

        assertEquals(ScanPhase.RESULT, coordinator.state.phase)
        assertEquals(MatchResult.MATCH, coordinator.state.result)
        assertNull(coordinator.state.autoAdvanceSecondsRemaining)
        assertTrue(reduction.effects.none { it is ScanEffect.RecordMatch })
        assertTrue(reduction.effects.none { it is ScanEffect.AutoAdvanceStarted })
    }

    @Test
    fun unsupportedCheckpointFallsBackToWaitingQrWithExistingCount() {
        val coordinator = ScanSessionCoordinator(
            scanner = TestScanner(),
            existingMatchedCount = 5,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.RESULT,
                qrPayload = "qr",
                barcodePayload = "barcode",
                result = MatchResult.MATCH,
                matchedCount = 5,
                version = ScanSessionCheckpoint.CURRENT_VERSION + 1,
            ),
        )

        coordinator.startSession()

        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
        assertEquals(5, coordinator.state.matchedCount)
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
    }

    @Test
    fun rereadQrCheckpointRestoresWaitingQrInsteadOfTheOldAcceptedQr() {
        val coordinator = ScanSessionCoordinator(TestScanner())
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload))
        coordinator.rereadQr()

        val checkpoint = coordinator.state.toScanSessionCheckpoint("session")
        val restored = ScanSessionCoordinator(
            scanner = TestScanner(),
            restoredCheckpoint = checkpoint,
        )
        restored.startSession()

        assertEquals(ScanPhase.WAITING_QR, restored.state.phase)
        assertNull(restored.state.qrPayload)
    }

    @Test
    fun manualNextCheckpointRestoresWaitingQrWithoutReplayingTerminalMatch() {
        val coordinator = ScanSessionCoordinator(TestScanner())
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload))
        coordinator.submitScanPayload(ScanPayload.code128(barcodePayload, timestampMillis = 300L))
        coordinator.submitScanPayload(ScanPayload.code128(barcodePayload, timestampMillis = 400L))
        assertEquals(ScanPhase.RESULT, coordinator.state.phase)
        coordinator.manualNext()

        val checkpoint = coordinator.state.toScanSessionCheckpoint("session")
        val restored = ScanSessionCoordinator(
            scanner = TestScanner(),
            restoredCheckpoint = checkpoint,
        )
        val start = restored.startSession()

        assertEquals(ScanPhase.WAITING_QR, restored.state.phase)
        assertEquals(1, restored.state.matchedCount)
        assertTrue(start.effects.none { it is ScanEffect.RecordMatch })
    }

    @Test
    fun restoredCheckpointDestinationSeedsTheLock() {
        val coordinator = ScanSessionCoordinator(
            scanner = TestScanner(),
            existingMatchedCount = 1,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_QR,
                matchedCount = 1,
                destination = Destination.MOLTEN,
            ),
            // The checkpoint is the freshest record of the lock and wins over
            // a session row that has not been updated yet.
            sessionDestination = Destination.SAWAI,
        )

        coordinator.startSession()

        assertEquals(Destination.MOLTEN, coordinator.state.destination)
    }

    @Test
    fun sessionDestinationSeedsTheLockWhenCheckpointHasNone() {
        val coordinator = ScanSessionCoordinator(
            scanner = TestScanner(),
            existingMatchedCount = 2,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_QR,
                matchedCount = 2,
            ),
            sessionDestination = Destination.MOLTEN,
        )

        coordinator.startSession()

        assertEquals(Destination.MOLTEN, coordinator.state.destination)
    }

    @Test
    fun recordedBoxesDeriveTheLockWhenNothingElseIsStored() {
        val coordinator = ScanSessionCoordinator(
            scanner = TestScanner(),
            existingMatchedCount = 1,
            recordedBoxes = listOfNotNull(
                RecordedBox.fromPayloads(moltenQrPayload, moltenShortPartBarcode),
            ),
        )

        coordinator.startSession()

        assertEquals(Destination.MOLTEN, coordinator.state.destination)
        assertEquals(1, coordinator.state.recordedBoxes.size)
    }

    @Test
    fun cameraFourTwoThreeTagGoesThroughStabilizerInSawaiAndMoltenSessions() {
        val molten = ScanSessionCoordinator(TestScanner())
        molten.startSession()
        molten.submitScanPayload(ScanPayload.qr(moltenQrPayload, timestampMillis = 0L))
        assertEquals(Destination.MOLTEN, molten.state.destination)

        // A 4-2-3 tag is a valid Molten product tag, so it takes the strict
        // two-observation path instead of being rejected on the first frame.
        assertNull(
            molten.submitScanPayload(
                ScanPayload.code128(moltenShortPartBarcode, timestampMillis = 300L),
            ),
        )
        val accepted = molten.submitScanPayload(
            ScanPayload.code128(moltenShortPartBarcode, timestampMillis = 400L),
        )
        assertEquals(ScanPhase.RESULT, accepted?.state?.phase)
        assertEquals(MatchResult.MATCH, accepted?.state?.result)

        // A Sawai label can carry a nine-character part number too (#129), so
        // its 4-2-3 tag takes the same two-observation path in a Sawai session.
        val sawai = ScanSessionCoordinator(TestScanner())
        sawai.startSession()
        sawai.submitScanPayload(ScanPayload.qr(sawaiShortPartQrPayload, timestampMillis = 0L))
        assertEquals(Destination.SAWAI, sawai.state.destination)
        assertNull(
            sawai.submitScanPayload(
                ScanPayload.code128(sawaiShortPartBarcode, timestampMillis = 300L),
            ),
        )
        val sawaiAccepted = sawai.submitScanPayload(
            ScanPayload.code128(sawaiShortPartBarcode, timestampMillis = 400L),
        )
        assertEquals(ScanPhase.RESULT, sawaiAccepted?.state?.phase)
        assertEquals(MatchResult.MATCH, sawaiAccepted?.state?.result)
    }

    @Test
    fun cameraSixFourTagGoesThroughStabilizerOnlyInDensoSession() {
        val denso = ScanSessionCoordinator(TestScanner())
        denso.startSession()
        denso.submitScanPayload(ScanPayload.qr(densoQrPayload, timestampMillis = 0L))
        assertEquals(Destination.DENSO, denso.state.destination)

        // A 6-4 tag is a valid Denso product tag, so it takes the strict
        // two-observation path instead of being rejected on the first frame.
        assertNull(
            denso.submitScanPayload(
                ScanPayload.code128(densoBarcodePayload, timestampMillis = 300L),
            ),
        )
        val accepted = denso.submitScanPayload(
            ScanPayload.code128(densoBarcodePayload, timestampMillis = 400L),
        )
        assertEquals(ScanPhase.RESULT, accepted?.state?.phase)
        assertEquals(MatchResult.MATCH, accepted?.state?.result)

        val sawai = ScanSessionCoordinator(TestScanner())
        sawai.startSession()
        sawai.submitScanPayload(ScanPayload.qr(qrPayload, timestampMillis = 0L))
        val rejected = sawai.submitScanPayload(
            ScanPayload.code128(densoBarcodePayload, timestampMillis = 300L),
        )
        assertTrue(rejected?.effects?.single() is ScanEffect.InvalidScan)
        assertEquals(ScanPhase.WAITING_CODE_128, sawai.state.phase)
    }


    @Test
    fun scanLogRecordsAcceptedQrBarcodeMatchAndSessionEnd() {
        val scanner = TestScanner().apply { markReady() }
        val log = RecordingScanLog()
        val coordinator = ScanSessionCoordinator(scanner, scanLogRecorder = log)

        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH, 1_000L))
        coordinator.submitScanPayload(
            ScanPayload.code128(barcodePayload, InputSource.BLUETOOTH, 2_000L),
        )

        assertEquals(
            listOf(
                ScanLogEventKind.QR_ACCEPTED,
                ScanLogEventKind.BARCODE_ACCEPTED,
                ScanLogEventKind.MATCH,
            ),
            log.events.map { it.event },
        )
        val qrAccepted = log.events[0]
        assertEquals(ScanLogSource.BLUETOOTH, qrAccepted.source)
        assertEquals(ScanLogStep.QR, qrAccepted.step)
        assertEquals(qrPayload, qrAccepted.qrPayload)
        assertNull(qrAccepted.barcodePayload)
        assertEquals(Destination.SAWAI, qrAccepted.destination)
        // The host owns the session id; the coordinator never invents one.
        assertNull(qrAccepted.sessionId)

        val barcodeAccepted = log.events[1]
        assertEquals(ScanLogStep.BARCODE, barcodeAccepted.step)
        assertEquals(barcodePayload, barcodeAccepted.barcodePayload)
        assertNull(barcodeAccepted.qrPayload)

        val match = log.events[2]
        assertEquals(ScanLogStep.RESULT, match.step)
        assertEquals(qrPayload, match.qrPayload)
        assertEquals(barcodePayload, match.barcodePayload)
        assertEquals("BCJH-52-81GG", match.code)
        assertEquals(1, match.boxNumber)
        assertNull(match.reason)
        assertNull(match.message)

        coordinator.endSession()

        val ended = log.events.last()
        assertEquals(ScanLogEventKind.SESSION_END, ended.event)
        assertEquals(ScanLogStep.NONE, ended.step)
        // Ending resets the source and clears the lock, so both are read from
        // the state the session had before the reduction.
        assertEquals(ScanLogSource.BLUETOOTH, ended.source)
        assertEquals(Destination.SAWAI, ended.destination)
    }

    @Test
    fun scanLogRecordsMismatchAndDuplicateWithTheirPartNumberButNoBoxNumber() {
        val scanner = TestScanner().apply { markReady() }
        val log = RecordingScanLog()
        val coordinator = ScanSessionCoordinator(scanner, scanLogRecorder = log)
        coordinator.startSession()

        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH, 1_000L))
        coordinator.submitScanPayload(
            ScanPayload.code128(mismatchBarcodePayload, InputSource.BLUETOOTH, 2_000L),
        )

        val mismatch = log.events.last()
        assertEquals(ScanLogEventKind.MISMATCH, mismatch.event)
        assertEquals(mismatchBarcodePayload, mismatch.barcodePayload)
        assertEquals("BCJH-55-81GG", mismatch.code)
        assertNull(mismatch.boxNumber)

        coordinator.manualNext()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH, 3_000L))
        coordinator.submitScanPayload(
            ScanPayload.code128(barcodePayload, InputSource.BLUETOOTH, 4_000L),
        )
        assertEquals(ScanLogEventKind.MATCH, log.events.last().event)

        coordinator.manualNext()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH, 5_000L))
        coordinator.submitScanPayload(
            ScanPayload.code128(barcodePayload, InputSource.BLUETOOTH, 6_000L),
        )

        val duplicate = log.events.last()
        assertEquals(ScanLogEventKind.DUPLICATE, duplicate.event)
        assertEquals("BCJH-52-81GG", duplicate.code)
        assertNull(duplicate.boxNumber)
    }

    @Test
    fun scanLogRecordsEveryRejectionReasonWithTheValueThatCausedIt() {
        val scanner = TestScanner().apply { markReady() }
        val log = RecordingScanLog()
        val coordinator = ScanSessionCoordinator(scanner, scanLogRecorder = log)

        // A connected scanner is promoted only once a session starts, so this
        // first callback is still a camera one.
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.CAMERA, 1_000L))
        coordinator.startSession()
        coordinator.submitScanPayload(
            ScanPayload.code128(barcodePayload, InputSource.BLUETOOTH, 2_000L),
        )
        coordinator.submitScanPayload(ScanPayload.qr("", InputSource.BLUETOOTH, 3_000L))
        coordinator.submitScanPayload(ScanPayload.qr("SHORT", InputSource.BLUETOOTH, 4_000L))
        coordinator.submitScanPayload(ScanPayload.qr("A".repeat(70), InputSource.BLUETOOTH, 5_000L))
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH, 6_000L))
        coordinator.submitScanPayload(
            ScanPayload.code128("NOT-A-TAG", InputSource.BLUETOOTH, 7_000L),
        )
        // Re-reading the QR keeps the destination lock, so a slip of another
        // destination is still refused.
        coordinator.rereadQr()
        coordinator.submitScanPayload(
            ScanPayload.qr(moltenQrPayload, InputSource.BLUETOOTH, 8_000L),
        )

        val rejections = log.events.filter { it.event == ScanLogEventKind.REJECTED }
        assertEquals(
            listOf(
                ScanLogReason.SESSION_NOT_STARTED,
                ScanLogReason.WRONG_ORDER,
                ScanLogReason.EMPTY,
                ScanLogReason.INCOMPLETE,
                ScanLogReason.OVERLONG,
                ScanLogReason.INVALID,
                ScanLogReason.WRONG_DESTINATION,
            ),
            rejections.map { it.reason },
        )
        assertEquals(ScanLogStep.NONE, rejections[0].step)
        assertEquals(qrPayload, rejections[0].qrPayload)
        // The value is filed under the step it was judged in: a Code 128 that
        // arrives while a QR is expected is recorded as the QR that was read.
        assertEquals(barcodePayload, rejections[1].qrPayload)
        assertNull(rejections[1].barcodePayload)
        assertEquals("", rejections[2].qrPayload)
        assertEquals("SHORT", rejections[3].qrPayload)
        assertEquals("A".repeat(70), rejections[4].qrPayload)
        assertEquals(ScanLogStep.BARCODE, rejections[5].step)
        assertEquals("NOT-A-TAG", rejections[5].barcodePayload)
        assertNull(rejections[5].qrPayload)
        assertEquals(moltenQrPayload, rejections[6].qrPayload)
        assertEquals(Destination.SAWAI, rejections[6].destination)
    }

    @Test
    fun scanLogRecordsDroppedSourceConfirmationCandidateAndResultCallbacks() {
        val bluetoothScanner = TestScanner().apply { markReady() }
        val bluetoothLog = RecordingScanLog()
        val bluetooth = ScanSessionCoordinator(bluetoothScanner, scanLogRecorder = bluetoothLog)
        bluetooth.startSession()

        bluetooth.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.CAMERA, 1_000L))

        val dropped = bluetoothLog.events.single()
        assertEquals(ScanLogEventKind.REJECTED, dropped.event)
        assertEquals(ScanLogReason.SOURCE_MISMATCH, dropped.reason)
        assertEquals(ScanLogSource.CAMERA, dropped.source)
        assertEquals(ScanLogStep.QR, dropped.step)
        assertEquals(qrPayload, dropped.qrPayload)

        val cameraLog = RecordingScanLog()
        val camera = ScanSessionCoordinator(TestScanner(), scanLogRecorder = cameraLog)
        camera.startSession()
        camera.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.CAMERA, 1_000L))
        camera.submitScanPayload(ScanPayload.code128(barcodePayload, InputSource.CAMERA, 2_000L))

        assertEquals(
            listOf(ScanLogEventKind.QR_ACCEPTED, ScanLogEventKind.BARCODE_CANDIDATE),
            cameraLog.events.map { it.event },
        )
        val candidate = cameraLog.events.last()
        assertEquals(ScanLogStep.BARCODE, candidate.step)
        assertEquals(barcodePayload, candidate.barcodePayload)
        assertNull(candidate.reason)

        camera.submitScanPayload(ScanPayload.code128(barcodePayload, InputSource.CAMERA, 2_500L))
        assertEquals(ScanLogEventKind.MATCH, cameraLog.events.last().event)

        camera.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.CAMERA, 5_000L))

        val swallowed = cameraLog.events.last()
        assertEquals(ScanLogEventKind.REJECTED, swallowed.event)
        assertEquals(ScanLogReason.RESULT_PENDING, swallowed.reason)
        assertEquals(ScanLogStep.RESULT, swallowed.step)
        assertEquals(qrPayload, swallowed.qrPayload)
    }

    private class RecordingScanLog : ScanLogRecorder {
        val events = mutableListOf<ScanLogEvent>()

        override fun record(event: ScanLogEvent) {
            events += event
        }
    }

    private class TestScanner : ExternalScanner {
        private val device = ScannerDevice("test", "Test scanner")
        override var devices: List<ScannerDevice> = listOf(device)
        override var connectionState: ConnectionState = ConnectionState.Idle
        override var configurationState: ConfigurationState = ConfigurationState.Unavailable
        override var diagnosticEvents: List<jp.rimtty.codematch.scanner.api.DiagnosticEvent> = emptyList()
        override var expectedFormat: ScanFormat? = null
        override var listener: ExternalScannerListener? = null
        var reconnectSynchronously: Boolean = true
        var reconnectCalls = 0
        var disconnectCalls = 0
        var requireExpectedFormatForPayloadReadiness: Boolean = false
        override val isReadyForScanning: Boolean
            get() = super.isReadyForScanning &&
                (!requireExpectedFormatForPayloadReadiness || expectedFormat != null)

        override fun startDiscovery(): Boolean = true
        override fun stopDiscovery(): Boolean = true

        override fun connect(device: ScannerDevice): Boolean {
            markReady()
            return true
        }

        override fun disconnect(): Boolean {
            disconnectCalls++
            markDisconnected()
            return true
        }

        override fun reconnectKnownDevice(): Boolean {
            reconnectCalls++
            if (reconnectSynchronously) markReady()
            return true
        }

        /** Every restriction write fails (a scanner that keeps rejecting it). */
        var failRestriction: Boolean = false
        var restrictionAttempts = 0

        override fun setExpectedFormat(format: ScanFormat?): Boolean {
            expectedFormat = format
            if (format == null && connectionState.connectedDevice != null) {
                configurationState = ConfigurationState.Ready
            }
            if (format != null) restrictionAttempts++
            if (format != null && failRestriction && connectionState.connectedDevice != null) {
                // The write is pending; rejectRestriction() fails it later,
                // asynchronously like a real BLE callback.
                configurationState = ConfigurationState.Configuring
            }
            listener?.onConfigurationStateChanged(configurationState)
            return true
        }

        fun rejectRestriction() = markConfigurationFailed("restriction rejected")

        fun markConfiguring() {
            connectionState = ConnectionState.Connected(device)
            configurationState = ConfigurationState.Configuring
            listener?.onConnectionStateChanged(connectionState)
            listener?.onConfigurationStateChanged(configurationState)
        }

        fun markReady() {
            connectionState = ConnectionState.Connected(device)
            configurationState = ConfigurationState.Ready
            listener?.onConnectionStateChanged(connectionState)
            listener?.onConfigurationStateChanged(configurationState)
        }

        fun markConnecting() {
            connectionState = ConnectionState.Connecting(device)
            configurationState = ConfigurationState.Unavailable
            listener?.onConnectionStateChanged(connectionState)
            listener?.onConfigurationStateChanged(configurationState)
        }

        fun markDisconnected() {
            connectionState = ConnectionState.Idle
            configurationState = ConfigurationState.Unavailable
            listener?.onConfigurationStateChanged(configurationState)
            listener?.onConnectionStateChanged(connectionState)
        }

        fun markConfigurationFailed(reason: String) {
            configurationState = ConfigurationState.Failed(reason)
            listener?.onConfigurationStateChanged(configurationState)
        }
    }
}
