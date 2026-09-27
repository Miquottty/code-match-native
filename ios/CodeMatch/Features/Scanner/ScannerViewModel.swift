import AVFoundation
import Foundation
import SwiftUI

@MainActor
final class ScannerViewModel: ObservableObject {
    @Published private(set) var step: ScanStep = .qr
    @Published private(set) var inputSource: ScanInputSource = .camera
    @Published private(set) var qrValue = ""
    @Published private(set) var barcodeValue = ""
    @Published private(set) var isCameraRunning = false
    @Published private(set) var isCameraStarting = false
    @Published private(set) var isEndingSession = false
    @Published private(set) var message = ""
    @Published private(set) var focusPoint: CGPoint?
    /// 一致した品番がこのセッションで何箱目の照合かを示す通し番号。結果表示中以外は0。
    @Published private(set) var sessionBoxNumber = 0
    /// セッションの仕向地。最初に受理したQRで固定し、以後は別仕向地のQRを拒否する。
    /// セッション終了まで解除しない。
    @Published private(set) var destination: Destination?
    /// モルテンの一致結果でだけ設定する納品番号ごとの集計。結果表示中以外は nil。
    @Published private(set) var deliverySummary: DeliveryBoxSummary?
    @Published private(set) var isAutoAdvanceEnabled: Bool
    @Published private(set) var autoAdvanceDelay: AutoAdvanceDelay
    @Published private(set) var autoAdvanceSecondsRemaining: Int?

    let camera: CameraScanner
    private let feedback = FeedbackPlayer.shared
    private let historyStore: HistoryStore
    private let bluetoothScanner: BluetoothScannerService
    private let scanLog: ScanLogStore
    private var scanLocked = false
    private var barcodeCandidate: (value: String, count: Int, date: Date)?
    private var autoAdvanceTask: Task<Void, Never>?
    private let autoAdvanceTickDuration: Duration
    /// 利用者がカメラを選んだ箱の間だけ、Bluetoothへ自動で戻さないためのフラグ。
    /// 次の照合（QR工程）へ進むと解除し、スキャナ接続中はBluetoothへ戻す（#145）。
    private var cameraWasSelectedByUser = false
    /// 一時的な非アクティブ化（Control Center、通知センター、着信など）で止めたカメラを、
    /// アクティブへ戻ったときに自動で再開するためのフラグ。
    private var cameraWasRunningBeforeInactive = false
    /// カメラで拒否した業務外のコード（QR / Code 128）。同じ値がフレームごとに届くため、
    /// 一定時間は再通知しない。
    private var rejectedCameraCode: (value: String, date: Date)?
    static let cameraRejectionRepeatInterval: TimeInterval = 2
    /// 想定と違うシンボロジーをカメラが読んだときに、照合ログだけを抑制するための記録。
    /// 文言と効果音は`rejectCameraCode`を通さない（通すと2秒ごとに警告音が鳴る）。
    private var lastLoggedSymbologyMismatch: (value: String, date: Date)?
    private var localizedMessageBuilder: (() -> String)?

    var expectedCode: ExpectedCode? {
        switch step {
        case .qr: .qr
        case .barcode: .barcode
        case .result: nil
        }
    }

    /// スキャナのリンクが確立していて、読み取り設定が失敗で止まっていない。設定中も含む。
    /// この間は照合の入力元をBluetoothにする（#145）。設定失敗と接続断のときだけカメラへ退避する。
    private var bluetoothIsUsable: Bool {
        guard bluetoothScanner.isConnected else { return false }
        if case .failed = bluetoothScanner.configurationState { return false }
        return true
    }

    private func setLocalizedMessage(_ builder: @escaping () -> String) {
        let localizedMessage = builder()
        message = localizedMessage
        localizedMessageBuilder = builder
    }

    func refreshLocalizedMessage() {
        if let builder = localizedMessageBuilder {
            message = builder()
        }
    }

    /// 実ラベル由来のサンプルペイロード(仕向地 澤井製作所・品番 BCJH-52-81GG)。デモ判定に使用する。
    static let sampleQRPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    static let sampleBarcodePayload = "BCJH-52-81GG@1N5X0C"
    static let sampleMismatchBarcodePayload = "BCJH-55-81GG@1KVV0C"
    /// 実ラベル由来のサンプルペイロード(仕向地 モルテン・品番 PAF1-15-422・納品番号 UAG5560)。
    /// 61桁固定長レコードなので、末尾の空白まで含めて1文字も削らない。
    static let sampleMoltenQRPayload = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    static let sampleMoltenBarcodePayload = "PAF1-15-422@0NKD3C"
    /// 同じ納品書QRの2箱目。モルテンのQRは箱を区別しないため、現品票の管理コードだけが異なる。
    static let sampleMoltenSecondBoxBarcodePayload = "PAF1-15-422@0NLL3C"
    static let sampleMoltenMismatchBarcodePayload = "PAF1-15-423@0N5L3C"
    /// 実ラベル由来のサンプルペイロード(仕向地 デンソー・品番 860150-7722)。
    /// JAMA自己記述形式のかんばん。かんばん連番(項目152)が箱ごとに異なるため、
    /// 重複判定は澤井製作所と同じくQR全文で行う。
    static let sampleDensoQRPayload = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
    static let sampleDensoBarcodePayload = "860150-7722@1DZ50O"
    /// 同じ品番の2箱目。かんばん連番が0140から0141に変わる。
    static let sampleDensoSecondBoxQRPayload = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0141SWS    20260908S0010000720000009924543330454333M6"
    static let sampleDensoSecondBoxBarcodePayload = "860150-7722@1DZB0O"
    /// 別品番(860150-7791)の現品票。不一致の確認に使う。
    static let sampleDensoMismatchBarcodePayload = "860150-7791@01335C"

