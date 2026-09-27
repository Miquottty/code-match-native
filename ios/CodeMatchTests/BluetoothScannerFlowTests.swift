import XCTest
import AVFoundation
import Combine
@testable import CodeMatch

@MainActor
final class BluetoothScannerFlowTests: XCTestCase {
    private var originalLanguageRawValue: String?

    override func setUp() {
        super.setUp()
        originalLanguageRawValue = UserDefaults.standard.string(forKey: AppLanguage.storageKey)
        UserDefaults.standard.set(AppLanguage.japanese.rawValue, forKey: AppLanguage.storageKey)
    }

    override func tearDown() {
        if let originalLanguageRawValue {
            UserDefaults.standard.set(originalLanguageRawValue, forKey: AppLanguage.storageKey)
        } else {
            UserDefaults.standard.removeObject(forKey: AppLanguage.storageKey)
        }
        super.tearDown()
    }

    func testSuccessfulMatchCountsDownAndAutomaticallyStartsNextScan() async {
        let context = makeContext(
            autoAdvanceEnabled: true,
            autoAdvanceTickDuration: .milliseconds(40)
        )
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        let countdownUpdated = expectation(description: "countdown updates visibly")
        let nextScanStarted = expectation(description: "next QR scan starts")
        let countdownObservation = context.viewModel.$autoAdvanceSecondsRemaining
            .dropFirst()
            .sink { remaining in
                if remaining == 2 { countdownUpdated.fulfill() }
            }
        let stepObservation = context.viewModel.$step
            .dropFirst()
            .sink { step in
                if step == .qr { nextScanStarted.fulfill() }
            }

        context.viewModel.runDemo(shouldMatch: true)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 3)