    var qrPartNumber: String? {
        CodeMatcher.partNumber(fromQR: qrValue)
    }

    var barcodePartNumber: String? {
        CodeMatcher.partNumber(fromBarcode: barcodeValue)
    }

    init(
        historyStore: HistoryStore,
        bluetoothScanner: BluetoothScannerService,
        scanLog: ScanLogStore,
        camera: CameraScanner = CameraScanner(),
        isAutoAdvanceEnabled: Bool = AutoAdvanceSettings.isEnabled(),
        autoAdvanceDelay: AutoAdvanceDelay = AutoAdvanceSettings.delay(),
        autoAdvanceTickDuration: Duration = .seconds(1)
    ) {
        self.historyStore = historyStore
        self.bluetoothScanner = bluetoothScanner
        self.scanLog = scanLog
        self.camera = camera
        self.isAutoAdvanceEnabled = isAutoAdvanceEnabled
        self.autoAdvanceDelay = autoAdvanceDelay
        self.autoAdvanceTickDuration = autoAdvanceTickDuration
        // 画面が作り直されても、セッションで確定済みの仕向地は引き継ぐ。
        destination = historyStore.activeSession?.resolvedDestination
        camera.delegate = self
        bluetoothScanner.onCode = { [weak self] value in
            self?.handleBluetoothScan(value)
        }
        setLocalizedMessage {
            AppLocalization.string("まず、納品書兼現品票のQRコードをカメラに映してください。")
        }

        if ProcessInfo.processInfo.arguments.contains("-demoMatch") {
            qrValue = Self.sampleQRPayload
            barcodeValue = Self.sampleBarcodePayload
            step = .result(.match)
            setLocalizedMessage { AppLocalization.string("品目番号が一致しています。") }
            historyStore.recordMatch(
                code: recordedCode,
                qrPayload: qrValue,
                barcodePayload: barcodeValue,
                destination: .sawai
            )
            sessionBoxNumber = historyStore.activeSessionMatchCount(code: recordedCode)
            destination = .sawai
        } else if ProcessInfo.processInfo.arguments.contains("-demoMismatch") {
            qrValue = Self.sampleQRPayload
            barcodeValue = Self.sampleMismatchBarcodePayload
            step = .result(.mismatch)
            setLocalizedMessage {
                AppLocalization.string("品目番号が一致しません。納品書と現品の取り違えを確認してください。")
            }
        }
    }

    deinit {
        autoAdvanceTask?.cancel()
        // 予期しない画面破棄経路でも、非同期停止処理がCameraScanner自身を
        // 完了まで保持し、実行中のカメラデバイスを残さない。
        camera.stop()
    }

    func toggleCamera() {
        guard inputSource == .camera else { return }
        if isCameraRunning || isCameraStarting {
            stopCamera(showMessage: true)
        } else {
            startCamera()
        }
    }

    func startCamera() {
        guard !isEndingSession, let expectedCode else { return }
        inputSource = .camera
        isCameraStarting = true
        isCameraRunning = false
        setLocalizedMessage { AppLocalization.string("カメラを準備しています…") }
        camera.setActiveType(expectedCode.metadataType)
        camera.requestAccessAndStart()
    }

    func stopCamera(showMessage: Bool = false) {
        focusPoint = nil
        camera.stop { [weak self] in
            guard let self else { return }
            self.isCameraStarting = false
            self.isCameraRunning = false
            if showMessage {
                let isQr = self.expectedCode == .qr
                self.setLocalizedMessage {
                    isQr
                    ? AppLocalization.string("カメラを停止しました。「QRコードを読み取る」で再開できます。")
                    : AppLocalization.string("カメラを停止しました。「バーコードを読み取る」で再開できます。")
                }
            }
        }
    }

    /// 結果表示から次の照合へ戻す。手動の「次のコードを照合」も自動照合も同じ経路を通る。
    /// カメラ入力のときは既定でカメラを再開するので、ボタンを押した直後から次のQRを読める。
    /// BCST-47が接続されている場合は従来どおりカメラを起動せず、`setExpectedCode(.qr)`だけを行う。
    /// テストなど、カメラ起動を避けたい場合だけ`automaticallyStartScanning: false`を渡す。
    func reset(automaticallyStartScanning: Bool = true) {
        cancelAutoAdvanceCountdown()
        camera.stop()
        step = .qr
        qrValue = ""
        barcodeValue = ""
        isCameraRunning = false
        isCameraStarting = false
        scanLocked = false
        barcodeCandidate = nil
        rejectedCameraCode = nil
        lastLoggedSymbologyMismatch = nil
        focusPoint = nil
        sessionBoxNumber = 0
        // 仕向地はセッション単位の固定なので、次の照合へ進んでも解除しない。
        deliverySummary = nil
        // カメラの選択はその箱の間だけ。スキャナ接続中は次のQRからBluetoothへ戻す（#145）。
        cameraWasSelectedByUser = false
        if bluetoothIsUsable {
            inputSource = .bluetooth
            bluetoothScanner.setExpectedCode(.qr)
            updateBluetoothInstruction()
        } else {
            bluetoothScanner.setExpectedCode(nil)
            inputSource = .camera
            setLocalizedMessage { AppLocalization.string("まず、納品書兼現品票のQRコードをカメラに映してください。") }
            if automaticallyStartScanning {
                startCamera()
            }
        }
    }

    /// バーコード待機中に、誤って読み取ったQRだけを破棄してQR工程へ戻す。
    /// セッションと照合済み件数は維持し、選択中の入力方法で直ちに読み取りを再開する。
    func rereadQR() {
        guard step == .barcode, !isEndingSession else { return }

        cancelAutoAdvanceCountdown()
        step = .qr
        qrValue = ""
        barcodeValue = ""
        scanLocked = false
        barcodeCandidate = nil
        focusPoint = nil
        // 仕向地はセッション単位の固定なので、QRを取り直しても解除しない。
        deliverySummary = nil

        // QRの取り直しは同じ箱の続きなので、利用者が選んだカメラはそのまま使う。
        // それ以外でスキャナが使えるならBluetoothで取り直す（#145）。
        if !cameraWasSelectedByUser, bluetoothIsUsable {
            if inputSource != .bluetooth {
                activateBluetooth()
                return
            }
            bluetoothScanner.setExpectedCode(.qr)
            if bluetoothScanner.isReadyForScanning {
                setLocalizedMessage { AppLocalization.string("BCST-47で別の納品書兼現品票のQRコードを読み取ってください。") }
            } else {
                updateBluetoothInstruction()
            }
            return
        }

        bluetoothScanner.setExpectedCode(nil)
        inputSource = .camera
        camera.setActiveType(ExpectedCode.qr.metadataType)
        setLocalizedMessage { AppLocalization.string("別の納品書兼現品票のQRコードを枠の中央に合わせてください。") }
        if !isCameraRunning, !isCameraStarting {
            startCamera()
        }
    }

    func setAutoAdvanceEnabled(_ isEnabled: Bool) {
        guard isAutoAdvanceEnabled != isEnabled else { return }
        isAutoAdvanceEnabled = isEnabled
        if isEnabled {
            startAutoAdvanceCountdownIfNeeded()
        } else {
            cancelAutoAdvanceCountdown()
        }
    }

    func setAutoAdvanceDelay(_ delay: AutoAdvanceDelay) {
        guard autoAdvanceDelay != delay else { return }
        autoAdvanceDelay = delay
        startAutoAdvanceCountdownIfNeeded()
    }

    func selectInputSource(_ source: ScanInputSource) {
        guard !isEndingSession, expectedCode != nil else { return }

        switch source {
        case .camera:
            cameraWasSelectedByUser = true
            activateCamera()
        case .bluetooth:
            cameraWasSelectedByUser = false
            // 設定中でもリンクがあればBluetoothを選び、完了まで「設定中」を表示する（#145）。
            guard bluetoothScanner.isReadyForScanning || bluetoothIsUsable else {
                inputSource = .camera
                if bluetoothScanner.isConnected,
                   case .failed = bluetoothScanner.configurationState {
                    // 設定失敗で止まっている場合は、利用者の再選択を再設定の合図として扱う。
                    bluetoothScanner.retryConfiguration()
                    setLocalizedMessage {
                        AppLocalization.string("Bluetoothスキャナの読み取り設定をやり直しています。完了すると自動的に切り替わります。")
                    }
                    return
                }
                let isConnected = bluetoothScanner.isConnected
                setLocalizedMessage {
                    isConnected
                        ? AppLocalization.string("Bluetoothスキャナの読み取り設定を準備しています。少し待ってからもう一度選択してください。")
                        : AppLocalization.string("Bluetoothスキャナが未接続です。設定画面で接続してください。")
                }
                return
            }
            activateBluetooth()
        }
    }

    func handleBluetoothConnectionState(_ state: BluetoothScannerConnectionState) {
        guard !isEndingSession else { return }
        if state.connectedDevice != nil {
            // 接続済みなら、読み取り設定中でもBluetoothを入力元にする（#145）。
            guard bluetoothIsUsable,
                  expectedCode != nil,
                  !cameraWasSelectedByUser else { return }
            if inputSource != .bluetooth {
                activateBluetooth()
            } else if bluetoothScanner.isReadyForScanning {
                // 設定中メッセージを、完了した現在工程の案内へ戻す。
                updateBluetoothInstruction()
            }
            return
        }

        guard inputSource == .bluetooth else { return }
        activateCamera()
        setLocalizedMessage {
            AppLocalization.string("Bluetoothスキャナとの接続が切れたため、現在の読取ステップを維持してカメラへ切り替えました。")
        }
    }