        await fulfillment(of: [countdownUpdated, nextScanStarted], timeout: 2, enforceOrder: true)
        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertNil(context.viewModel.autoAdvanceSecondsRemaining)
        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        withExtendedLifetime((countdownObservation, stepObservation)) {}
    }

    func testTurningAutoAdvanceOffCancelsCountdownAndKeepsResultVisible() async {
        let context = makeContext(
            autoAdvanceEnabled: true,
            autoAdvanceTickDuration: .milliseconds(30)
        )
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: true)
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 3)

        context.viewModel.setAutoAdvanceEnabled(false)
        try? await Task.sleep(for: .milliseconds(120))

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertNil(context.viewModel.autoAdvanceSecondsRemaining)
    }

    func testChangingCountdownToFiveSecondsRestartsVisibleCount() {
        let context = makeContext(autoAdvanceEnabled: true)
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: true)
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 3)

        context.viewModel.setAutoAdvanceDelay(.fiveSeconds)

        XCTAssertEqual(context.viewModel.autoAdvanceDelay, .fiveSeconds)
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 5)
        context.viewModel.setAutoAdvanceEnabled(false)
    }

    func testChangingCountdownToOneSecondRestartsVisibleCount() {
        let context = makeContext(autoAdvanceEnabled: true)
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: true)
        context.viewModel.setAutoAdvanceDelay(.oneSecond)

        XCTAssertEqual(context.viewModel.autoAdvanceDelay, .oneSecond)
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 1)
        context.viewModel.setAutoAdvanceEnabled(false)
    }

    func testMismatchNeverStartsAutoAdvanceCountdown() {
        let context = makeContext(autoAdvanceEnabled: true)
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: false)

        XCTAssertEqual(context.viewModel.step, .result(.mismatch))
        XCTAssertNil(context.viewModel.autoAdvanceSecondsRemaining)
    }

    func testSuccessfulPayloadCannotBeCountedTwiceInActiveSession() {
        let context = makeContext(autoAdvanceEnabled: true)
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: true)
        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)

        // カメラ入力の経路なので、テストを決定的にするためカメラ再開は明示的に抑止する。
        context.viewModel.reset(automaticallyStartScanning: false)
        context.viewModel.runDemo(shouldMatch: true)

        XCTAssertEqual(context.viewModel.step, .result(.duplicate))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 0)
        XCTAssertNil(context.viewModel.autoAdvanceSecondsRemaining)
        XCTAssertTrue(context.viewModel.message.contains("すでに照合済み"))
        XCTAssertTrue(context.viewModel.message.contains("照合件数に加えていません"))
    }

    func testManualNextRestartsCameraWhenInputIsCamera() {
        let context = makeContext()
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: true)
        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        // 手動の「次のコードを照合」。カメラ入力なので既定でカメラが再開する。
        context.viewModel.reset()

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.isCameraStarting)
        XCTAssertEqual(context.viewModel.expectedCode, .qr)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        XCTAssertNil(context.viewModel.autoAdvanceSecondsRemaining)
    }

    func testManualNextKeepsBluetoothWaitingForQRWithoutCamera() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)

        // BCST-47が接続されている間は、手動の「次のコードを照合」でもカメラは起動しない。
        context.viewModel.reset()

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertFalse(context.viewModel.isCameraStarting)
        XCTAssertFalse(context.viewModel.isCameraRunning)
        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertTrue(context.viewModel.message.contains("BCST-47"))
    }

    func testDifferentBoxQRsWithSameBarcodeAreBothCounted() async {
        let context = makeContext()
        defer { context.cleanup() }
        let firstBoxQR = "DAAL134150BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
        let secondBoxQR = "DAAL134140BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
        let sharedBarcode = "BCJH-55-81GG@1KVQ0C"

        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(firstBoxQR)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(sharedBarcode)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)

        context.viewModel.reset()
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(secondBoxQR)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(sharedBarcode)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 2)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 2)
    }

    func testBluetoothRejectsCode128BeforeQRWithoutAdvancing() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertTrue(context.service.isReadyForScanning)
        XCTAssertEqual(context.service.expectedCode, .qr)
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.qrValue.isEmpty)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("先に"))

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)

        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
    }

    func testBluetoothRejectsQRWhileWaitingForCode128() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))

        let otherQR = "DAYA005100DFR55581GA  0001000000010000Y      000000BYBYTLYB16   0*"
        context.service.simulateScan(otherQR)

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("Code 128"))
        XCTAssertTrue(context.service.isReadyForScanning)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
    }

    func testRereadQRClearsWrongQRAndReturnsBluetoothToQRWaiting() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)

        context.viewModel.rereadQR()

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.qrValue.isEmpty)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)
        XCTAssertTrue(context.viewModel.message.contains("別の"))
    }

    func testBluetoothQRThenBarcodeCompletesMatchImmediately() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        var configurationUpdates: [BluetoothScannerConfigurationState] = []
        let configurationObservation = context.service.$configurationState
            .dropFirst()
            .sink { configurationUpdates.append($0) }

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertFalse(context.viewModel.isCameraRunning)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload + "\r\n")
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.qrValue.hasSuffix("   0*"))

        // サービス側の短時間デバウンスを越えて同じQRが再通知されても、
        // 次ステップのCode 128として誤確定しない。
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload + "\r\n")
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertTrue(configurationUpdates.isEmpty)
        XCTAssertTrue(context.service.isReadyForScanning)

        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload + "\r")

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
        XCTAssertTrue(configurationUpdates.isEmpty)
        withExtendedLifetime(configurationObservation) {}
    }

    func testConnectedBluetoothBecomesDefaultButManualCameraSelectionIsPreserved() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])

        context.viewModel.handleBluetoothConnectionState(context.service.state)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertTrue(context.viewModel.message.contains("BCST-47"))

        context.viewModel.selectInputSource(.camera)
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.viewModel.inputSource, .camera)
    }

    func testBluetoothReadyRestoresInstructionAfterConfigurationMessage() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.viewModel.handleBluetoothConfigurationState(.configuring)
        XCTAssertTrue(context.viewModel.message.contains("設定しています"))

        context.viewModel.handleBluetoothConfigurationState(.ready)

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertTrue(context.viewModel.message.contains("QRコードを読み取ってください"))
        XCTAssertFalse(context.viewModel.message.contains("設定しています"))
    }

    func testBluetoothBackgroundRestoresBaselineAndForegroundReappliesCurrentStep() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)

        context.viewModel.prepareForBackground()

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertNil(context.service.expectedCode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .unrestricted)

        context.viewModel.resumeAfterForeground()

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
    }

    func testInactivePhaseKeepsBluetoothSymbologyRestriction() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)

        var configurationUpdates: [BluetoothScannerConfigurationState] = []
        let observation = context.service.$configurationState
            .dropFirst()
            .sink { configurationUpdates.append($0) }

        // Control Centerや通知センターなどの一時的な非アクティブ化では、
        // スキャナーの読み取り設定を復元・再制限しない。
        context.viewModel.prepareForInactive()
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)

        context.viewModel.resumeAfterForeground()
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertTrue(configurationUpdates.isEmpty)
        XCTAssertTrue(context.service.isReadyForScanning)
        withExtendedLifetime(observation) {}
    }

    func testActivePhaseRestartsCameraStoppedByInactivePhase() async {
        let context = makeContext()
        defer { context.cleanup() }
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        context.viewModel.cameraScannerDidStart(context.viewModel.camera)
        XCTAssertTrue(context.viewModel.isCameraRunning)

        context.viewModel.prepareForInactive()
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(context.viewModel.isCameraRunning)
        XCTAssertFalse(context.viewModel.isCameraStarting)

        // 一時的な非アクティブ化で止めたカメラは、アクティブへ戻ったときに再開を要求する。
        context.viewModel.resumeAfterForeground()
        XCTAssertTrue(context.viewModel.isCameraStarting)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
    }

    func testActivePhaseDoesNotRestartCameraStoppedByUser() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.viewModel.cameraScannerDidStart(context.viewModel.camera)
        context.viewModel.stopCamera(showMessage: true)
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(context.viewModel.isCameraRunning)

        context.viewModel.prepareForInactive()
        context.viewModel.resumeAfterForeground()

        XCTAssertFalse(context.viewModel.isCameraStarting)
        XCTAssertFalse(context.viewModel.isCameraRunning)
    }

    func testEndingBluetoothSessionRestoresUnrestrictedBaseline() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)

        let completed = expectation(description: "Bluetooth session ended")
        context.viewModel.prepareForSessionEnd {
            completed.fulfill()
        }
        await fulfillment(of: [completed], timeout: 2)

        XCTAssertNil(context.service.expectedCode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .unrestricted)
    }

    func testEndingSessionWaitsForCameraStopBeforeCompleting() async {
        let queue = DispatchQueue(label: "BluetoothScannerFlowTests.suspended-camera")
        queue.suspend()
        let camera = CameraScanner(sessionQueue: queue)
        let context = makeContext(camera: camera)
        defer { context.cleanup() }
        let shutdownCompleted = expectation(description: "session end waits for camera")
        var didComplete = false

        // 実セッションの開始通知と同じ状態を作る。停止キューを止めている間は
        // PreviewLayerを外さないため、UI上もrunningのまま維持される必要がある。
        context.viewModel.cameraScannerDidStart(camera)
        XCTAssertTrue(context.viewModel.isCameraRunning)

        context.viewModel.prepareForSessionEnd {
            didComplete = true
            shutdownCompleted.fulfill()
        }

        XCTAssertTrue(context.viewModel.isEndingSession)
        XCTAssertTrue(context.viewModel.isCameraRunning)
        XCTAssertFalse(didComplete)
        queue.resume()

        await fulfillment(of: [shutdownCompleted], timeout: 2)
        XCTAssertTrue(didComplete)
        XCTAssertFalse(context.viewModel.isEndingSession)
        XCTAssertFalse(context.viewModel.isCameraRunning)
    }

    func testViewModelDeinitWaitsForCameraStop() async {
        let queue = DispatchQueue(label: "BluetoothScannerFlowTests.deinit-camera")
        queue.suspend()
        var camera: CameraScanner? = CameraScanner(sessionQueue: queue)
        weak var weakCamera = camera
        var context: (
            service: BluetoothScannerService,
            viewModel: ScannerViewModel,
            store: HistoryStore,
            scanLog: ScanLogStore,
            cleanup: () -> Void
        )? = makeContext(camera: camera!)
        let stopDrained = expectation(description: "deinit camera stop drained")

        context?.cleanup()
        context = nil
        camera = nil

        // ScannerViewModel.deinitがstopをキューへ積み、処理完了まで
        // CameraScanner自身を保持していることを確認する。
        XCTAssertNotNil(weakCamera)
        queue.async {
            Task { @MainActor in
                stopDrained.fulfill()
            }
        }
        queue.resume()

        await fulfillment(of: [stopDrained], timeout: 2)
        XCTAssertNil(weakCamera)
    }

    func testBluetoothConfigurationFailureFallsBackToCameraThenReturnsWhenReady() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)

        XCTAssertEqual(context.viewModel.inputSource, .camera)
        XCTAssertTrue(context.viewModel.message.contains("カメラへ切り替えました"))

        // 設定失敗は利用者の選択ではないので、復旧してReadyへ戻ればBluetoothへ自動で戻る。
        context.service.retryConfiguration()
        XCTAssertTrue(context.service.isReadyForScanning)
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        // カメラ停止の完了を待ってから、現在工程がスキャナへ再適用されたことを確認する。
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
    }

    func testSelectingBluetoothWhileConfigurationFailedRequestsRetry() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        // 復元書込も失敗したままなら、サービスは失敗状態に留まる。
        XCTAssertEqual(context.service.configurationState, .failed("設定に失敗しました。"))

        context.viewModel.selectInputSource(.bluetooth)

        XCTAssertTrue(context.viewModel.message.contains("やり直しています"))
        XCTAssertTrue(context.service.isReadyForScanning)
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(context.service.expectedCode, .qr)
    }

    func testForegroundResumeRetriesFailedBluetoothConfiguration() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        XCTAssertEqual(context.service.configurationState, .failed("設定に失敗しました。"))

        context.viewModel.resumeAfterForeground()

        XCTAssertTrue(context.service.isReadyForScanning)
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
    }

    /// 設定失敗が続いても、カメラへの退避は一時的なもの。Readyへ戻るたびにBluetoothへ戻す（#145）。
    func testRepeatedBluetoothConfigurationFailuresStillReturnToBluetoothWhenReady() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        for _ in 0..<3 {
            context.viewModel.handleBluetoothConfigurationState(.ready)
            XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
            context.service.simulateConfigurationFailure("設定に失敗しました。")
            context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
            XCTAssertEqual(context.viewModel.inputSource, .camera)
            context.service.retryConfiguration()
        }

        XCTAssertTrue(context.service.isReadyForScanning)
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
    }

    /// 利用者がカメラを選んでも、その箱の照合が終わって次のQRへ進めばBluetoothへ戻す（#145）。
    func testManualCameraSelectionLastsOnlyUntilTheNextBox() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)

        context.viewModel.selectInputSource(.camera)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        // 同じ箱の間は、接続状態の通知が来てもカメラのまま。
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        context.viewModel.runDemo(shouldMatch: true)
        XCTAssertEqual(context.viewModel.step, .result(.match))

        context.viewModel.reset()

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertFalse(context.viewModel.isCameraStarting)
        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertTrue(context.viewModel.message.contains("BCST-47"))
    }

    /// QRの取り直しは同じ箱の続きなので、利用者が選んだカメラのまま取り直す。
    func testRereadQRKeepsTheCameraTheOperatorChoseForThisBox() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        context.viewModel.selectInputSource(.bluetooth)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)

        context.viewModel.selectInputSource(.camera)
        context.viewModel.rereadQR()

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
    }

    /// スキャナ接続中に始めた照合は、接続前からカメラが選ばれていてもBluetoothで始める。
    func testNextBoxReturnsToBluetoothAfterCameraWasUsedBeforeTheScannerConnected() {
        let context = makeContext()
        defer { context.cleanup() }
        context.viewModel.runDemo(shouldMatch: true)
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        // 結果表示中は工程がないため切り替えない。
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        context.viewModel.reset()

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertEqual(context.service.expectedCode, .qr)
    }

    func testAcceptedBluetoothScanResetsConfigurationFailureCount() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        context.service.retryConfiguration()
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        try? await Task.sleep(for: .milliseconds(300))

        // 読取が受理されれば失敗回数はリセットされ、次の失敗でも自動復帰する。
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        XCTAssertEqual(context.viewModel.step, .barcode)
        context.service.retryConfiguration()
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(context.service.expectedCode, .barcode)
    }

    func testCameraRejectsUnrelatedQRAndKeepsWaitingForKanbanQR() {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        // 無関係なQR（URL、短い値、66桁だが必須フィールド不正）はQR待機を保持する。
        for payload in [
            "https://example.com/tissue",
            "BCJH5281GG",
            String(repeating: "X", count: 66)
        ] {
            context.viewModel.cameraScanner(camera, didRead: payload, type: .qr)
            XCTAssertEqual(context.viewModel.step, .qr, payload)
            XCTAssertTrue(context.viewModel.qrValue.isEmpty, payload)
            XCTAssertTrue(context.viewModel.message.contains("QRコードではありません"), payload)
        }
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)

        context.viewModel.cameraScanner(camera, didRead: ScannerViewModel.sampleQRPayload, type: .qr)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.qrValue, ScannerViewModel.sampleQRPayload)
    }

    func testCameraRejectsUnrelatedCode128AndKeepsWaitingForTagBarcode() async {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera
        context.viewModel.cameraScanner(camera, didRead: ScannerViewModel.sampleQRPayload, type: .qr)
        XCTAssertEqual(context.viewModel.step, .barcode)
        try? await Task.sleep(for: .milliseconds(300))

        // 業務外のCode 128（@なしの品番、URL、数字列など）はCode 128待機を保持し、
        // 同じ値が2フレーム届いても確定候補にしない。
        for payload in [
            "BCJH-52-81GG",
            "HELLO-WORLD",
            "1234567890",
            "https://example.com/tissue"
        ] {
            context.viewModel.cameraScanner(camera, didRead: payload, type: .code128)
            context.viewModel.cameraScanner(camera, didRead: payload, type: .code128)
            XCTAssertEqual(context.viewModel.step, .barcode, payload)
            XCTAssertTrue(context.viewModel.barcodeValue.isEmpty, payload)
            XCTAssertTrue(context.viewModel.message.contains("Code 128バーコードではありません"), payload)
        }
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)

        // 準拠した現品票のCode 128は従来どおり同一値2フレームで確定する。
        context.viewModel.cameraScanner(camera, didRead: ScannerViewModel.sampleBarcodePayload, type: .code128)
        XCTAssertEqual(context.viewModel.step, .barcode)
        context.viewModel.cameraScanner(camera, didRead: ScannerViewModel.sampleBarcodePayload, type: .code128)
        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
    }

    func testBluetoothDistinguishesUnrelatedCodesFromWrongOrder() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        // QR待機: Code 128形式は順序違い、それ以外は無関係なコードとして案内する。
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)
        XCTAssertTrue(context.viewModel.message.contains("読み取り順序が違います"))
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan("https://example.com/tissue")
        XCTAssertTrue(context.viewModel.message.contains("QRコードではありません"))
        XCTAssertEqual(context.viewModel.step, .qr)

        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)

        // Code 128待機: QR形式は順序違い、それ以外は無関係なコードとして案内する。
        try? await Task.sleep(for: .milliseconds(800))
        let otherQR = "DAYA005100DFR55581GA  0001000000010000Y      000000BYBYTLYB16   0*"
        context.service.simulateScan(otherQR)
        XCTAssertTrue(context.viewModel.message.contains("読み取り順序が違います"))
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan("https://example.com/tissue")
        XCTAssertTrue(context.viewModel.message.contains("Code 128バーコードではありません"))
        XCTAssertEqual(context.viewModel.step, .barcode)
    }

    func testBluetoothScanDuringMismatchResultWarnsWithoutAdvancing() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleMismatchBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.mismatch))

        // 不一致の結果表示中のトリガーは進めず、確認操作が必要なことを知らせる。
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .result(.mismatch))
        XCTAssertTrue(context.viewModel.message.contains("次のコードを照合"))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)

        context.viewModel.reset()
        XCTAssertEqual(context.viewModel.step, .qr)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
    }

    func testBluetoothDisconnectKeepsCurrentStepAndFallsBackToCamera() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.selectInputSource(.bluetooth)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))

        context.service.disconnect()
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.viewModel.inputSource, .camera)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.qrValue, ScannerViewModel.sampleQRPayload)
        XCTAssertTrue(context.viewModel.message.contains("カメラへ切り替えました"))

        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.qrValue, ScannerViewModel.sampleQRPayload)
    }

    func testMoltenQRThenFourTwoThreeBarcodeMatchesAndReportsDeliveryBox() async {
        let context = makeContext()
        defer { context.cleanup() }
        // 末尾の空白まで含めて61桁が1レコード。1文字でも欠けると別の値になる。
        XCTAssertEqual(ScannerViewModel.sampleMoltenQRPayload.count, 61)
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .molten)
        XCTAssertEqual(context.store.activeSession?.destination, .molten)

        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleMoltenBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 1)
        XCTAssertEqual(
            context.viewModel.deliverySummary,
            DeliveryBoxSummary(deliveryNumber: "UAG5560", boxCount: 1, totalQuantity: 120)
        )
        XCTAssertTrue(context.viewModel.message.contains("納品番号 UAG5560"))
        XCTAssertTrue(context.viewModel.message.contains("累計 120個"))
    }

    func testMoltenSecondBoxSameQRDifferentLabelIsCountedNotDuplicate() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleMoltenBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.match))

        // モルテンのQRは箱を区別しないため、現品票が別ラベルなら2箱目として数える。
        context.viewModel.reset()
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleMoltenSecondBoxBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 2)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 2)
        XCTAssertEqual(
            context.viewModel.deliverySummary,
            DeliveryBoxSummary(deliveryNumber: "UAG5560", boxCount: 2, totalQuantity: 240)
        )
        XCTAssertTrue(context.viewModel.message.contains("累計 240個"))
    }

    func testMoltenRescanOfSameBoxIsDuplicate() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleMoltenBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)

        // 同じQRと同じ現品票（=同じ箱）を読み直しても件数には加えない。
        context.viewModel.reset()
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleMoltenBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .result(.duplicate))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 0)
        XCTAssertNil(context.viewModel.deliverySummary)
    }

    func testMoltenMismatchIsNotCounted() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        // 同じ4-2-3の並びでも末尾ブロックが違えば別品番として扱う。
        context.service.simulateScan(ScannerViewModel.sampleMoltenMismatchBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .result(.mismatch))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)
        XCTAssertNil(context.viewModel.deliverySummary)
        XCTAssertEqual(context.viewModel.destination, .molten)
    }

    func testSessionLocksToFirstDestinationAndRejectsOtherDestinationQR() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .molten)

        // QRの読み取りなおしでは仕向地の固定を解除しない。
        context.viewModel.rereadQR()
        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertEqual(context.viewModel.destination, .molten)

        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.qrValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("モルテン"))
        XCTAssertTrue(context.viewModel.message.contains("読み取った値は照合に使用していません"))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)

        // 同じ仕向地のQRはそのまま受理する。
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .molten)
    }

    func testSawaiSessionRejectsMoltenQRViaCameraNamingSawai() {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        context.viewModel.cameraScanner(camera, didRead: ScannerViewModel.sampleQRPayload, type: .qr)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .sawai)

        context.viewModel.rereadQR()
        XCTAssertEqual(context.viewModel.step, .qr)

        context.viewModel.cameraScanner(
            camera,
            didRead: ScannerViewModel.sampleMoltenQRPayload,
            type: .qr
        )

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.qrValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("澤井製作所"))
        XCTAssertEqual(context.viewModel.destination, .sawai)
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)
    }

    func testFourTwoThreeBarcodeMatchesInSawaiSession() async {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera
        // 2026-09-08 の現場ラベル（#129）: カード番号は `DAH4` 型、品目番号欄は9桁品番を
        // 左詰めにした末尾空白、枝番は空白。現品票の品番は4-2-3。
        let qrPayload = "DAH4093540BCJH5281F   0002000000020000H      000000BHB01LHA28   0*"
        let barcodePayload = "BCJH-52-81F@01R95K"
        XCTAssertEqual(qrPayload.count, 66)

        context.viewModel.cameraScanner(camera, didRead: qrPayload, type: .qr)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .sawai)
        XCTAssertEqual(context.viewModel.qrPartNumber, "BCJH5281F")
        try? await Task.sleep(for: .milliseconds(300))

        // 4-2-3の現品票も澤井製作所のセッションで受理し、カメラでは同一値2フレームで確定する。
        context.viewModel.cameraScanner(camera, didRead: barcodePayload, type: .code128)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        context.viewModel.cameraScanner(camera, didRead: barcodePayload, type: .code128)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.viewModel.barcodePartNumber, "BCJH5281F")
        XCTAssertEqual(CodeMatcher.format(partNumber: "BCJH5281F", destination: context.viewModel.destination), "BCJH-52-81F")
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        XCTAssertEqual(context.store.activeSession?.destination, .sawai)
    }

    func testMoltenQRAtBarcodeStepIsWrongOrder() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)

        // 別の納品書QR（同じ仕向地）を現品票の代わりに読んだ場合は順序違いとして案内する。
        let otherMoltenQR = "AK6805BCKE34716B         UAG7530000FA3P20F-DAM000010809080500"
        XCTAssertEqual(otherMoltenQR.count, 61)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(otherMoltenQR)

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("読み取り順序が違います"))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)
    }

    func testDestinationIsRestoredFromActiveSessionOnViewModelCreation() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleMoltenBarcodePayload)
        XCTAssertEqual(context.store.activeSession?.destination, .molten)

        // 照合画面が作り直されても、セッションの仕向地は固定されたままにする。
        let restored = ScannerViewModel(
            historyStore: context.store,
            bluetoothScanner: context.service,
            scanLog: context.scanLog,
            camera: CameraScanner()
        )

        XCTAssertEqual(restored.destination, .molten)
    }

    func testStrippedTrailingSpacesStillMatchAndDoNotCreateSecondBox() async {
        let context = makeContext()
        defer { context.cleanup() }
        let fullQR = "AK6805D10E50N10B         U543820000MB    S600700000020908    "
        // 末尾の空白が落ちた読取値も同じレコードとして扱う。
        let strippedQR = "AK6805D10E50N10B         U543820000MB    S600700000020908"
        let firstBoxTag = "D10E-50-N10B@0UBL00"
        // 実ラベルは1箱分しか判明していないため、2箱目は管理コードだけを変えた想定値を使う。
        let secondBoxTag = "D10E-50-N10B@0UBL01"
        XCTAssertEqual(fullQR.count, 61)
        XCTAssertEqual(strippedQR.count, 57)
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(fullQR)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(firstBoxTag)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.viewModel.destination, .molten)
        XCTAssertEqual(
            context.viewModel.deliverySummary,
            DeliveryBoxSummary(deliveryNumber: "U543820", boxCount: 1, totalQuantity: 2)
        )

        context.viewModel.reset()
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(strippedQR)
        XCTAssertEqual(context.viewModel.step, .barcode)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(firstBoxTag)

        XCTAssertEqual(context.viewModel.step, .result(.duplicate))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)

        context.viewModel.reset()
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(strippedQR)
        XCTAssertEqual(context.viewModel.step, .barcode)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(secondBoxTag)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 2)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 2)
        XCTAssertEqual(
            context.viewModel.deliverySummary,
            DeliveryBoxSummary(deliveryNumber: "U543820", boxCount: 2, totalQuantity: 4)
        )
    }

    func testDensoQRThenSixFourBarcodeMatches() async {
        let context = makeContext()
        defer { context.cleanup() }
        // JAMA自己記述形式のかんばん。実物は221桁。
        XCTAssertEqual(ScannerViewModel.sampleDensoQRPayload.count, 221)
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleDensoQRPayload)

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .denso)
        XCTAssertEqual(context.store.activeSession?.destination, .denso)
        // デンソーの品番は6-4で表示する（4-2-4の8601-50-7722にしない）。
        XCTAssertTrue(context.viewModel.message.contains("品目番号 860150-7722"))

        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleDensoBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 1)
        // 納品番号ごとの集計はモルテン専用。デンソーでは使わない。
        XCTAssertNil(context.viewModel.deliverySummary)
        XCTAssertEqual(context.store.activeSession?.entries.first?.code, "860150-7722")
        XCTAssertTrue(context.viewModel.message.contains("品目番号が一致しています"))
    }

    func testDensoSecondKanbanSamePartIsCountedAsSecondBox() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleDensoQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleDensoBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.match))

        // かんばん連番が0140→0141と変わるので、同じ品番でも別の箱として数える。
        context.viewModel.reset()
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleDensoSecondBoxQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleDensoSecondBoxBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 2)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 2)
        XCTAssertNil(context.viewModel.deliverySummary)
        XCTAssertTrue(context.viewModel.message.contains("2箱目"))
    }

    func testDensoRescanOfSameKanbanIsDuplicate() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleDensoQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleDensoBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)

        // 重複キーはQR全文。現品票の管理コードが別でも、同じかんばんなら同じ箱。
        context.viewModel.reset()
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleDensoQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleDensoSecondBoxBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .result(.duplicate))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 0)
        XCTAssertNil(context.viewModel.deliverySummary)
    }

    func testDensoMismatchIsNotCounted() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleDensoQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        // 同じ6-4の並びでも末尾が違えば別品番として扱う。
        context.service.simulateScan(ScannerViewModel.sampleDensoMismatchBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .result(.mismatch))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)
        XCTAssertNil(context.viewModel.deliverySummary)
        XCTAssertEqual(context.viewModel.destination, .denso)
    }

    func testSessionLockedToDensoRejectsSawaiAndMoltenQR() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleDensoQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .denso)

        context.viewModel.rereadQR()
        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertEqual(context.viewModel.destination, .denso)

        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.qrValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("デンソー"))
        XCTAssertTrue(
            context.viewModel.message.contains(
                "このセッションは仕向地「デンソー」で照合中です。別の仕向地のQRコードは照合できません。仕向地を変えるにはセッションを終了してください。"
            )
        )
        XCTAssertTrue(context.viewModel.message.contains("読み取った値は照合に使用していません"))

        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.qrValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("デンソー"))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)

        // 同じ仕向地のQRはそのまま受理する。
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleDensoQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .denso)
    }

    func testSawaiSessionRejectsDensoQR() {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        context.viewModel.cameraScanner(camera, didRead: ScannerViewModel.sampleQRPayload, type: .qr)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .sawai)

        context.viewModel.rereadQR()
        XCTAssertEqual(context.viewModel.step, .qr)

        context.viewModel.cameraScanner(
            camera,
            didRead: ScannerViewModel.sampleDensoQRPayload,
            type: .qr
        )

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.qrValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("澤井製作所"))
        XCTAssertEqual(context.viewModel.destination, .sawai)
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)
    }

    func testSixFourBarcodeIsRejectedInSawaiAndMoltenSessions() async {
        // 澤井製作所・モルテンは4-2-3/4-2-4。デンソーの6-4はどちらでも受理しない。
        for (qrPayload, destination) in [
            (ScannerViewModel.sampleQRPayload, Destination.sawai),
            (ScannerViewModel.sampleMoltenQRPayload, Destination.molten)
        ] {
            let context = makeContext()
            defer { context.cleanup() }
            let camera = context.viewModel.camera

            context.viewModel.cameraScanner(camera, didRead: qrPayload, type: .qr)
            XCTAssertEqual(context.viewModel.step, .barcode)
            XCTAssertEqual(context.viewModel.destination, destination)
            try? await Task.sleep(for: .milliseconds(300))

            context.viewModel.cameraScanner(
                camera,
                didRead: ScannerViewModel.sampleDensoBarcodePayload,
                type: .code128
            )
            context.viewModel.cameraScanner(
                camera,
                didRead: ScannerViewModel.sampleDensoBarcodePayload,
                type: .code128
            )

            XCTAssertEqual(context.viewModel.step, .barcode)
            XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
            XCTAssertTrue(
                context.viewModel.message.contains("現品票のCode 128バーコードではありません")
            )
            XCTAssertEqual(context.store.activeSession?.matchedCount, 0)
        }
    }

    func testDensoQRAtBarcodeStepIsWrongOrder() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleDensoQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)

        // 別のかんばん（同じ仕向地）を現品票の代わりに読んだ場合は順序違いとして案内する。
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleDensoSecondBoxQRPayload)

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("読み取り順序が違います"))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)
    }

    func testDestinationIsRestoredFromActiveSessionForDenso() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleDensoQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleDensoBarcodePayload)
        XCTAssertEqual(context.store.activeSession?.destination, .denso)

        let restored = ScannerViewModel(
            historyStore: context.store,
            bluetoothScanner: context.service,
            scanLog: context.scanLog,
            camera: CameraScanner()
        )

        XCTAssertEqual(restored.destination, .denso)
    }

    /// カメラ経路でもデンソーのQRを受理し、案内に6-4の品番を出す。
    func testDensoQRViaCameraShowsSixFourPartNumber() {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        context.viewModel.cameraScanner(
            camera,
            didRead: ScannerViewModel.sampleDensoQRPayload,
            type: .qr
        )

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.destination, .denso)
        XCTAssertTrue(context.viewModel.message.contains("品目番号 860150-7722"))
    }

    // MARK: - 照合ログ

    func testBluetoothMatchIsLoggedWithCodeAndBoxNumber() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)

        let events = loggedEvents(context.scanLog)
        XCTAssertEqual(events.map(\.event), ["qr_accepted", "barcode_accepted", "match"])
        XCTAssertEqual(events.map(\.source), ["bluetooth", "bluetooth", "bluetooth"])
        XCTAssertEqual(events.map(\.step), ["qr", "barcode", "result"])
        XCTAssertEqual(events.map(\.destination), ["sawai", "sawai", "sawai"])
        XCTAssertEqual(events[0].qr, ScannerViewModel.sampleQRPayload)
        XCTAssertNil(events[0].barcode)
        XCTAssertEqual(events[1].barcode, ScannerViewModel.sampleBarcodePayload)
        XCTAssertNil(events[1].qr)
        XCTAssertEqual(events[2].qr, ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(events[2].barcode, ScannerViewModel.sampleBarcodePayload)
        XCTAssertEqual(events[2].code, "BCJH-52-81GG")
        XCTAssertEqual(events[2].boxNumber, 1)
        XCTAssertEqual(events[2].message, context.viewModel.message)
        XCTAssertEqual(events[2].session, context.store.activeSession?.id)
        XCTAssertNil(events[2].reason)
        XCTAssertEqual(context.scanLog.eventCount, 3)
    }

    func testBluetoothMismatchIsLogged() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleMismatchBarcodePayload)

        let events = loggedEvents(context.scanLog)
        XCTAssertEqual(events.map(\.event), ["qr_accepted", "barcode_accepted", "mismatch"])
        let mismatch = events[2]
        XCTAssertEqual(mismatch.qr, ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(mismatch.barcode, ScannerViewModel.sampleMismatchBarcodePayload)
        XCTAssertEqual(mismatch.code, "BCJH-55-81GG")
        XCTAssertNil(mismatch.boxNumber)
        XCTAssertTrue(mismatch.message?.contains("一致しません") ?? false)
    }

    func testBluetoothDuplicateIsLogged() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.match))

        context.viewModel.reset(automaticallyStartScanning: false)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)

        let events = loggedEvents(context.scanLog)
        XCTAssertEqual(
            events.map(\.event),
            ["qr_accepted", "barcode_accepted", "match", "qr_accepted", "barcode_accepted", "duplicate"]
        )
        let duplicate = events[5]
        XCTAssertEqual(duplicate.qr, ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(duplicate.barcode, ScannerViewModel.sampleBarcodePayload)
        XCTAssertEqual(duplicate.code, "BCJH-52-81GG")
        XCTAssertNil(duplicate.boxNumber)
        XCTAssertTrue(duplicate.message?.contains("すでに照合済み") ?? false)
    }

    func testBluetoothTriggerDuringNonMatchResultIsLoggedAsResultPending() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleMismatchBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.mismatch))

        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)

        let rejected = loggedEvents(context.scanLog).last
        XCTAssertEqual(rejected?.event, "rejected")
        XCTAssertEqual(rejected?.reason, "result_pending")
        XCTAssertEqual(rejected?.step, "result")
        // 結果表示中は待っている工程が無いので、読み取った値はCode 128側へ入れる。
        XCTAssertEqual(rejected?.barcode, ScannerViewModel.sampleBarcodePayload)
        XCTAssertNil(rejected?.qr)
    }

    func testBluetoothWrongOrderIsLogged() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)

        let events = loggedEvents(context.scanLog)
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].event, "rejected")
        XCTAssertEqual(events[0].reason, "wrong_order")
        XCTAssertEqual(events[0].step, "qr")
        XCTAssertEqual(events[0].qr, ScannerViewModel.sampleBarcodePayload)
        XCTAssertNil(events[0].barcode)
        XCTAssertNil(events[0].destination)
    }

    func testBluetoothInvalidFormatIsLogged() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan("HELLO-WORLD")

        let events = loggedEvents(context.scanLog)
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].event, "rejected")
        XCTAssertEqual(events[0].reason, "invalid_format")
        XCTAssertEqual(events[0].qr, "HELLO-WORLD")
    }

    func testBluetoothWrongDestinationIsLogged() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)
        context.viewModel.reset(automaticallyStartScanning: false)
        try? await Task.sleep(for: .milliseconds(800))

        context.service.simulateScan(ScannerViewModel.sampleMoltenQRPayload)

        let rejected = loggedEvents(context.scanLog).last
        XCTAssertEqual(rejected?.event, "rejected")
        XCTAssertEqual(rejected?.reason, "wrong_destination")
        XCTAssertEqual(rejected?.step, "qr")
        XCTAssertEqual(rejected?.qr, ScannerViewModel.sampleMoltenQRPayload)
        XCTAssertEqual(rejected?.destination, "sawai")
    }

    func testCameraRejectionIsLoggedOnlyOnceWithinSuppressionWindow() {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera

        context.viewModel.cameraScanner(camera, didRead: "NOT-A-KANBAN", type: .qr)
        context.viewModel.cameraScanner(camera, didRead: "NOT-A-KANBAN", type: .qr)
        context.viewModel.cameraScanner(camera, didRead: "NOT-A-KANBAN", type: .qr)

        let events = loggedEvents(context.scanLog)
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].event, "rejected")
        XCTAssertEqual(events[0].reason, "invalid_format")
        XCTAssertEqual(events[0].source, "camera")
        XCTAssertEqual(events[0].qr, "NOT-A-KANBAN")
    }

    func testCameraBarcodeCandidateIsLoggedBeforeAcceptance() async {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera

        context.viewModel.cameraScanner(camera, didRead: ScannerViewModel.sampleQRPayload, type: .qr)
        try? await Task.sleep(for: .milliseconds(300))
        context.viewModel.cameraScanner(
            camera,
            didRead: ScannerViewModel.sampleBarcodePayload,
            type: .code128
        )
        context.viewModel.cameraScanner(
            camera,
            didRead: ScannerViewModel.sampleBarcodePayload,
            type: .code128
        )

        let events = loggedEvents(context.scanLog)
        XCTAssertEqual(
            events.map(\.event),
            ["qr_accepted", "barcode_candidate", "barcode_accepted", "match"]
        )
        XCTAssertEqual(events[1].barcode, ScannerViewModel.sampleBarcodePayload)
        XCTAssertEqual(events[1].step, "barcode")
        XCTAssertEqual(events.map(\.source), ["camera", "camera", "camera", "camera"])
    }

    func testCameraSymbologyMismatchIsLoggedOncePerWindowWithoutChangingMessage() {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera

        context.viewModel.cameraScanner(
            camera,
            didRead: ScannerViewModel.sampleBarcodePayload,
            type: .code128
        )
        context.viewModel.cameraScanner(
            camera,
            didRead: ScannerViewModel.sampleBarcodePayload,
            type: .code128
        )

        let events = loggedEvents(context.scanLog)
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].event, "rejected")
        XCTAssertEqual(events[0].reason, "wrong_symbology")
        XCTAssertEqual(events[0].step, "qr")
        XCTAssertEqual(events[0].qr, ScannerViewModel.sampleBarcodePayload)
        // 文言はQR待機の案内のままで、不受理音は鳴らさない。
        XCTAssertEqual(
            context.viewModel.message,
            "正方形のQRコードを枠に合わせてください。"
        )
    }

    func testSessionEndIsLoggedWhileTheSessionIsStillActive() {
        let context = makeContext()
        defer { context.cleanup() }
        let sessionID = context.store.activeSession?.id

        context.viewModel.prepareForSessionEnd()

        let events = loggedEvents(context.scanLog)
        XCTAssertEqual(events.map(\.event), ["session_end"])
        XCTAssertEqual(events[0].session, sessionID)
        XCTAssertEqual(events[0].step, "none")
        XCTAssertEqual(events[0].source, "camera")
    }

    private func makeContext(
        camera: CameraScanner = CameraScanner(),
        autoAdvanceEnabled: Bool = false,
        autoAdvanceDelay: AutoAdvanceDelay = .threeSeconds,
        autoAdvanceTickDuration: Duration = .seconds(1)
    ) -> (
        service: BluetoothScannerService,
        viewModel: ScannerViewModel,
        store: HistoryStore,
        scanLog: ScanLogStore,
        cleanup: () -> Void
    ) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let storageURL = directory.appendingPathComponent("history.json")
        let defaultsName = "BluetoothScannerFlowTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: defaultsName)!
        let service = BluetoothScannerService(defaults: defaults)
        let store = HistoryStore(storageURL: storageURL)
        let scanLog = ScanLogStore(
            storageURL: directory.appendingPathComponent("scan-log.jsonl"),
            arguments: []
        )
        store.beginSession()
        let viewModel = ScannerViewModel(
            historyStore: store,
            bluetoothScanner: service,
            scanLog: scanLog,
            camera: camera,
            isAutoAdvanceEnabled: autoAdvanceEnabled,
            autoAdvanceDelay: autoAdvanceDelay,
            autoAdvanceTickDuration: autoAdvanceTickDuration
        )
        return (service, viewModel, store, scanLog, {
            try? FileManager.default.removeItem(at: directory)
            defaults.removePersistentDomain(forName: defaultsName)
        })
    }

    /// 照合ログに記録されたイベントを読み出す。復号できない行があればテストを失敗させる。
    private func loggedEvents(
        _ scanLog: ScanLogStore,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> [ScanLogEvent] {
        do {
            return try scanLog.events()
        } catch {
            XCTFail("照合ログを復号できませんでした: \(error)", file: file, line: line)
            return []
        }
    }
}