    func handleBluetoothConfigurationState(_ state: BluetoothScannerConfigurationState) {
        guard !isEndingSession else { return }
        switch state {
        case .ready:
            handleBluetoothConnectionState(bluetoothScanner.state)
        case .failed(let reason):
            guard inputSource == .bluetooth else { return }
            // 設定失敗は利用者の選択ではないため、カメラへの退避は一時的なもの。
            // 復旧してReadyへ戻れば、失敗が続いた後でもBluetoothへ自動で戻す（#145）。
            // 自動の再設定はサービス側で回数に上限があるため、往復は有限回で止まる。
            activateCamera()
            setLocalizedMessage {
                AppLocalization.string("\(reason) 現在の読取ステップを維持してカメラへ切り替えました。")
            }
        case .configuring:
            if inputSource == .bluetooth {
                setLocalizedMessage {
                    AppLocalization.string("BCST-47の読み取り対象を設定しています。完了するまでお待ちください。")
                }
            }
        case .unavailable:
            break
        }
    }

    func prepareForSessionEnd(completion: CameraScanner.Completion? = nil) {
        cancelAutoAdvanceCountdown()
        guard !isEndingSession else { return }
        // アクティブセッションのidが残っているうちに記録する。
        // セッション系のイベントは工程を持たない（Android と同じ `none`）。
        log("session_end", stepOverride: "none")
        isEndingSession = true
        scanLocked = true
        focusPoint = nil
        camera.stop { [weak self] in
            guard let self else {
                completion?()
                return
            }
            // PreviewLayerはstopRunning完了後にだけ外す。実行中のレイヤーを
            // メインスレッドから先に切断してUIが固まる順序を避ける。
            self.isCameraRunning = false
            self.isCameraStarting = false
            self.bluetoothScanner.setExpectedCode(nil)
            self.isEndingSession = false
            completion?()
        }
    }

    /// `.inactive` はControl Center・通知センター・App Switcher・Face ID・着信など、
    /// 数秒で戻る一時的な状態でも発生する。ここではカメラだけを止め、
    /// スキャナーの読み取り設定は書き換えない。全バーコード種別の復元と再制限を
    /// 毎回往復すると、その間の読取が破棄され、設定通信とトリガー入力の衝突による
    /// 失敗の契機にもなるため、復元は実際にバックグラウンドへ入ったときに限定する。
    func prepareForInactive() {
        cancelAutoAdvanceCountdown()
        cameraWasRunningBeforeInactive = inputSource == .camera
            && (isCameraRunning || isCameraStarting)
        stopCamera()
    }

    func prepareForBackground() {
        cancelAutoAdvanceCountdown()
        cameraWasRunningBeforeInactive = false
        stopCamera()
        if inputSource == .bluetooth {
            // バックグラウンド中は読取コールバックを扱わないため、強制終了に
            // 備えてスキャナーを照合開始前の全バーコード設定へ戻す。
            bluetoothScanner.setExpectedCode(nil)
        }
    }

    func resumeAfterForeground() {
        let shouldRestartCamera = cameraWasRunningBeforeInactive
        cameraWasRunningBeforeInactive = false
        if step == .result(.match) {
            startAutoAdvanceCountdownIfNeeded()
            return
        }
        // 前景へ戻ったとき、利用者が選んだのではないカメラ（接続前・接続断・設定失敗からの退避）で、
        // スキャナが使える状態ならBluetoothへ戻す（#145）。
        if !isEndingSession,
           !cameraWasSelectedByUser,
           inputSource == .camera,
           expectedCode != nil,
           bluetoothIsUsable {
            activateBluetooth()
            return
        }
        if shouldRestartCamera,
           !isEndingSession,
           inputSource == .camera,
           expectedCode != nil,
           !isCameraRunning, !isCameraStarting {
            startCamera()
            return
        }
        // 設定失敗でカメラへ退避している間に前景へ戻ったときは、再設定を試みる。
        if !isEndingSession,
           !cameraWasSelectedByUser,
           bluetoothScanner.isConnected,
           case .failed = bluetoothScanner.configurationState {
            bluetoothScanner.retryConfiguration()
        }
        guard !isEndingSession,
              inputSource == .bluetooth,
              bluetoothScanner.isConnected,
              let expectedCode else { return }
        // 論理的なQR→Code 128の進行状態は保持し、ハードウェア設定だけを戻す。
        bluetoothScanner.setExpectedCode(expectedCode)
    }

    func focus(at devicePoint: CGPoint, displayAt viewPoint: CGPoint) {
        guard isCameraRunning else { return }
        focusPoint = viewPoint
        camera.focus(at: devicePoint)
        Task {
            try? await Task.sleep(for: .milliseconds(850))
            if focusPoint == viewPoint { focusPoint = nil }
        }
    }

    func runDemo(shouldMatch: Bool) {
        camera.stop()
        isCameraRunning = false
        isCameraStarting = false
        qrValue = Self.sampleQRPayload
        barcodeValue = shouldMatch ? Self.sampleBarcodePayload : Self.sampleMismatchBarcodePayload
        finishComparison()
    }

    private func acceptQR(_ value: String, destination: Destination) {
        // 最初に受理したQRでセッションの仕向地を確定し、履歴側にも同じ値を残す。
        if self.destination == nil {
            self.destination = destination
            historyStore.setActiveSessionDestinationIfNeeded(destination)
        }
        // 工程を進める前に記録する（この時点の step は "qr"）。
        log("qr_accepted", qr: value)
        scanLocked = true
        qrValue = value
        step = .barcode
        if inputSource == .bluetooth {
            bluetoothScanner.setExpectedCode(.barcode)
        }
        // 次のステップではCode 128だけを検出対象にして読み取りを速くする
        if inputSource == .camera {
            camera.setActiveType(ExpectedCode.barcode.metadataType)
        }
        if let part = CodeMatcher.partNumber(fromQR: value) {
            // 表記は仕向地ごとに違う(デンソーは6-4、それ以外は4-2-4/4-2-3)。
            // ここでは受理したQRの仕向地をそのまま使う。
            let partNumber = CodeMatcher.format(partNumber: part, destination: destination)
            let isBluetooth = inputSource == .bluetooth
            setLocalizedMessage {
                let instruction = isBluetooth
                    ? AppLocalization.string("続けてBCST-47で現品票のCode 128バーコードを読み取ってください。")
                    : AppLocalization.string("続けて現品票のCode 128バーコードを映してください。")
                return AppLocalization.string("品目番号 \(partNumber) を読み取りました。\(instruction)")
            }
        } else {
            setLocalizedMessage {
                self.inputSource == .bluetooth
                    ? AppLocalization.string("読取完了。続けてBCST-47でCode 128バーコードを読み取ってください。")
                    : AppLocalization.string("読取完了。続けて横長のCode 128バーコードを映してください。")
            }
        }
        feedback.scanAccepted()
        Task {
            try? await Task.sleep(for: .milliseconds(250))
            scanLocked = false
        }
    }

    private func acceptBarcodeCandidate(_ value: String) {
        let now = Date()
        if let candidate = barcodeCandidate,
           candidate.value == value,
           now.timeIntervalSince(candidate.date) < 1.5 {
            barcodeCandidate = (value, candidate.count + 1, now)
        } else {
            barcodeCandidate = (value, 1, now)
        }

        guard barcodeCandidate?.count ?? 0 >= 2 else {
            log("barcode_candidate", barcode: value)
            setLocalizedMessage {
                AppLocalization.string("コードを確認中です。そのまま一瞬だけ保持してください。")
            }
            return
        }

        acceptBarcode(value, resultSoundDelay: 0.28)
    }

    private func acceptBarcode(_ value: String, resultSoundDelay: TimeInterval = 0) {
        log("barcode_accepted", barcode: value)
        scanLocked = true
        barcodeValue = value
        feedback.scanAccepted()
        if inputSource == .camera, isCameraRunning || isCameraStarting {
            camera.stop { [weak self] in
                guard let self else { return }
                // 結果画面へ遷移してCameraPreviewを破棄するのは、実セッションが
                // 停止してからに限定する。
                self.isCameraRunning = false
                self.isCameraStarting = false
                self.finishComparison(resultSoundDelay: resultSoundDelay)
            }
        } else {
            camera.stop()
            isCameraRunning = false
            isCameraStarting = false
            finishComparison(resultSoundDelay: resultSoundDelay)
        }
    }

    private func handleBluetoothScan(_ value: String) {
        guard inputSource == .bluetooth, bluetoothScanner.isReadyForScanning else { return }
        // 結果表示中のトリガーは無音で捨てず、不一致・重複では確認操作が必要なことを知らせる。
        // 一致は自動送り／手動送りの流れに任せ、通知しない。
        if case .result(let result) = step {
            if result != .match {
                rejectBluetoothScan(
                    "結果を確認して「次のコードを照合」を押してください。",
                    logReason: "result_pending",
                    value: value
                )
            }
            return
        }
        guard !scanLocked, let expectedCode else { return }

        switch expectedCode {
        case .qr:
            guard let detected = Destination.detect(qrPayload: value) else {
                // 相手工程の正しい形式なら順序違い、それ以外は無関係なコードとして案内する。
                // 仕向地が未確定でも取りこぼさないよう、現品票の判定は緩い方を使う。
                let isWrongOrder = TagBarcodeRecord.isValidScanPayload(value, destination: nil)
                rejectBluetoothScan(
                    isWrongOrder
                        ? "読み取り順序が違います。先に納品書兼現品票のQRコードを読み取ってください。"
                        : "納品書兼現品票のQRコードではありません。納品書兼現品票のQRコードを読み取ってください。",
                    logReason: isWrongOrder ? "wrong_order" : "invalid_format",
                    value: value
                )
                return
            }
            // セッションの仕向地が確定していれば、別仕向地のQRは照合へ進めない。
            if let locked = destination, locked != detected {
                rejectBluetoothScan(
                    Self.destinationLockMessage(locked),
                    logReason: "wrong_destination",
                    value: value
                )
                return
            }
            acceptQR(value, destination: detected)
        case .barcode:
            // BCST-47は1回の読取結果を複数回通知することがある。
            // QR確定後に同じペイロードが再通知されてもバーコードとして扱わない。
            guard value != qrValue else { return }
            guard TagBarcodeRecord.isValidScanPayload(value, destination: destination) else {
                let isWrongOrder = Destination.detect(qrPayload: value) != nil
                rejectBluetoothScan(
                    isWrongOrder
                        ? "読み取り順序が違います。現在は現品票のCode 128バーコード待ちです。"
                        : "現品票のCode 128バーコードではありません。現在は現品票のCode 128バーコード待ちです。",
                    logReason: isWrongOrder ? "wrong_order" : "invalid_format",
                    value: value
                )
                return
            }
            // 外部スキャナはトリガー操作そのものを確定操作として扱う。
            acceptBarcode(value)
        }
    }

    private func rejectBluetoothScan(
        _ reason: String.LocalizationValue,
        logReason: String,
        value: String
    ) {
        setLocalizedMessage {
            "\(AppLocalization.string(reason)) " + AppLocalization.string("読み取った値は照合に使用していません。")
        }
        feedback.invalidScan()
        logRejection(reason: logReason, value: value)
    }

    /// カメラのQRもBLEと同じ業務QR（仕向地ごとの固定長レコード）の検証を通す。無関係なQRは
    /// 警告してQR待機と件数を保持し、履歴や診断へ値を残さない。
    private func rejectCameraQR(_ value: String) {
        // 文言は1種類だが、理由はBluetoothと同じ基準で分ける（相手工程の正しい形式なら順序違い）。
        let isWrongOrder = TagBarcodeRecord.isValidScanPayload(value, destination: nil)
        rejectCameraCode(value, reason: isWrongOrder ? "wrong_order" : "invalid_format") {
            AppLocalization.string("納品書兼現品票のQRコードではありません。正しいQRコードを枠の中央に合わせてください。")
        }
    }

    /// カメラのCode 128もBLEと同じ現品票の業務形式（品番@管理コード）の検証を通す。
    /// 業務外のCode 128は2フレーム確認の候補に入れず、Code 128待機と件数を保持する。
    private func rejectCameraBarcode(_ value: String) {
        let isWrongOrder = Destination.detect(qrPayload: value) != nil
        rejectCameraCode(value, reason: isWrongOrder ? "wrong_order" : "invalid_format") {
            AppLocalization.string("現品票のCode 128バーコードではありません。現品票のCode 128バーコードを枠に合わせてください。")
        }
    }

    /// 仕向地を固定した後に別仕向地のQRが届いたときの案内。カメラは同じQRが
    /// フレームごとに届くため、`rejectCameraCode`の再通知抑制を通す。
    private func rejectCameraDestinationLock(_ value: String, locked: Destination) {
        rejectCameraCode(value, reason: "wrong_destination") {
            AppLocalization.string(Self.destinationLockMessage(locked))
        }
    }

    /// 仕向地を固定中に別仕向地のQRを拒否する文言。Bluetoothとカメラで同じキーを使う。
    private static func destinationLockMessage(_ locked: Destination) -> String.LocalizationValue {
        "このセッションは仕向地「\(locked.displayName)」で照合中です。別の仕向地のQRコードは照合できません。仕向地を変えるにはセッションを終了してください。"
    }

    private func rejectCameraCode(
        _ value: String,
        reason: String,
        message: @escaping () -> String
    ) {
        let now = Date()
        if let rejected = rejectedCameraCode,
           rejected.value == value,
           now.timeIntervalSince(rejected.date) < Self.cameraRejectionRepeatInterval {
            return
        }
        rejectedCameraCode = (value, now)
        setLocalizedMessage(message)
        feedback.invalidScan()
        logRejection(reason: reason, value: value)
    }

    private func updateBluetoothInstruction() {
        guard bluetoothScanner.isReadyForScanning else {
            setLocalizedMessage {
                AppLocalization.string("BCST-47の読み取り対象を設定しています。完了するまでお待ちください。")
            }
            return
        }
        setLocalizedMessage {
            self.expectedCode == .qr
                ? AppLocalization.string("BCST-47で納品書兼現品票のQRコードを読み取ってください。")
                : AppLocalization.string("BCST-47で現品票のCode 128バーコードを読み取ってください。")
        }
    }

    private func activateCamera() {
        bluetoothScanner.setExpectedCode(nil)
        inputSource = .camera
        barcodeCandidate = nil
        startCamera()
    }

    private func activateBluetooth() {
        focusPoint = nil
        barcodeCandidate = nil
        inputSource = .bluetooth

        // カメラを使っていない初回接続や自動再接続では待つ対象がないため、
        // スキャナを即座に現在工程へ設定する。
        guard isCameraRunning || isCameraStarting else {
            camera.stop()
            bluetoothScanner.setExpectedCode(expectedCode)
            updateBluetoothInstruction()
            return
        }

        // 表示はすぐBluetoothへ切り替えるが、非表示になったCameraPreviewは
        // stopRunning完了までセッションへ接続したまま保持する。
        setLocalizedMessage {
            AppLocalization.string("カメラを停止してBluetoothスキャナへ切り替えています…")
        }
        camera.stop { [weak self] in
            guard let self, self.inputSource == .bluetooth else { return }
            self.isCameraRunning = false
            self.isCameraStarting = false
            self.bluetoothScanner.setExpectedCode(self.expectedCode)
            self.updateBluetoothInstruction()
        }
    }

    private func finishComparison(resultSoundDelay: TimeInterval = 0) {
        // 結果表示と次の照合の間もQR・Code 128のセッション固定モードを維持する。
        // 工程ごとのGATT設定変更をなくし、連続トリガーと設定通信の競合を防ぐ。
        let comparison = CodeMatcher.compare(qrPayload: qrValue, barcodePayload: barcodeValue)
        let detected = Destination.detect(qrPayload: qrValue)
        let result: MatchResult
        if comparison == .match,
           historyStore.activeSessionContainsMatchedBox(qrPayload: qrValue, barcodePayload: barcodeValue) {
            result = .duplicate
        } else {
            result = comparison
        }
        step = .result(result)

        switch result {
        case .match:
            // デモ判定などQRの受理を経ない経路でも、記録する仕向地と表示を一致させる。
            if destination == nil, let detected {
                destination = detected
            }
            historyStore.recordMatch(
                code: recordedCode,
                qrPayload: qrValue,
                barcodePayload: barcodeValue,
                destination: detected
            )
            if detected == .molten, let record = MoltenQRRecord.parse(qrValue) {
                // モルテンは納品番号ごとに箱を数え、収容数を積み上げて報告する。
                let summary = historyStore.activeSessionDeliverySummary(
                    deliveryNumber: record.deliveryNumber
                )
                sessionBoxNumber = summary.boxCount
                deliverySummary = summary
                setLocalizedMessage {
                    AppLocalization.string(
                        "品目番号が一致しています。納品番号 \(summary.deliveryNumber) は本セッションで\(summary.boxCount)箱目です（累計 \(summary.totalQuantity)個）。"
                    )
                }
            } else {
                // 澤井製作所とデンソーはQR自体が箱ごとに異なる(カード番号／かんばん連番)ため、
                // 納品番号での集計は使わず、品番ごとに「本セッションでN箱目」を数える。
                deliverySummary = nil
                let boxNumber = historyStore.activeSessionMatchCount(code: recordedCode)
                sessionBoxNumber = boxNumber
                setLocalizedMessage {
                    boxNumber >= 2
                        ? AppLocalization.string("品目番号が一致しています。この品番は本セッションで\(boxNumber)箱目です。")
                        : AppLocalization.string("品目番号が一致しています。")
                }
            }
            log(
                "match",
                qr: qrValue,
                barcode: barcodeValue,
                code: recordedCode,
                boxNumber: sessionBoxNumber,
                message: message
            )
            feedback.success(after: resultSoundDelay)
            startAutoAdvanceCountdownIfNeeded()
        case .mismatch:
            sessionBoxNumber = 0
            deliverySummary = nil
            setLocalizedMessage {
                AppLocalization.string("品目番号が一致しません。納品書と現品の取り違えを確認してください。")
            }
            log(
                "mismatch",
                qr: qrValue,
                barcode: barcodeValue,
                code: recordedCode,
                message: message
            )
            feedback.failure()
        case .duplicate:
            sessionBoxNumber = 0
            deliverySummary = nil
            setLocalizedMessage {
                AppLocalization.string("すでに照合済みです。このコードは照合件数に加えていません。")
            }
            log(
                "duplicate",
                qr: qrValue,
                barcode: barcodeValue,
                code: recordedCode,
                message: message
            )
            feedback.failure()
        }
    }

    private func startAutoAdvanceCountdownIfNeeded() {
        cancelAutoAdvanceCountdown()
        guard isAutoAdvanceEnabled,
              !isEndingSession,
              step == .result(.match) else { return }

        autoAdvanceSecondsRemaining = autoAdvanceDelay.rawValue
        autoAdvanceTask = Task { [weak self] in
            while let self,
                  let remaining = self.autoAdvanceSecondsRemaining,
                  remaining > 0 {
                do {
                    try await Task.sleep(for: self.autoAdvanceTickDuration)
                } catch {
                    return
                }
                guard !Task.isCancelled,
                      self.isAutoAdvanceEnabled,
                      self.step == .result(.match) else { return }

                let nextRemaining = remaining - 1
                if nextRemaining == 0 {
                    self.reset(automaticallyStartScanning: true)
                    return
                }
                self.autoAdvanceSecondsRemaining = nextRemaining
            }
        }
    }

    private func cancelAutoAdvanceCountdown() {
        autoAdvanceTask?.cancel()
        autoAdvanceTask = nil
        autoAdvanceSecondsRemaining = nil
    }

    // MARK: - 照合ログ

    /// 照合ログへ1件記録する。セッション・入力方法・工程・仕向地は現在の状態から埋める。
    private func log(
        _ event: String,
        reason: String? = nil,
        qr: String? = nil,
        barcode: String? = nil,
        code: String? = nil,
        boxNumber: Int? = nil,
        message: String? = nil,
        stepOverride: String? = nil
    ) {
        scanLog.record(
            ScanLogEvent(
                at: Date(),
                session: historyStore.activeSession?.id,
                source: inputSource.scanLogValue,
                step: stepOverride ?? step.scanLogValue,
                event: event,
                reason: reason,
                destination: destination?.rawValue,
                qr: qr,
                barcode: barcode,
                code: code,
                boxNumber: boxNumber,
                message: message
            )
        )
    }

    /// 不受理の記録。読み取った値は待っている工程に合わせてQR / Code 128のどちらかへ入れる。
    private func logRejection(reason: String, value: String) {
        let isQRStep = expectedCode == .qr
        log(
            "rejected",
            reason: reason,
            qr: isQRStep ? value : nil,
            barcode: isQRStep ? nil : value
        )
    }

    /// 想定と違うシンボロジーを読んだときの記録。カメラは同じ値がフレームごとに届くため、
    /// 文言・効果音の挙動は変えずにログだけを2秒に1件へ抑える。
    private func logSymbologyMismatch(_ value: String) {
        let now = Date()
        if let logged = lastLoggedSymbologyMismatch,
           logged.value == value,
           now.timeIntervalSince(logged.date) < Self.cameraRejectionRepeatInterval {
            return
        }
        lastLoggedSymbologyMismatch = (value, now)
        logRejection(reason: "wrong_symbology", value: value)
    }

    /// 履歴へ残す値。読み取れた品番を優先し、抽出できない場合はQRの生値を使う。
    /// 表記は仕向地ごとに違う(デンソーは6-4、それ以外は4-2-4/4-2-3)ため、
    /// セッションで固定済みの仕向地を使う。デモ判定などQRの受理を経ない経路では
    /// まだ固定されていないので、そのQRから判定し直す。
    private var recordedCode: String {
        if let part = barcodePartNumber ?? qrPartNumber {
            let destination = destination ?? Destination.detect(qrPayload: qrValue)
            return CodeMatcher.format(partNumber: part, destination: destination)
        }
        return qrValue
    }
}

extension ScannerViewModel: CameraScannerDelegate {
    func cameraScannerDidStart(_ scanner: CameraScanner) {
        guard inputSource == .camera, expectedCode != nil else {
            scanner.stop()
            return
        }
        isCameraStarting = false
        isCameraRunning = true
        setLocalizedMessage {
            self.expectedCode == .qr
                ? AppLocalization.string("QRコードを枠の中央に合わせてください。")
                : AppLocalization.string("横長のCode 128バーコード全体を枠に合わせてください。")
        }
    }

    func cameraScannerDidStop(_ scanner: CameraScanner) {
        isCameraStarting = false
        isCameraRunning = false
    }

    func cameraScanner(
        _ scanner: CameraScanner,
        didRead value: String,
        type: AVMetadataObject.ObjectType
    ) {
        guard inputSource == .camera, !scanLocked, let expectedCode else { return }

        switch (expectedCode, type) {
        case (.qr, .qr):
            guard let detected = Destination.detect(qrPayload: value) else {
                rejectCameraQR(value)
                return
            }
            // セッションの仕向地が確定していれば、別仕向地のQRは照合へ進めない。
            if let locked = destination, locked != detected {
                rejectCameraDestinationLock(value, locked: locked)
                return
            }
            acceptQR(value, destination: detected)
        case (.barcode, .code128):
            guard TagBarcodeRecord.isValidScanPayload(value, destination: destination) else {
                rejectCameraBarcode(value)
                return
            }
            acceptBarcodeCandidate(value)
        default:
            logSymbologyMismatch(value)
            setLocalizedMessage {
                expectedCode == .qr
                    ? AppLocalization.string("正方形のQRコードを枠に合わせてください。")
                    : AppLocalization.string("横長のCode 128バーコードを枠に合わせてください。")
            }
        }
    }

    func cameraScanner(_ scanner: CameraScanner, didFail message: String) {
        isCameraStarting = false
        isCameraRunning = false
        setLocalizedMessage { message }
    }
}

extension ScanInputSource {
    /// 照合ログの `source`。Android版と同じ文字列を使う。
    var scanLogValue: String { rawValue }
}

extension ScanStep {
    /// 照合ログの `step`。Android版と同じ文字列を使う。
    var scanLogValue: String {
        switch self {
        case .qr: "qr"
        case .barcode: "barcode"
        case .result: "result"
        }
    }
}
