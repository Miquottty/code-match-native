import CoreBluetooth
import Combine
import Foundation
import OSLog

#if INATECK_SDK
import InateckScannerBleKit
#endif

enum ScanInputSource: String, CaseIterable, Identifiable {
    case camera
    case bluetooth

    var id: String { rawValue }

    var label: String {
        switch self {
        case .camera: AppLocalization.string("カメラ")
        case .bluetooth: AppLocalization.string("Bluetooth")
        }
    }
}

struct BluetoothScannerDevice: Identifiable, Hashable {
    let id: String
    let name: String
}

enum BluetoothScannerConnectionState: Equatable {
    case idle
    case searching
    case connecting(BluetoothScannerDevice)
    case connected(BluetoothScannerDevice)
    case unavailable(String)
    case failed(String)

    var connectedDevice: BluetoothScannerDevice? {
        guard case .connected(let device) = self else { return nil }
        return device
    }

    var statusText: String {
        switch self {
        case .idle: AppLocalization.string("未接続")
        case .searching: AppLocalization.string("スキャナを検索中…")
        case .connecting(let device): AppLocalization.string("\(device.name)へ接続中…")
        case .connected(let device): AppLocalization.string("\(device.name) 接続済み")
        case .unavailable(let message), .failed(let message): message
        }
    }
}

enum BluetoothScannerConfigurationState: Equatable {
    case unavailable
    case configuring
    case ready
    case failed(String)
}

/// スキャナー照明（`lighting_lamp_control`）の確認済み状態。`unknown`をOFF成功として表示しない。
enum BluetoothScannerIlluminationState: Equatable {
    /// 接続中のスキャナーのinventoryに照明設定がない
    case unsupported
    /// 接続後、まだSDKから現在値を確認していない
    case unknown
    case applying
    case off
    case on
    case failed(String)
}

/// 読取チューニング（多コード・反転・赤光消灯時間）の適用状態。
enum BluetoothScannerTuningState: Equatable {
    /// inventoryに対象項目が1つもない
    case unsupported
    case unknown
    case applying
    /// `applied`はこの接続で書込を行ったか。falseは既に一致していた
    case matched(applied: Bool)
    case failed(String)
}

enum BluetoothScannerSymbologyMode: String, Equatable {
    case unrestricted
    case sessionCodes
    // 旧バージョンが制限中に終了した場合の復旧互換用。新しい照合では使用しない。
    case qrOnly
    case code128Only

    init(expectedCode: ExpectedCode?) {
        switch expectedCode {
        case .qr, .barcode: self = .sessionCodes
        case nil: self = .unrestricted
        }
    }

    var statusText: String {
        switch self {
        case .unrestricted:
            AppLocalization.string("読取対象：接続前の設定へ復元済み")
        case .sessionCodes:
            AppLocalization.string("読取対象：QR・Code 128（照合セッション）")
        case .qrOnly:
            AppLocalization.string("読取対象：QRのみ（旧設定から復旧中）")
        case .code128Only:
            AppLocalization.string("読取対象：Code 128のみ（旧設定から復旧中）")
        }
    }
}

struct BluetoothScannerSymbologySnapshot: Codable, Equatable {
    let deviceID: String
    let values: [String: Int]
}

/// 照合開始前の全バーコード設定（復元用スナップショット）をスキャナーごとに保存する。
/// 1台分しか持たないと、照合中に離れたスキャナーが戻るまで別のスキャナーを
/// 設定できなかった（#135）。他のスキャナーの分は、そのスキャナーの次回接続で復元する。
struct BluetoothScannerSymbologySnapshotStore {
    static let snapshotsKey = "bluetoothScanner.symbologySnapshots"
    /// 1台分だけを保存していた旧形式。持ち主のスキャナーの分として読み、次の書込で移す。
    static let legacySnapshotKey = "bluetoothScanner.symbologySnapshot"

    let defaults: UserDefaults

    var all: [String: BluetoothScannerSymbologySnapshot] {
        var snapshots = defaults.data(forKey: Self.snapshotsKey).flatMap {
            try? JSONDecoder().decode([String: BluetoothScannerSymbologySnapshot].self, from: $0)
        } ?? [:]
        if let data = defaults.data(forKey: Self.legacySnapshotKey),
           let legacy = try? JSONDecoder().decode(BluetoothScannerSymbologySnapshot.self, from: data),
           snapshots[legacy.deviceID] == nil {
            snapshots[legacy.deviceID] = legacy
        }
        return snapshots
    }

    func snapshot(for deviceID: String) -> BluetoothScannerSymbologySnapshot? {
        all[deviceID]
    }

    func save(_ snapshot: BluetoothScannerSymbologySnapshot) {
        var snapshots = all
        snapshots[snapshot.deviceID] = snapshot
        write(snapshots)
    }

    func remove(deviceID: String) {
        var snapshots = all
        guard snapshots.removeValue(forKey: deviceID) != nil else { return }
        write(snapshots)
    }

    func removeAll() {
        defaults.removeObject(forKey: Self.snapshotsKey)
        defaults.removeObject(forKey: Self.legacySnapshotKey)
    }

    private func write(_ snapshots: [String: BluetoothScannerSymbologySnapshot]) {
        if snapshots.isEmpty {
            defaults.removeObject(forKey: Self.snapshotsKey)
        } else if let data = try? JSONEncoder().encode(snapshots) {
            defaults.set(data, forKey: Self.snapshotsKey)
        } else {
            return
        }
        defaults.removeObject(forKey: Self.legacySnapshotKey)
    }
}

struct BluetoothScannerDiagnosticEvent: Identifiable, Equatable, Codable {
    let id = UUID()
    let date: Date
    let message: String
}

/// Inateck SDKへの依存をアプリの残りから隔離する接続サービス。
/// Simulatorでは同じAPIのモックとして動作し、実機ビルドだけが公式SDKを使用する。
@MainActor
final class BluetoothScannerService: NSObject, ObservableObject {
    static let preferredDeviceIDKey = "bluetoothScanner.preferredDeviceID"
    static let symbologyRecoveryModeKey = "bluetoothScanner.symbologyRecoveryMode"
    static let diagnosticEventsKey = "bluetoothScanner.diagnosticEvents"
    static let cachedScannerSettingsKey = "bluetoothScanner.cachedScannerSettings"
    static let lastKnownDeviceIDKey = "bluetoothScanner.lastKnownDeviceID"
    static let lastKnownDeviceNameKey = "bluetoothScanner.lastKnownDeviceName"
    static let discoveryTimeout: TimeInterval = 5
    static let connectionTimeout: TimeInterval = 30
    static let automaticReconnectTimeout: TimeInterval = 8
    static let duplicateInterval: TimeInterval = 0.75
    /// 1回の設定書込（最大28項目）にSDKの応答を待つ上限。超過時は新しい
    /// コマンドを重ねずリンクを閉じて再同期する。3秒ではBLE混雑やスキャナー側の
    /// 処理遅延だけで強制切断に至ったため、Android版の25秒との中間の10秒にする。
    static let symbologyCommandTimeout: Duration = .seconds(10)
    /// SDKの切断通知に依存せず、SDKが保持するリンク状態を定期的に確認する間隔。
    static let linkLivenessInterval: Duration = .seconds(30)
    /// 端末内に保持する診断イベント数。長時間セッションの事後解析に足りる量を残しつつ、
    /// 設定画面には直近`diagnosticDisplayLimit`件だけを表示する。
    static let diagnosticEventLimit = 300
    static let diagnosticDisplayLimit = 20
    static let settingsRetryLimit = 4
    /// 読み取り設定の書込が失敗したときに、同じ要求を再送する回数。
    static let symbologyWriteRetryLimit = 2
    /// `.failed` になった後に、設定の再取得から自動でやり直す間隔と回数。
    static let configurationRecoveryDelay: Duration = .seconds(5)
    static let configurationRecoveryLimit = 3
    /// SDK出力設定（FF04書込）用の補助接続が完了するまでの上限。
    /// 超過してもこの設定は読取そのものに必須ではないため、先へ進む。
    static let sdkOutputConfigurationTimeout: Duration = .seconds(8)
    /// 照明設定は取得→書込→再取得の3往復なので、読み取り設定より長い上限を置く。
    static let illuminationCommandTimeout: Duration = .seconds(20)
    /// 機器のinventoryが報告する照明設定のname。areaは機器の報告値を使い、汎用flag 1003へ置き換えない。
    static let illuminationSettingName = "lighting_lamp_control"
    /// GATTモードへ切り替えた後、スキャナーの再起動を待ってから再検索するまでの時間。
    static let gattSwitchRediscoveryDelay: Duration = .seconds(3)
    /// GATT切替後に切替先を探す回数。尽きたら利用者に検索から選び直してもらう。
    static let gattSwitchRediscoveryLimit = 3

    struct TuningItem: Equatable {
        let name: String
        let value: Int
    }

    /// 接続ごとに揃える読取チューニング（#59）。inventoryにある項目だけを対象にし、
    /// 現在値と異なる項目があるときだけ1コマンドで書く。値は機器側に保存され、切断時に復元しない。
    /// - 多コード認識OFF: 大判ラベル周辺の別コードを拾いにいかせない
    /// - 反転（正相/反相）OFF: 不要な探索を減らす
    /// - 赤光消灯までの時間（`auto_close_mode`、汎用flag 1023相当、単位0.2秒）: 既定10（2秒）では
    ///   位置合わせ中に消えるため20（約4秒）。HPRT-4F5Fで20→約3.8秒を実測して確定した。
    ///   `time_auto_off`は赤光時間を変えなかった（電源自動OFF時間と推定）ため対象外にする。
    static let tuningProfile: [TuningItem] = [
        TuningItem(name: "qrcode_read_more_code", value: 0),
        TuningItem(name: "datamatrix_read_multi", value: 0),
        TuningItem(name: "pdf417_read_more_code", value: 0),
        TuningItem(name: "read_inverse_color", value: 0),
        TuningItem(name: "qrcode_read_phase", value: 0),
        TuningItem(name: "datamatrix_read_phase", value: 0),
        TuningItem(name: "hanxin_read_phase", value: 0),
        TuningItem(name: "pdf417_read_phase", value: 0),
        TuningItem(name: "auto_close_mode", value: 20)
    ]

    @Published private(set) var devices: [BluetoothScannerDevice] = []
    @Published private(set) var state: BluetoothScannerConnectionState = .idle
    @Published private(set) var configurationState: BluetoothScannerConfigurationState = .unavailable
    @Published private(set) var diagnosticEvents: [BluetoothScannerDiagnosticEvent] = []
    @Published private(set) var illuminationState: BluetoothScannerIlluminationState = .unknown
    @Published private(set) var tuningState: BluetoothScannerTuningState = .unknown

    var onCode: ((String) -> Void)?

    private let defaults: UserDefaults
    private let now: () -> Date
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "jp.rimtty.CodeMatch",
        category: "BluetoothScanner"
    )
    private var reconnectDeviceID: String?
    private var lastDeliveredCode: (value: String, date: Date)?
    private(set) var expectedCode: ExpectedCode?

#if INATECK_SDK
    private var sdkDevices: [String: BLEDevice] = [:]
    private var connectedSDKDevice: BLEDevice?
    private var availabilityMonitor: CBCentralManager?
    private var pendingDiscovery = false
    private var sdkDiscoveryIsRunning = false
    private var applicationIsActive = false
    private var automaticReconnectAttempt = 0
    private var automaticReconnectTask: Task<Void, Never>?
    private var gattModeChangeInProgress = false
    /// GATTモードへ切り替えた接続元。切替で広告名と識別子が変わる機種があるため（#134）、
    /// 再起動後は切替元の識別子へ再接続し続けず、再検索で見つけた切替先へ接続する。
    private var gattSwitchOrigin: BluetoothScannerDevice?
    private var gattSwitchRediscoveryAttempt = 0
    /// 切替先を探す再検索を予約済み、または実行中で、検索終了時に接続先を決める。
    private var gattSwitchRediscoveryPending = false
    private var gattSwitchRediscoveryTask: Task<Void, Never>?
    private var sdkOutputConfigurationPeripheral: CBPeripheral?
    private var sdkOutputConfigurationInProgress = false
    private var sdkOutputConfigurationTimeoutTask: Task<Void, Never>?
    private var sdkOutputConfigurationCompleted = false
    private var connectedScannerSettings: String?
    private var connectedSymbologyValues: [String: Int]?
    private var connectedScannerSettingsAreFresh = false
    private var symbologyConfigurationRevision = 0
    /// 読み取り設定・照明・チューニングを含む全setSettingInfoの直列化ゲート。
    /// SDKは設定応答とscan通知を同じcharacteristicで扱うため、コマンドは1本ずつしか送らない。
    private var settingCommandInFlight = false
    private var settingCommandGeneration = 0
    private var settingCommandTimeoutTask: Task<Void, Never>?
    private var manualDisconnectInProgress = false
    private var configurationRecoveryTask: Task<Void, Never>?
    private var configurationRecoveryAttempt = 0
    private var linkLivenessTask: Task<Void, Never>?
    /// 接続直後にSDKが報告したリンク状態。SDKがこの値を更新しない実装でも
    /// 誤って切断扱いにしないよう、生存確認はこの基準からの変化だけを見る。
    private var connectedLinkBaselineState: BLEDevice.ConnectType?
    /// 設定コマンド実行中に受けた照明要求。完了後に最新の1件だけを適用する。
    private var pendingIlluminationRequest: Bool?
#else
    /// Simulatorテスト用: 設定されている間は`setExpectedCode`が失敗し、
    /// `retryConfiguration()`で解除される。
    private var simulatedConfigurationFailure: String?
#endif

    var connectedDevice: BluetoothScannerDevice? { state.connectedDevice }
    var isConnected: Bool { connectedDevice != nil }
    var isReadyForScanning: Bool { isConnected && configurationState == .ready }
    var isConnecting: Bool {
        if case .connecting = state { return true }
        return false
    }
    var reconnectableDevice: BluetoothScannerDevice? {
        guard let id = defaults.string(forKey: Self.lastKnownDeviceIDKey), !id.isEmpty else {
            return nil
        }
        return devices.first(where: { $0.id == id })
            ?? BluetoothScannerDevice(
                id: id,
                name: defaults.string(forKey: Self.lastKnownDeviceNameKey) ?? "Inateck Scanner"
            )
    }

    var persistedSymbologyMode: BluetoothScannerSymbologyMode {
        guard let rawValue = defaults.string(forKey: Self.symbologyRecoveryModeKey),
              let mode = BluetoothScannerSymbologyMode(rawValue: rawValue) else {
            return .unrestricted
        }
        return mode
    }

    /// 復元待ちの照合前設定。キーはスキャナーの識別子で、別のスキャナーの分は接続を妨げない。
    var persistedSymbologySnapshots: [String: BluetoothScannerSymbologySnapshot] {
        symbologySnapshots.all
    }

    func persistedSymbologySnapshot(for deviceID: String) -> BluetoothScannerSymbologySnapshot? {
        symbologySnapshots.snapshot(for: deviceID)
    }

    private var symbologySnapshots: BluetoothScannerSymbologySnapshotStore {
        BluetoothScannerSymbologySnapshotStore(defaults: defaults)
    }

    init(
        defaults: UserDefaults = .standard,
        now: @escaping () -> Date = Date.init
    ) {
        self.defaults = defaults
        self.now = now
        super.init()

        if ProcessInfo.processInfo.arguments.contains("-resetBluetoothScanner") {
            defaults.removeObject(forKey: Self.preferredDeviceIDKey)
            defaults.removeObject(forKey: Self.symbologyRecoveryModeKey)
            symbologySnapshots.removeAll()
            defaults.removeObject(forKey: Self.diagnosticEventsKey)
            defaults.removeObject(forKey: Self.cachedScannerSettingsKey)
            defaults.removeObject(forKey: Self.lastKnownDeviceIDKey)
            defaults.removeObject(forKey: Self.lastKnownDeviceNameKey)
        } else if let data = defaults.data(forKey: Self.diagnosticEventsKey),
                  let events = try? JSONDecoder().decode(
                    [BluetoothScannerDiagnosticEvent].self,
                    from: data
                  ) {
            diagnosticEvents = Array(events.suffix(Self.diagnosticEventLimit))
        }

        migrateLastKnownDeviceFromDiagnosticsIfNeeded()

#if INATECK_SDK
        trace("Inateck SDK implementation initialized")
        BLEManager.shared.disconnectHandler = { [weak self] device in
            Task { @MainActor in
                self?.handleSDKDisconnect(device)
            }
        }
        availabilityMonitor = CBCentralManager(delegate: self, queue: nil)
#else
        trace("Simulator mock implementation initialized")
        if ProcessInfo.processInfo.arguments.contains("-demoBluetoothConnected") {
            let device = Self.simulatorDevice
            devices = [device]
            state = .connected(device)
            configurationState = .ready
            illuminationState = .off
            tuningState = .matched(applied: false)
            clearPersistedSymbologySnapshot(deviceID: device.id)
            recordAppliedSymbologyMode(.unrestricted)
            defaults.set(device.id, forKey: Self.preferredDeviceIDKey)
            rememberDevice(device)
        }
#endif
    }

    func startDiscovery() {
        trace("Discovery requested")
#if INATECK_SDK
        guard !sdkDiscoveryIsRunning else {
            trace("Discovery request ignored: scan already running")
            return
        }
        guard bluetoothIsAuthorized else { return }
        guard let availabilityMonitor, availabilityMonitor.state == .poweredOn else {
            pendingDiscovery = true
            switch availabilityMonitor?.state {
            case .poweredOff:
                state = .unavailable(AppLocalization.string("Bluetoothがオフです。"))
            case .unauthorized:
                state = .unavailable(AppLocalization.string("Bluetoothの利用が許可されていません。"))
            case .unsupported:
                state = .unavailable(AppLocalization.string("この端末ではBluetoothを利用できません。"))
            case .resetting:
                state = .unavailable(AppLocalization.string("Bluetoothを再準備しています…"))
            case .unknown, .none:
                state = .searching
            case .poweredOn:
                break
            @unknown default:
                state = .unavailable(AppLocalization.string("Bluetoothの状態を取得できません。"))
            }
            trace("Discovery deferred until Core Bluetooth is ready")
            return
        }

        pendingDiscovery = false
        sdkDiscoveryIsRunning = true
        devices = []
        mergeSDKCachedDevices()
        appendReconnectableDeviceIfNeeded()
        state = .searching

        BLEManager.shared.scanDevices(timeoutAfter: Self.discoveryTimeout) { [weak self] scanState in
            Task { @MainActor in
                self?.handleSDKScanState(scanState)
            }
        }
#else
        state = .searching
        let device = Self.simulatorDevice
        devices = [device]
        state = .idle

        if reconnectDeviceID == device.id {
            reconnectDeviceID = nil
            connect(device)
        }
#endif
    }

    func stopDiscovery() {
        trace("Discovery stop requested")
#if INATECK_SDK
        pendingDiscovery = false
        sdkDiscoveryIsRunning = false
        BLEManager.shared.stopScan()
#endif
        if case .searching = state {
            state = .idle
        }
    }

    func connect(
        _ device: BluetoothScannerDevice,
        timeout: TimeInterval = BluetoothScannerService.connectionTimeout
    ) {
        trace("Connect requested: \(device.name) [\(device.id)]")
#if INATECK_SDK
        // 同じSDKデバイスへconnectを重ねると、片方の失敗応答が接続済みの状態を
        // 消したりscan callbackが二重登録されたりする。接続中・接続済みなら無視する。
        if let connectedSDKDevice, connectedSDKDevice.uuid == device.id {
            trace("Connect request ignored: already connected to \(device.name)")
            return
        }
        if isConnecting {
            trace("Connect request ignored: a connection is already in progress")
            return
        }
        guard let sdkDevice = sdkDevices[device.id]
                ?? BLEManager.shared.devices.first(where: { $0.uuid == device.id }) else {
            rememberDevice(device)
            reconnectDeviceID = device.id
            trace("Known scanner is not in the SDK cache; discovering before reconnect")
            startDiscovery()
            return
        }

        automaticReconnectTask?.cancel()
        automaticReconnectTask = nil
        reconnectDeviceID = nil
        state = .connecting(device)
        // The first connection can present an iOS bonding dialog. Five seconds
        // is enough for scanning, but not for the user to approve that dialog
        // and for the SDK to finish service discovery.
        sdkDevice.connect(timeout: timeout) { [weak self] value in
            // 複数の`Task { @MainActor }`は実行順序が保証されないため、QRとCode 128が
            // 連続して通知されたときの逆転を防ぐ目的で、FIFOのメインキューへ直接積む。
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    guard let payload = Self.decodedSDKScanPayload(value) else {
                        self.trace(
                            "SDK callback ignored because it was not a complete scan payload "
                                + "(\(value.utf8.count) UTF-8 bytes)"
                        )
                        return
                    }
                    self.receiveCode(payload)
                }
            }
        } completion: { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .success:
                    self.connectedSDKDevice = sdkDevice
                    self.sdkOutputConfigurationCompleted = false
                    self.connectedScannerSettings = nil
                    self.connectedSymbologyValues = nil
                    self.connectedScannerSettingsAreFresh = false
                    self.settingCommandGeneration += 1
                    self.settingCommandInFlight = false
                    self.settingCommandTimeoutTask?.cancel()
                    self.settingCommandTimeoutTask = nil
                    self.manualDisconnectInProgress = false
                    self.configurationRecoveryTask?.cancel()
                    self.configurationRecoveryTask = nil
                    self.configurationRecoveryAttempt = 0
                    self.configurationState = .configuring
                    self.defaults.set(device.id, forKey: Self.preferredDeviceIDKey)
                    self.rememberDevice(device)
                    self.state = .connected(device)
                    self.automaticReconnectAttempt = 0
                    self.connectedLinkBaselineState = sdkDevice.connectState
                    self.illuminationState = .unknown
                    self.pendingIlluminationRequest = nil
                    self.tuningState = .unknown
                    self.trace(
                        "Connected: \(device.name) [\(device.id)] "
                            + "(SDK link state \(String(describing: sdkDevice.connectState)))"
                    )
                    self.finishGATTSwitchRediscovery()
                    self.startLinkLivenessMonitor()
                    self.ensureGATTMode(for: sdkDevice, appDevice: device)
                case .failure(let error):
                    self.connectedSDKDevice = nil
                    self.configurationState = .unavailable
                    self.state = .failed(
                        AppLocalization.string("接続できませんでした: \(error.localizedDescription)")
                    )
                    self.trace("Connection failed: \(error.localizedDescription)")
                    if self.gattSwitchOrigin != nil {
                        self.scheduleGATTSwitchRediscovery(reason: "connection failure")
                    } else {
                        self.scheduleAutomaticReconnect(reason: "connection failure")
                    }
                }
            }
        }
#else
        reconnectDeviceID = nil
        if !devices.contains(device) { devices.append(device) }
        state = .connecting(device)
        defaults.set(device.id, forKey: Self.preferredDeviceIDKey)
        rememberDevice(device)
        state = .connected(device)
        configurationState = .ready
        // 実機と同じく、接続ごとに照明OFF（常時消灯）を適用した状態から始める。
        illuminationState = .off
        tuningState = .matched(applied: false)
        let mode = BluetoothScannerSymbologyMode(expectedCode: expectedCode)
        if mode == .unrestricted {
            clearPersistedSymbologySnapshot(deviceID: device.id)
        } else {
            persistSimulatorSymbologySnapshotIfNeeded()
            recordPendingSymbologyMode(mode)
        }
        recordAppliedSymbologyMode(mode)
#endif
    }

    func disconnect() {
        trace("Manual disconnect requested")
#if INATECK_SDK
        automaticReconnectTask?.cancel()
        automaticReconnectTask = nil
        automaticReconnectAttempt = 0
        finishGATTSwitchRediscovery()
#endif
        reconnectDeviceID = nil
        defaults.removeObject(forKey: Self.preferredDeviceIDKey)
        expectedCode = nil

#if INATECK_SDK
        symbologyConfigurationRevision += 1
        guard let sdkDevice = connectedSDKDevice else {
            configurationState = .unavailable
            state = .idle
            return
        }
        manualDisconnectInProgress = true
        restoreSafeBaselineBeforeDisconnect(sdkDevice)
#else
        if let deviceID = connectedDevice?.id {
            clearPersistedSymbologySnapshot(deviceID: deviceID)
        }
        recordAppliedSymbologyMode(.unrestricted)
        configurationState = .unavailable
        illuminationState = .unknown
        tuningState = .unknown
        state = .idle
#endif
    }

    func reconnectPreferredDevice() {
        guard !isConnected,
              !isConnecting,
              reconnectDeviceID == nil,
              let preferredID = defaults.string(forKey: Self.preferredDeviceIDKey),
              !preferredID.isEmpty else { return }

#if INATECK_SDK
        if let cachedSDKDevice = sdkDevices[preferredID]
                ?? BLEManager.shared.devices.first(where: { $0.uuid == preferredID }) {
            let device = BluetoothScannerDevice(
                id: cachedSDKDevice.uuid,
                name: cachedSDKDevice.name ?? cachedSDKDevice.productName ?? "Inateck Scanner"
            )
            sdkDevices[device.id] = cachedSDKDevice
            trace(
                "Preferred-device reconnect using SDK cache: \(device.name) "
                    + "[\(device.id)] state=\(String(describing: cachedSDKDevice.connectState))"
            )
            connect(device, timeout: Self.automaticReconnectTimeout)
            return
        }
#endif

        reconnectDeviceID = preferredID
        trace("Preferred-device reconnect requested: \(preferredID)")
        startDiscovery()
    }

    func reconnectKnownDevice() {
        guard !isConnected, let device = reconnectableDevice else {
            if !isConnected {
                state = .failed(AppLocalization.string("再接続できるスキャナがありません。スキャナを検索してください。"))
            }
            return
        }
        trace("Manual reconnect requested for known scanner: \(device.name) [\(device.id)]")
        connect(device)
    }

    func setApplicationActive(_ isActive: Bool) {
#if INATECK_SDK
        applicationIsActive = isActive
        if isActive {
            automaticReconnectAttempt = 0
            verifyLinkLiveness(reason: "foreground")
            reconnectPreferredDevice()
            if gattSwitchOrigin != nil, !isConnected, !isConnecting {
                scheduleGATTSwitchRediscovery(reason: "foreground")
            }
        } else {
            automaticReconnectTask?.cancel()
            automaticReconnectTask = nil
            gattSwitchRediscoveryTask?.cancel()
            gattSwitchRediscoveryTask = nil
            gattSwitchRediscoveryPending = false
            if sdkDiscoveryIsRunning {
                stopDiscovery()
            }
        }
#else
        if isActive {
            reconnectPreferredDevice()
        }
#endif
    }

    /// 現在の業務工程に合わせ、実機が報告した全バーコード種別から
    /// 照合中はQRとCode 128の両方だけを有効にする。工程間では設定を書き換えず、
    /// 読み取り順序はアプリ側で検証する。`nil`では接続前の設定を正確に復元する。
    func setExpectedCode(_ expectedCode: ExpectedCode?) {
        let mode = BluetoothScannerSymbologyMode(expectedCode: expectedCode)
        let previousExpectedCode = self.expectedCode
        let previousMode = BluetoothScannerSymbologyMode(expectedCode: previousExpectedCode)
        self.expectedCode = expectedCode

#if INATECK_SDK
        // QR→Code 128やQR読み直しは論理工程だけを更新する。同じsessionCodesを
        // 適用中なら追加コマンドを積まず、実行中のGATT処理とトリガー入力を競合させない。
        if previousMode == mode, settingCommandInFlight {
            if previousExpectedCode != expectedCode {
                trace("Logical scan step changed while scanner session mode is still configuring")
            }
            return
        }
#endif

        // 物理モードが同じなら論理工程が変わっても再送しない。照合中の
        // setSettingInfoはセッション開始時の1回だけに限定する。
        guard !isReadyForScanning || persistedSymbologyMode != mode else {
            if previousExpectedCode != expectedCode, previousMode == mode {
                trace("Logical scan step changed; scanner remains in \(mode.rawValue)")
            }
            return
        }

#if INATECK_SDK
        symbologyConfigurationRevision += 1
        guard connectedSDKDevice != nil else {
            configurationState = .unavailable
            return
        }
        configurationState = .configuring
        guard sdkOutputConfigurationCompleted else { return }
        applyExpectedCodeConfiguration(revision: symbologyConfigurationRevision)
#else
        guard isConnected else {
            configurationState = .unavailable
            return
        }
        if let simulatedConfigurationFailure {
            configurationState = .failed(simulatedConfigurationFailure)
            return
        }
        if mode == .unrestricted {
            if let deviceID = connectedDevice?.id {
                clearPersistedSymbologySnapshot(deviceID: deviceID)
            }
        } else {
            persistSimulatorSymbologySnapshotIfNeeded()
            recordPendingSymbologyMode(mode)
        }
        recordAppliedSymbologyMode(mode)
        configurationState = .ready
#endif
    }

    /// `.failed` で止まった読み取り設定を、設定の再取得からやり直す。
    /// 利用者の入力元再選択、前景復帰、失敗後の自動復旧から呼ぶ。
    /// 設定失敗は接続断ではないため、リンクを維持したまま同じ端末で再試行する。
    func retryConfiguration() {
        guard case .failed = configurationState else { return }
#if INATECK_SDK
        guard let sdkDevice = connectedSDKDevice,
              let device = connectedDevice,
              !settingCommandInFlight,
              !manualDisconnectInProgress else { return }
        configurationRecoveryTask?.cancel()
        configurationRecoveryTask = nil
        trace("Scanner configuration retry requested")
        configurationState = .configuring
        connectedScannerSettings = nil
        connectedSymbologyValues = nil
        connectedScannerSettingsAreFresh = false
        symbologyConfigurationRevision += 1
        ensureGATTMode(for: sdkDevice, appDevice: device)
#else
        guard isConnected else { return }
        simulatedConfigurationFailure = nil
        trace("Simulator configuration retry requested")
        let mode = BluetoothScannerSymbologyMode(expectedCode: expectedCode)
        if mode == .unrestricted {
            if let deviceID = connectedDevice?.id {
                clearPersistedSymbologySnapshot(deviceID: deviceID)
            }
        } else {
            persistSimulatorSymbologySnapshotIfNeeded()
            recordPendingSymbologyMode(mode)
        }
        recordAppliedSymbologyMode(mode)
        configurationState = .ready
#endif
    }

    /// 読取中の照明を点灯（ON=読取中点灯）／消灯（OFF=常時消灯）へ切り替える。
    /// Android版と同じく、接続ごとに読み取り設定の確認後へOFFを適用し、
    /// 書込後にSDKから再取得して一致したときだけ確定表示する。値はスキャナー本体に
    /// 保存され、切断時には復元しない（工程用のシンボロジー復元とは別扱い）。
    func setIllumination(_ enabled: Bool) {
        guard isConnected else { return }
#if INATECK_SDK
        guard let sdkDevice = connectedSDKDevice, !manualDisconnectInProgress else { return }
        guard configurationState == .ready, !settingCommandInFlight else {
            pendingIlluminationRequest = enabled
            trace("Illumination request deferred until the current setting command completes")
            return
        }
        if case .applying = illuminationState {
            pendingIlluminationRequest = enabled
            return
        }
        pendingIlluminationRequest = nil
        illuminationState = .applying
        settingCommandGeneration += 1
        let generation = settingCommandGeneration
        settingCommandInFlight = true
        trace("Applying scanner illumination \(enabled ? "on" : "off") (generation \(generation))")
        scheduleSettingCommandTimeout(
            generation: generation,
            sdkDevice: sdkDevice,
            label: "illumination",
            duration: Self.illuminationCommandTimeout
        )

        let failureMessage = AppLocalization.string("照明設定を確認できませんでした。再操作してください。")
        // 機器が返した最新のarea/nameで書き、再取得した値が一致するまで確定しない。
        sdkDevice.messageManager.getSettingInfo { [weak self] result in
            Task { @MainActor in
                guard let self, generation == self.settingCommandGeneration else { return }
                guard case .success(let settings) = result else {
                    self.finishIllumination(
                        generation,
                        state: .failed(failureMessage),
                        message: "Illumination setting query failed"
                    )
                    return
                }
                guard let setting = Self.illuminationSetting(from: settings),
                      let command = Self.illuminationCommand(settings: settings, enabled: enabled) else {
                    self.finishIllumination(
                        generation,
                        state: .unsupported,
                        message: "Scanner inventory has no usable illumination setting"
                    )
                    return
                }
                sdkDevice.messageManager.setSettingInfo(with: command) { [weak self] written in
                    Task { @MainActor in
                        guard let self, generation == self.settingCommandGeneration else { return }
                        if case .failure(let error) = written {
                            self.finishIllumination(
                                generation,
                                state: .failed(failureMessage),
                                message: "Illumination write failed: \(error.localizedDescription)"
                            )
                            return
                        }
                        sdkDevice.messageManager.getSettingInfo { [weak self] verified in
                            Task { @MainActor in
                                guard let self, generation == self.settingCommandGeneration else { return }
                                guard case .success(let verifiedSettings) = verified,
                                      Self.illuminationConfirmed(
                                        settings: verifiedSettings,
                                        area: setting.area,
                                        enabled: enabled
                                      ) else {
                                    self.finishIllumination(
                                        generation,
                                        state: .failed(failureMessage),
                                        message: "Illumination readback did not confirm the requested value"
                                    )
                                    return
                                }
                                // 再取得したinventoryはこの接続の最新値なので、後続の
                                // チューニング差分判定にも使う。
                                self.connectedScannerSettings = verifiedSettings
                                self.finishIllumination(
                                    generation,
                                    state: enabled ? .on : .off,
                                    message: "Scanner illumination confirmed \(enabled ? "on" : "off")"
                                )
                            }
                        }
                    }
                }
            }
        }
#else
        illuminationState = enabled ? .on : .off
#endif
    }

    /// SimulatorのUIテストと単体テストだけで使用する入力フック。
    func simulateScan(_ value: String) {
#if !INATECK_SDK
        guard isConnected else { return }
        receiveCode(value)
#endif
    }

    /// Simulatorテスト用: 読み取り設定の失敗を再現し、`retryConfiguration()`まで
    /// `setExpectedCode`を失敗させ続ける。
    func simulateConfigurationFailure(_ message: String) {
#if !INATECK_SDK
        guard isConnected else { return }
        simulatedConfigurationFailure = message
        configurationState = .failed(message)
#endif
    }

    private func receiveCode(_ rawValue: String) {
        guard isReadyForScanning else {
            trace("Scan callback ignored because scanner configuration is not ready")
            return
        }
        let value = Self.normalizedPayload(rawValue)
        guard !value.isEmpty else { return }

        let receivedAt = now()
        if let lastDeliveredCode,
           lastDeliveredCode.value == value,
           receivedAt.timeIntervalSince(lastDeliveredCode.date) < Self.duplicateInterval {
            return
        }

        lastDeliveredCode = (value, receivedAt)
        trace("Scan callback accepted (\(value.utf8.count) UTF-8 bytes)")
        onCode?(value)
    }

    static func normalizedPayload(_ rawValue: String) -> String {
        var value = rawValue
        while let last = value.unicodeScalars.last,
              last == "\r" || last == "\n" || last.value == 0 {
            value.removeLast()
        }
        return value
    }

    /// The pinned iOS SDK returns the parser library's JSON result rather than
    /// the scan text shown in its demo. Keep accepting a direct string for
    /// compatibility with SDK revisions that already unwrap the scan value.
    static func decodedSDKScanPayload(_ callbackValue: String) -> String? {
        guard let data = callbackValue.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any] else {
            return callbackValue
        }

        func integer(_ value: Any?) -> Int? {
            if let number = value as? NSNumber { return number.intValue }
            if let string = value as? String { return Int(string) }
            return nil
        }

        // InateckScannerBleKit 2024-05-20 returns this final parser shape.
        // Requiring source_code as well avoids interpreting an ordinary JSON
        // barcode containing a generic `code` field as an SDK envelope.
        if root["source_code"] is String {
            guard integer(root["status"]) == 0,
                  let code = root["code"] as? String else { return nil }
            return code
        }

        // Also accept the scanner_lib notification envelope so this remains
        // compatible if a future SDK forwards the parser's intermediate JSON.
        guard root["notify_type"] != nil else { return callbackValue }
        guard integer(root["notify_type"]) == 1,
              integer(root["notify_status"]) == 1,
              let values = root["notify_data"] as? [Any] else { return nil }

        var bytes: [UInt8] = []
        bytes.reserveCapacity(values.count)
        for value in values {
            guard let integerValue = integer(value), (0...255).contains(integerValue) else {
                return nil
            }
            bytes.append(UInt8(integerValue))
        }
        return String(data: Data(bytes), encoding: .utf8)
    }

    private func rememberDevice(_ device: BluetoothScannerDevice) {
        defaults.set(device.id, forKey: Self.lastKnownDeviceIDKey)
        defaults.set(device.name, forKey: Self.lastKnownDeviceNameKey)
        appendDeviceIfNeeded(device)
    }

    /// 既存版で手動切断した直後はpreferred IDが消えており、専用の最終接続端末キーも
    /// まだ存在しない。過去の「接続成功」診断だけを移行元にして、アップデート後も
    /// 電源OFF／ONやGATT設定のやり直しなしで再接続候補を表示する。
    private func migrateLastKnownDeviceFromDiagnosticsIfNeeded() {
        guard defaults.string(forKey: Self.lastKnownDeviceIDKey) == nil else { return }

        let prefix = "Connected: "
        for event in diagnosticEvents.reversed() where event.message.hasPrefix(prefix) {
            guard event.message.hasSuffix("]"),
                  let bracket = event.message.lastIndex(of: "[") else { continue }

            let idStart = event.message.index(after: bracket)
            let idEnd = event.message.index(before: event.message.endIndex)
            let id = String(event.message[idStart..<idEnd])
            guard UUID(uuidString: id) != nil else { continue }

            let nameStart = event.message.index(event.message.startIndex, offsetBy: prefix.count)
            let rawName = event.message[nameStart..<bracket]
            let name = rawName.trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { continue }

            rememberDevice(BluetoothScannerDevice(id: id, name: name))
            return
        }
    }

    private func appendReconnectableDeviceIfNeeded() {
        guard let device = reconnectableDevice else { return }
        appendDeviceIfNeeded(device)
    }

    private func appendDeviceIfNeeded(_ device: BluetoothScannerDevice) {
        if let index = devices.firstIndex(where: { $0.id == device.id }) {
            devices[index] = device
        } else {
            devices.append(device)
        }
    }

#if INATECK_SDK
    private func mergeSDKCachedDevices() {
        for sdkDevice in BLEManager.shared.devices {
            let device = BluetoothScannerDevice(
                id: sdkDevice.uuid,
                name: sdkDevice.name ?? sdkDevice.productName ?? "Inateck Scanner"
            )
            sdkDevices[device.id] = sdkDevice
            appendDeviceIfNeeded(device)
        }
    }

    private var bluetoothIsAuthorized: Bool {
        switch CBManager.authorization {
        case .denied, .restricted:
            state = .unavailable(
                AppLocalization.string("Bluetoothの利用が許可されていません。設定アプリで許可してください。")
            )
            return false
        case .allowedAlways, .notDetermined:
            return true
        @unknown default:
            state = .unavailable(AppLocalization.string("Bluetoothを利用できません。"))
            return false
        }
    }

    private func handleSDKScanState(_ scanState: BLEManager.ScanStatus) {
        switch scanState {
        case .start:
            sdkDiscoveryIsRunning = true
            state = .searching
            trace("SDK discovery started")
        case .scan(let sdkDevice):
            let device = BluetoothScannerDevice(
                id: sdkDevice.uuid,
                name: sdkDevice.name ?? sdkDevice.productName ?? "Inateck Scanner"
            )
            sdkDevices[device.id] = sdkDevice
            trace("SDK discovered: \(device.name) [\(device.id)]")
            if let index = devices.firstIndex(where: { $0.id == device.id }) {
                devices[index] = device
            } else {
                devices.append(device)
            }

            if reconnectDeviceID == device.id {
                reconnectDeviceID = nil
                BLEManager.shared.stopScan()
                connect(device, timeout: Self.automaticReconnectTimeout)
            }
        case .stop:
            sdkDiscoveryIsRunning = false
            if case .searching = state {
                state = .idle
            }
            reconnectDeviceID = nil
            trace("SDK discovery stopped with \(devices.count) device(s)")
            if gattSwitchRediscoveryPending {
                resolveGATTSwitchTarget()
                return
            }
            if !isConnected, !isConnecting {
                scheduleAutomaticReconnect(reason: "preferred device not found")
            }
        @unknown default:
            sdkDiscoveryIsRunning = false
            state = .failed(AppLocalization.string("Bluetoothスキャナの検索状態を取得できませんでした。"))
            reconnectDeviceID = nil
            scheduleAutomaticReconnect(reason: "unknown discovery state")
        }
    }

    private func handleSDKDisconnect(_ sdkDevice: BLEDevice) {
        guard connectedSDKDevice?.uuid == sdkDevice.uuid
                || connectedDevice?.id == sdkDevice.uuid else { return }
        cancelSDKOutputConfiguration()
        connectedSDKDevice = nil
        sdkOutputConfigurationCompleted = false
        connectedScannerSettings = nil
        connectedSymbologyValues = nil
        connectedScannerSettingsAreFresh = false
        settingCommandGeneration += 1
        settingCommandInFlight = false
        settingCommandTimeoutTask?.cancel()
        settingCommandTimeoutTask = nil
        configurationRecoveryTask?.cancel()
        configurationRecoveryTask = nil
        configurationRecoveryAttempt = 0
        stopLinkLivenessMonitor()
        configurationState = .unavailable
        illuminationState = .unknown
        pendingIlluminationRequest = nil
        tuningState = .unknown
        if manualDisconnectInProgress {
            state = .idle
            trace("Manual disconnect completed")
            return
        }
        if gattModeChangeInProgress {
            gattModeChangeInProgress = false
            state = .idle
            trace("Disconnected while applying GATT mode: \(sdkDevice.uuid)")
            scheduleGATTSwitchRediscovery(reason: "GATT mode applied")
        } else {
            state = .failed(AppLocalization.string("Bluetoothスキャナとの接続が切れました。"))
            trace("Unexpected disconnect: \(sdkDevice.uuid)")
            scheduleAutomaticReconnect(reason: "unexpected disconnect")
        }
    }

    private func ensureGATTMode(
        for sdkDevice: BLEDevice,
        appDevice: BluetoothScannerDevice,
        settingsAttempt: Int = 0
    ) {
        sdkDevice.messageManager.getSettingInfo { [weak self] result in
            Task { @MainActor in
                guard let self, self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }
                switch result {
                case .success(let settings):
                    guard Self.hasRequiredSymbologySettings(settings) else {
                        self.retryScannerSettings(
                            for: sdkDevice,
                            appDevice: appDevice,
                            after: settingsAttempt,
                            reason: "incomplete response"
                        )
                        return
                    }
                    self.defaults.set(settings, forKey: Self.cachedScannerSettingsKey)
                    self.continueScannerConfiguration(
                        with: settings,
                        sdkDevice: sdkDevice,
                        appDevice: appDevice,
                        settingsAreFresh: true
                    )
                case .failure(let error):
                    self.trace("Bluetooth mode query failed: \(error.localizedDescription)")
                    self.retryScannerSettings(
                        for: sdkDevice,
                        appDevice: appDevice,
                        after: settingsAttempt,
                        reason: "query failure"
                    )
                }
            }
        }
    }

    private func retryScannerSettings(
        for sdkDevice: BLEDevice,
        appDevice: BluetoothScannerDevice,
        after attempt: Int,
        reason: String
    ) {
        if attempt >= Self.settingsRetryLimit {
            if let cachedSettings = defaults.string(forKey: Self.cachedScannerSettingsKey),
               Self.hasRequiredSymbologySettings(cachedSettings) {
                trace("Scanner settings unavailable; using cached setting areas")
                continueScannerConfiguration(
                    with: cachedSettings,
                    sdkDevice: sdkDevice,
                    appDevice: appDevice,
                    settingsAreFresh: false
                )
            } else {
                configurationState = .failed(
                    AppLocalization.string("スキャナーの全バーコード設定を取得できませんでした。電源を入れ直してください。")
                )
                trace("Scanner settings unavailable after \(attempt + 1) attempts")
                scheduleConfigurationRecovery()
            }
            return
        }

        let nextAttempt = attempt + 1
        trace("Scanner settings \(reason); retrying (attempt \(nextAttempt + 1))")
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self,
                  self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }
            self.ensureGATTMode(
                for: sdkDevice,
                appDevice: appDevice,
                settingsAttempt: nextAttempt
            )
        }
    }

    private func continueScannerConfiguration(
        with settings: String,
        sdkDevice: BLEDevice,
        appDevice: BluetoothScannerDevice,
        settingsAreFresh: Bool
    ) {
        connectedScannerSettings = settings
        connectedSymbologyValues = Self.symbologySettingValues(from: settings)
        connectedScannerSettingsAreFresh = settingsAreFresh
        migrateLegacySymbologyRecoveryIfNeeded(
            deviceID: sdkDevice.uuid,
            settingsAreFresh: settingsAreFresh
        )
        trace(
            "Scanner reported \(connectedSymbologyValues?.count ?? 0) barcode types"
                + (settingsAreFresh ? "" : " (cached metadata)")
        )
        // Phase 0（#59）: 機器が報告する設定nameと、読取品質に関わる少数の設定値だけを
        // 診断へ残す。payload・device識別子・接頭辞/接尾辞などの文字設定は含めない。
        if let items = Self.scannerSettingItems(from: settings) {
            let names = items.compactMap { $0["name"] as? String }.sorted().joined(separator: ",")
            trace("Scanner inventory names: \(names)")
            let tuning = items.compactMap { item -> String? in
                guard let name = item["name"] as? String,
                      Self.tuningSettingNames.contains(name),
                      let value = Self.integerSettingValue(item["value"]) else { return nil }
                return "\(name)=\(value)"
            }
            if !tuning.isEmpty {
                trace("Scanner tuning values: \(tuning.sorted().joined(separator: ","))")
            }
        }
        let inventoryMode = Self.settingValue(named: "inventory_mode", from: settings)
        let automaticCacheUpload = Self.settingValue(named: "auto_upload_cache", from: settings)
        let clearCacheAtStartup = Self.settingValue(named: "start_up_clean_cache", from: settings)
        trace(
            "Scanner data modes: inventory=\(inventoryMode.map(String.init) ?? "unknown") "
                + "autoUpload=\(automaticCacheUpload.map(String.init) ?? "unknown") "
                + "clearAtStartup=\(clearCacheAtStartup.map(String.init) ?? "unknown")"
        )
        guard let mode = Self.bluetoothMode(from: settings) else {
            trace("Could not determine the scanner Bluetooth mode")
            startSymbologyConfiguration(deviceID: sdkDevice.uuid)
            return
        }
        guard mode != 2 else {
            gattModeChangeInProgress = false
            trace("Scanner Bluetooth mode confirmed: GATT (2)")
            startSymbologyConfiguration(deviceID: sdkDevice.uuid)
            return
        }

        gattModeChangeInProgress = true
        gattSwitchOrigin = appDevice
        gattSwitchRediscoveryAttempt = 0
        state = .connecting(appDevice)
        trace("Scanner Bluetooth mode is \(mode); applying GATT mode (2)")
        let command = """
        [{"area":"1","value":"0","name":"bt_mode_low"},\
        {"area":"31","value":"1","name":"bt_mode_high"}]
        """
        sdkDevice.messageManager.setSettingInfo(with: command) { [weak self] updateResult in
            Task { @MainActor in
                guard let self else { return }
                switch updateResult {
                case .success:
                    self.trace("GATT mode setting accepted; restarting scanner")
                    sdkDevice.messageManager.setRestart { [weak self] restartResult in
                        Task { @MainActor in
                            guard let self else { return }
                            switch restartResult {
                            case .success:
                                self.trace("Scanner restart accepted after GATT mode change")
                                self.scheduleGATTRestartFallback(
                                    for: sdkDevice,
                                    appDevice: appDevice
                                )
                            case .failure(let error):
                                self.gattModeChangeInProgress = false
                                self.gattSwitchOrigin = nil
                                self.state = .failed(
                                    AppLocalization.string(
                                        "GATT設定後にスキャナを再起動できませんでした: \(error.localizedDescription)"
                                    )
                                )
                                self.trace(
                                    "Scanner restart after GATT mode change failed: "
                                        + error.localizedDescription
                                )
                            }
                        }
                    }
                case .failure(let error):
                    self.gattModeChangeInProgress = false
                    self.gattSwitchOrigin = nil
                    self.state = .failed(AppLocalization.string("GATTモードへ切り替えられませんでした: \(error.localizedDescription)"))
                    self.trace("GATT mode setting failed: \(error.localizedDescription)")
                }
            }
        }
    }

    private func scheduleGATTRestartFallback(
        for sdkDevice: BLEDevice,
        appDevice: BluetoothScannerDevice
    ) {
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled, let self,
                  self.gattModeChangeInProgress,
                  self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }

            self.trace("Scanner restart did not close the link; reconnecting explicitly")
            sdkDevice.disconnect { [weak self] _ in
                Task { @MainActor in
                    guard let self else { return }
                    self.stopLinkLivenessMonitor()
                    self.connectedSDKDevice = nil
                    self.gattModeChangeInProgress = false
                    self.state = .idle
                    self.scheduleGATTSwitchRediscovery(reason: "GATT mode restart fallback")
                }
            }
        }
    }

    /// 切替元のリンクが閉じた後、再起動を待って再検索する。SDKの切断通知と
    /// 明示切断の完了が両方届いても、検索は1回だけ予約する。
    private func scheduleGATTSwitchRediscovery(reason: String) {
        guard let origin = gattSwitchOrigin, !gattSwitchRediscoveryPending else { return }
        // 切替元の識別子はもう広告されないことがあるため、自動再接続の対象から外す。
        // 接続できた切替先が、connectの成功時に新しい優先接続先になる。
        if defaults.string(forKey: Self.preferredDeviceIDKey) == origin.id {
            defaults.removeObject(forKey: Self.preferredDeviceIDKey)
        }
        automaticReconnectTask?.cancel()
        automaticReconnectTask = nil
        gattSwitchRediscoveryAttempt += 1
        guard gattSwitchRediscoveryAttempt <= Self.gattSwitchRediscoveryLimit else {
            abandonGATTSwitchRediscovery()
            return
        }
        gattSwitchRediscoveryPending = true
        trace(
            "GATT mode switch: searching for the restarted scanner "
                + "(attempt \(gattSwitchRediscoveryAttempt), \(reason))"
        )
        gattSwitchRediscoveryTask?.cancel()
        gattSwitchRediscoveryTask = Task { [weak self] in
            do {
                try await Task.sleep(for: Self.gattSwitchRediscoveryDelay)
            } catch {
                return
            }
            guard !Task.isCancelled, let self else { return }
            self.gattSwitchRediscoveryTask = nil
            self.startDiscovery()
        }
    }

    /// 再検索の終了時に接続先を決める。切替元の名前に含まれる末尾4桁で終わる別の
    /// スキャナーが1台だけあればそれが切替先、なければ識別子が変わらない機種として切替元へ戻る。
    private func resolveGATTSwitchTarget() {
        gattSwitchRediscoveryPending = false
        guard let origin = gattSwitchOrigin, !isConnected, !isConnecting else { return }
        let known = sdkDevices.values.map { sdkDevice in
            BluetoothScannerDevice(
                id: sdkDevice.uuid,
                name: sdkDevice.name ?? sdkDevice.productName ?? "Inateck Scanner"
            )
        }
        let successors = Self.gattModeSuccessors(of: origin, among: known)
        switch successors.count {
        case 1:
            let successor = successors[0]
            trace(
                "GATT mode switch: \(origin.name) [\(origin.id)] reappeared as "
                    + "\(successor.name) [\(successor.id)]; connecting"
            )
            connect(successor)
        case 0 where sdkDevices[origin.id] != nil:
            trace("GATT mode switch: no renamed scanner found; reconnecting to \(origin.name) [\(origin.id)]")
            connect(origin, timeout: Self.automaticReconnectTimeout)
        case 0:
            scheduleGATTSwitchRediscovery(reason: "scanner not found")
        default:
            trace("GATT mode switch: \(successors.count) candidate scanners; waiting for manual selection")
            abandonGATTSwitchRediscovery()
        }
    }

    /// 切替先を見つけられなかった。切替元の識別子を「以前接続したスキャナ」から外し、
    /// 検索結果から選び直すよう案内する。
    private func abandonGATTSwitchRediscovery() {
        guard let origin = gattSwitchOrigin else { return }
        finishGATTSwitchRediscovery()
        if defaults.string(forKey: Self.lastKnownDeviceIDKey) == origin.id {
            defaults.removeObject(forKey: Self.lastKnownDeviceIDKey)
            defaults.removeObject(forKey: Self.lastKnownDeviceNameKey)
        }
        state = .failed(
            AppLocalization.string("スキャナをGATTモードへ切り替えました。「スキャナを検索」で表示されたスキャナを選んで接続してください。")
        )
        trace("GATT mode switch: restarted scanner not found; waiting for manual selection")
    }

    private func finishGATTSwitchRediscovery() {
        gattSwitchRediscoveryTask?.cancel()
        gattSwitchRediscoveryTask = nil
        gattSwitchRediscoveryPending = false
        gattSwitchOrigin = nil
        gattSwitchRediscoveryAttempt = 0
    }

    /// 同じ接続でSDK出力設定（FF04）が済んでいれば繰り返さず、読み取り設定へ直接進む。
    private func startSymbologyConfiguration(deviceID: String) {
        if sdkOutputConfigurationCompleted {
            applyExpectedCodeConfiguration(revision: symbologyConfigurationRevision)
        } else {
            beginSDKOutputConfiguration(deviceID: deviceID)
        }
    }

    /// `.failed` のまま放置せず、一定間隔・有限回数で設定の再取得からやり直す。
    /// 利用者が入力元を再選択したときは即座に`retryConfiguration()`が走る。
    private func scheduleConfigurationRecovery() {
        guard connectedSDKDevice != nil,
              !manualDisconnectInProgress,
              configurationRecoveryAttempt < Self.configurationRecoveryLimit else {
            if connectedSDKDevice != nil {
                trace("Automatic configuration recovery exhausted; waiting for manual retry")
            }
            return
        }
        configurationRecoveryAttempt += 1
        let attempt = configurationRecoveryAttempt
        trace("Automatic configuration recovery scheduled (attempt \(attempt))")
        configurationRecoveryTask?.cancel()
        configurationRecoveryTask = Task { [weak self] in
            do {
                try await Task.sleep(for: Self.configurationRecoveryDelay)
            } catch {
                return
            }
            guard !Task.isCancelled, let self else { return }
            self.configurationRecoveryTask = nil
            guard case .failed = self.configurationState else { return }
            self.trace("Automatic configuration recovery attempt \(attempt) started")
            self.retryConfiguration()
        }
    }

    private static func bluetoothMode(from settings: String) -> Int? {
        guard let low = settingValue(named: "bt_mode_low", from: settings),
              let high = settingValue(named: "bt_mode_high", from: settings) else { return nil }
        return low + (high << 1)
    }

    private static func settingValue(named name: String, from settings: String) -> Int? {
        guard let data = settings.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any],
              let values = root["data"] as? [[String: Any]] else { return nil }
        guard let value = values.first(where: { $0["name"] as? String == name })?["value"] else {
            return nil
        }
        if let string = value as? String { return Int(string) }
        if let number = value as? NSNumber { return number.intValue }
        return nil
    }

    /// 読み取り設定がReadyになった直後に呼ぶ。接続後まだ確認していない照明は
    /// OFFへ揃え、設定コマンド中に受けた照明要求があれば最新の1件を適用する。
    private func settingsBecameReady() {
        if let pending = pendingIlluminationRequest {
            pendingIlluminationRequest = nil
            setIllumination(pending)
        } else if illuminationState == .unknown {
            setIllumination(false)
        } else {
            applyTuningProfileIfNeeded()
        }
    }

    private func finishIllumination(
        _ generation: Int,
        state: BluetoothScannerIlluminationState,
        message: String
    ) {
        guard generation == settingCommandGeneration else { return }
        settingCommandTimeoutTask?.cancel()
        settingCommandTimeoutTask = nil
        settingCommandInFlight = false
        illuminationState = state
        trace(message)
        // 照明処理中に工程が変わっていれば読み取り設定を先に進め、
        // その後で残っている照明要求を処理する。
        if configurationState == .configuring, connectedSDKDevice != nil {
            applyExpectedCodeConfiguration(revision: symbologyConfigurationRevision)
        }
        if !settingCommandInFlight, let pending = pendingIlluminationRequest {
            pendingIlluminationRequest = nil
            setIllumination(pending)
        }
        if !settingCommandInFlight {
            applyTuningProfileIfNeeded()
        }
    }

    /// 照明の確認が済んだ後、この接続でまだ確認していなければ読取チューニングを揃える。
    /// 差分がなければ書込を行わず、あれば1コマンドで書いて再取得で確認する。
    private func applyTuningProfileIfNeeded() {
        guard tuningState == .unknown,
              let sdkDevice = connectedSDKDevice,
              isConnected,
              configurationState == .ready,
              !settingCommandInFlight,
              !manualDisconnectInProgress,
              let settings = connectedScannerSettings else { return }
        switch illuminationState {
        case .unknown, .applying:
            return
        case .unsupported, .off, .on, .failed:
            break
        }

        guard let present = Self.tuningItemsPresent(in: settings), !present.isEmpty else {
            tuningState = .unsupported
            trace("Scanner inventory has none of the tuning items")
            return
        }
        let differences = Self.tuningDifferences(settings: settings)
        guard !differences.isEmpty else {
            tuningState = .matched(applied: false)
            trace("Scanner tuning already matches (\(present.count) items)")
            return
        }
        guard let command = Self.tuningCommand(settings: settings, items: differences) else {
            tuningState = .failed(AppLocalization.string("読取チューニングを適用できませんでした。"))
            trace("Scanner tuning command could not be built from the inventory")
            return
        }

        tuningState = .applying
        settingCommandGeneration += 1
        let generation = settingCommandGeneration
        settingCommandInFlight = true
        let summary = differences.map { "\($0.name)=\($0.value)" }.joined(separator: ",")
        trace("Applying scanner tuning (generation \(generation)): \(summary)")
        scheduleSettingCommandTimeout(
            generation: generation,
            sdkDevice: sdkDevice,
            label: "tuning",
            duration: Self.illuminationCommandTimeout
        )
        let failureMessage = AppLocalization.string("読取チューニングを確認できませんでした。")
        sdkDevice.messageManager.setSettingInfo(with: command) { [weak self] written in
            Task { @MainActor in
                guard let self, generation == self.settingCommandGeneration else { return }
                if case .failure(let error) = written {
                    self.finishTuning(
                        generation,
                        state: .failed(failureMessage),
                        message: "Scanner tuning write failed: \(error.localizedDescription)"
                    )
                    return
                }
                sdkDevice.messageManager.getSettingInfo { [weak self] verified in
                    Task { @MainActor in
                        guard let self, generation == self.settingCommandGeneration else { return }
                        guard case .success(let verifiedSettings) = verified else {
                            self.finishTuning(
                                generation,
                                state: .failed(failureMessage),
                                message: "Scanner tuning readback failed"
                            )
                            return
                        }
                        self.connectedScannerSettings = verifiedSettings
                        let remaining = Self.tuningDifferences(settings: verifiedSettings)
                        if remaining.isEmpty {
                            self.finishTuning(
                                generation,
                                state: .matched(applied: true),
                                message: "Scanner tuning confirmed (\(differences.count) items written)"
                            )
                        } else {
                            let unmatched = remaining.map(\.name).joined(separator: ",")
                            self.finishTuning(
                                generation,
                                state: .failed(failureMessage),
                                message: "Scanner tuning readback did not match: \(unmatched)"
                            )
                        }
                    }
                }
            }
        }
    }

    private func finishTuning(
        _ generation: Int,
        state: BluetoothScannerTuningState,
        message: String
    ) {
        guard generation == settingCommandGeneration else { return }
        settingCommandTimeoutTask?.cancel()
        settingCommandTimeoutTask = nil
        settingCommandInFlight = false
        tuningState = state
        trace(message)
        if configurationState == .configuring, connectedSDKDevice != nil {
            applyExpectedCodeConfiguration(revision: symbologyConfigurationRevision)
        }
        if !settingCommandInFlight, let pending = pendingIlluminationRequest {
            pendingIlluminationRequest = nil
            setIllumination(pending)
        }
    }

    private func startLinkLivenessMonitor() {
        linkLivenessTask?.cancel()
        linkLivenessTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: Self.linkLivenessInterval)
                } catch {
                    return
                }
                guard let self else { return }
                self.verifyLinkLiveness(reason: "periodic check")
            }
        }
    }

    private func stopLinkLivenessMonitor() {
        linkLivenessTask?.cancel()
        linkLivenessTask = nil
        connectedLinkBaselineState = nil
    }

    /// SDKの`disconnectHandler`が呼ばれないままリンクが落ちた場合に備え、
    /// SDKが保持するリンク状態から切断を検知する。接続直後の基準値から
    /// `disconnected`側へ変化したときだけ切断として扱い、通信は発生させない。
    private func verifyLinkLiveness(reason: String) {
        guard let sdkDevice = connectedSDKDevice, isConnected else { return }
        let linkState = sdkDevice.connectState
        guard linkState != connectedLinkBaselineState,
              linkState != .connected,
              linkState != .connecting else { return }
        trace(
            "SDK link state is \(String(describing: linkState)) without a disconnect "
                + "callback (\(reason)); treating the link as disconnected"
        )
        handleSDKDisconnect(sdkDevice)
    }

    private func scheduleAutomaticReconnect(reason: String) {
        guard applicationIsActive,
              !isConnected,
              automaticReconnectTask == nil,
              defaults.string(forKey: Self.preferredDeviceIDKey)?.isEmpty == false else { return }

        automaticReconnectAttempt += 1
        let delay = min(pow(2.0, Double(automaticReconnectAttempt - 1)) * 2.0, 15.0)
        trace(
            "Automatic reconnect scheduled in \(Int(delay))s "
                + "(attempt \(automaticReconnectAttempt), \(reason))"
        )

        automaticReconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            self.automaticReconnectTask = nil
            guard self.applicationIsActive, !self.isConnected else { return }
            self.trace("Automatic reconnect attempt \(self.automaticReconnectAttempt) started")
            self.reconnectPreferredDevice()
        }
    }

    /// BCST-47 can remain configured to acknowledge scans to its display while
    /// routing the payload away from the SDK notification characteristic. The
    /// current official scanner_lib exposes this as
    /// inateck_scanner_cmd_get_hid_output(1), which generates the six bytes
    /// below. Use a short-lived write-only Core Bluetooth client so the SDK
    /// remains the sole subscriber to FF01.
    private func beginSDKOutputConfiguration(deviceID: String) {
        guard !sdkOutputConfigurationInProgress else { return }
        guard let availabilityMonitor,
              availabilityMonitor.state == .poweredOn,
              let identifier = UUID(uuidString: deviceID),
              let peripheral = availabilityMonitor.retrievePeripherals(
                withIdentifiers: [identifier]
              ).first else {
            trace("SDK-output configuration could not retrieve the connected scanner")
            sdkOutputConfigurationCompleted = true
            applyExpectedCodeConfiguration(revision: symbologyConfigurationRevision)
            return
        }

        sdkOutputConfigurationInProgress = true
        sdkOutputConfigurationPeripheral = peripheral
        peripheral.delegate = self
        trace("SDK-output configuration connecting: \(deviceID)")
        // Core Bluetoothのconnect/discoverには期限がない。補助リンクの各段階の
        // コールバックが来なくても読取設定へ進めるよう、全体に上限を置く。
        scheduleSDKOutputConfigurationTimeout(for: peripheral)
        availabilityMonitor.connect(peripheral)
    }

    private func scheduleSDKOutputConfigurationTimeout(for peripheral: CBPeripheral) {
        sdkOutputConfigurationTimeoutTask?.cancel()
        sdkOutputConfigurationTimeoutTask = Task { [weak self] in
            do {
                try await Task.sleep(for: Self.sdkOutputConfigurationTimeout)
            } catch {
                return
            }
            guard !Task.isCancelled, let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.sdkOutputConfigurationTimeoutTask = nil
            self.finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration timed out; continuing with the current output setting"
            )
        }
    }

    private func handleSDKOutputConfigurationConnect(_ peripheral: CBPeripheral) {
        trace("SDK-output configuration connected; discovering FF00")
        peripheral.discoverServices([CBUUID(string: "FF00")])
    }

    private func handleSDKOutputConfigurationServices(
        _ peripheral: CBPeripheral,
        error: Error?
    ) {
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: "FF00") }) else {
            finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration could not discover FF00: "
                    + (error?.localizedDescription ?? "service missing")
            )
            return
        }
        peripheral.discoverCharacteristics([CBUUID(string: "FF04")], for: service)
    }

    private func handleSDKOutputConfigurationCharacteristics(
        _ peripheral: CBPeripheral,
        service: CBService,
        error: Error?
    ) {
        guard error == nil,
              let characteristic = service.characteristics?.first(where: {
                  $0.uuid == CBUUID(string: "FF04")
              }) else {
            finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration could not discover FF04: "
                    + (error?.localizedDescription ?? "characteristic missing")
            )
            return
        }

        let command = Data([0xF3, 0x03, 0x7F, 0x5E, 0x01, 0xD4])
        peripheral.writeValue(command, for: characteristic, type: .withoutResponse)
        trace("SDK-output configuration command written to FF04")
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self else { return }
            self.finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration write completed"
            )
        }
    }

    private func finishSDKOutputConfiguration(_ peripheral: CBPeripheral, message: String) {
        guard sdkOutputConfigurationInProgress,
              sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else { return }
        trace(message)
        sdkOutputConfigurationTimeoutTask?.cancel()
        sdkOutputConfigurationTimeoutTask = nil
        sdkOutputConfigurationInProgress = false
        sdkOutputConfigurationCompleted = true
        sdkOutputConfigurationPeripheral = nil
        peripheral.delegate = nil
        if peripheral.state != .disconnected {
            availabilityMonitor?.cancelPeripheralConnection(peripheral)
        }
        applyExpectedCodeConfiguration(revision: symbologyConfigurationRevision)
    }

    private func cancelSDKOutputConfiguration() {
        sdkOutputConfigurationTimeoutTask?.cancel()
        sdkOutputConfigurationTimeoutTask = nil
        guard let peripheral = sdkOutputConfigurationPeripheral else {
            sdkOutputConfigurationInProgress = false
            return
        }
        sdkOutputConfigurationInProgress = false
        sdkOutputConfigurationPeripheral = nil
        peripheral.delegate = nil
        if peripheral.state != .disconnected {
            availabilityMonitor?.cancelPeripheralConnection(peripheral)
        }
    }

    private func restoreSafeBaselineBeforeDisconnect(_ sdkDevice: BLEDevice) {
        configurationState = .configuring
        guard !settingCommandInFlight else {
            trace("Original symbology restore deferred until the current setting command completes")
            return
        }
        guard let snapshot = persistedSymbologySnapshot(for: sdkDevice.uuid) else {
            recordAppliedSymbologyMode(.unrestricted)
            performManualDisconnect(sdkDevice)
            return
        }
        guard let settings = connectedScannerSettings,
              let command = Self.symbologySettingCommand(values: snapshot.values, settings: settings) else {
            trace("Original symbology restore skipped because the saved settings do not match")
            performManualDisconnect(sdkDevice)
            return
        }

        settingCommandGeneration += 1
        let commandGeneration = settingCommandGeneration
        settingCommandInFlight = true
        trace(
            "Restoring original scanner mode "
                + "(generation \(commandGeneration), \(snapshot.values.count) barcode types)"
        )
        scheduleBaselineRestoreTimeout(
            generation: commandGeneration,
            sdkDevice: sdkDevice
        )
        sdkDevice.messageManager.setSettingInfo(with: command) { [weak self] result in
            Task { @MainActor in
                guard let self,
                      commandGeneration == self.settingCommandGeneration else { return }
                self.settingCommandTimeoutTask?.cancel()
                self.settingCommandTimeoutTask = nil
                self.settingCommandInFlight = false
                switch result {
                case .success:
                    self.connectedSymbologyValues = snapshot.values
                    self.connectedScannerSettingsAreFresh = true
                    self.clearPersistedSymbologySnapshot(deviceID: sdkDevice.uuid)
                    self.recordAppliedSymbologyMode(.unrestricted)
                    self.trace(
                        "Restored \(snapshot.values.count) original barcode settings before disconnect"
                    )
                case .failure(let error):
                    // 元設定と制限中の記録は消さず、次回接続時の復旧を必ず再試行する。
                    self.trace(
                        "Original symbology restore before disconnect failed: "
                            + error.localizedDescription
                    )
                }
                self.performManualDisconnect(sdkDevice)
            }
        }
    }

    private func performManualDisconnect(_ sdkDevice: BLEDevice) {
        settingCommandTimeoutTask?.cancel()
        settingCommandTimeoutTask = nil
        configurationRecoveryTask?.cancel()
        configurationRecoveryTask = nil
        stopLinkLivenessMonitor()
        manualDisconnectInProgress = true
        cancelSDKOutputConfiguration()
        sdkDevice.disconnect { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                self.connectedSDKDevice = nil
                self.sdkOutputConfigurationCompleted = false
                self.connectedScannerSettings = nil
                self.connectedSymbologyValues = nil
                self.connectedScannerSettingsAreFresh = false
                self.settingCommandGeneration += 1
                self.settingCommandInFlight = false
                self.settingCommandTimeoutTask?.cancel()
                self.settingCommandTimeoutTask = nil
                self.configurationState = .unavailable
                self.manualDisconnectInProgress = false
                switch result {
                case .success:
                    self.state = .idle
                    self.trace("Disconnected")
                case .failure(let error):
                    self.state = .failed(AppLocalization.string("切断に失敗しました: \(error.localizedDescription)"))
                    self.trace("Disconnect failed: \(error.localizedDescription)")
                }
            }
        }
    }

    private func applyExpectedCodeConfiguration(revision: Int, attempt: Int = 0) {
        guard revision == symbologyConfigurationRevision,
              let sdkDevice = connectedSDKDevice,
              let settings = connectedScannerSettings else {
            if connectedSDKDevice != nil, connectedScannerSettings == nil {
                trace("Symbology configuration deferred because scanner settings are unavailable")
            }
            return
        }
        guard !settingCommandInFlight else {
            trace("Symbology configuration deferred until the current setting command completes")
            return
        }

        let mode = BluetoothScannerSymbologyMode(expectedCode: expectedCode)

        // 復元待ちは接続中のスキャナー自身の分だけを見る。別のスキャナーの分は
        // そのスキャナーの次回接続で復元するため、ここでは残したまま先へ進む（#135）。
        let persisted = persistedSymbologySnapshot(for: sdkDevice.uuid)
        if mode == .unrestricted, persisted == nil {
            recordAppliedSymbologyMode(.unrestricted)
            configurationState = .ready
            trace(
                "Scanner barcode settings already match the pre-session state"
                    + pendingOtherScannerRestoreNote(excluding: sdkDevice.uuid)
            )
            settingsBecameReady()
            return
        }

        let snapshot: BluetoothScannerSymbologySnapshot
        if let persisted {
            snapshot = persisted
        } else {
            guard mode != .unrestricted,
                  connectedScannerSettingsAreFresh,
                  let currentValues = connectedSymbologyValues,
                  Self.hasRequiredSymbologyValues(currentValues) else {
                configurationState = .failed(
                    AppLocalization.string("スキャナーの現在の読み取り設定を保存できませんでした。電源を入れ直してください。")
                )
                trace("Fresh symbology settings are required before applying a restriction")
                scheduleConfigurationRecovery()
                return
            }
            snapshot = BluetoothScannerSymbologySnapshot(
                deviceID: sdkDevice.uuid,
                values: currentValues
            )
            persistSymbologySnapshot(snapshot)
        }

        guard let values = Self.symbologySettingValues(for: mode, original: snapshot.values),
              let command = Self.symbologySettingCommand(values: values, settings: settings) else {
            trace("Symbology configuration skipped because the complete setting set is unavailable")
            configurationState = .failed(
                AppLocalization.string("スキャナーから全バーコード種類の設定を取得できませんでした。カメラを使用してください。")
            )
            scheduleConfigurationRecovery()
            return
        }

        if mode != .unrestricted {
            recordPendingSymbologyMode(mode)
        }
        settingCommandGeneration += 1
        let commandGeneration = settingCommandGeneration
        settingCommandInFlight = true
        let requestedAt = ProcessInfo.processInfo.systemUptime
        trace(
            "Applying scanner mode \(mode.rawValue) "
                + "(generation \(commandGeneration), \(values.count) barcode types"
                + (attempt > 0 ? ", retry \(attempt)" : "") + ")"
        )
        scheduleSettingCommandTimeout(
            generation: commandGeneration,
            sdkDevice: sdkDevice,
            label: mode.rawValue,
            duration: Self.symbologyCommandTimeout
        )
        sdkDevice.messageManager.setSettingInfo(with: command) { [weak self] result in
            let callbackDelay = ProcessInfo.processInfo.systemUptime - requestedAt
            Task { @MainActor in
                guard let self,
                      commandGeneration == self.settingCommandGeneration else { return }
                self.settingCommandTimeoutTask?.cancel()
                self.settingCommandTimeoutTask = nil
                self.settingCommandInFlight = false
                guard self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }
                if self.manualDisconnectInProgress {
                    self.restoreSafeBaselineBeforeDisconnect(sdkDevice)
                    return
                }
                guard revision == self.symbologyConfigurationRevision else {
                    self.trace("Applying the latest symbology request after a superseded command")
                    self.applyExpectedCodeConfiguration(
                        revision: self.symbologyConfigurationRevision
                    )
                    return
                }
                print(
                    "[BluetoothScanner] Symbology SDK callback after "
                        + "\(String(format: "%.3f", callbackDelay))s"
                )
                switch result {
                case .success:
                    self.connectedSymbologyValues = values
                    self.connectedScannerSettingsAreFresh = true
                    if mode == .unrestricted {
                        self.clearPersistedSymbologySnapshot(deviceID: sdkDevice.uuid)
                    }
                    self.recordAppliedSymbologyMode(mode)
                    self.configurationRecoveryAttempt = 0
                    self.configurationState = .ready
                    self.trace(
                        "Scanner configured for \(mode.rawValue) across \(values.count) barcode types"
                    )
                    self.settingsBecameReady()
                case .failure(let error):
                    self.trace(
                        "Scanner symbology configuration failed (attempt \(attempt + 1)): "
                            + error.localizedDescription
                    )
                    // トリガー入力と設定応答の競合など一過性の失敗は同じ要求を再送し、
                    // 尽きたときだけ失敗として公開し、自動復旧の予約へ進む。
                    guard attempt < Self.symbologyWriteRetryLimit else {
                        self.configurationState = .failed(
                            AppLocalization.string("スキャナーの読み取り設定を変更できませんでした。カメラを使用してください。")
                        )
                        self.scheduleConfigurationRecovery()
                        return
                    }
                    self.scheduleSymbologyWriteRetry(
                        revision: revision,
                        attempt: attempt + 1,
                        sdkDevice: sdkDevice
                    )
                }
            }
        }
        let dispatchDuration = ProcessInfo.processInfo.systemUptime - requestedAt
        print(
            "[BluetoothScanner] Symbology SDK request returned in "
                + "\(String(format: "%.3f", dispatchDuration))s"
        )
    }

    private func scheduleSymbologyWriteRetry(
        revision: Int,
        attempt: Int,
        sdkDevice: BLEDevice
    ) {
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500 * attempt))
            guard !Task.isCancelled, let self,
                  revision == self.symbologyConfigurationRevision,
                  self.connectedSDKDevice?.uuid == sdkDevice.uuid,
                  !self.settingCommandInFlight,
                  !self.manualDisconnectInProgress else { return }
            self.applyExpectedCodeConfiguration(revision: revision, attempt: attempt)
        }
    }

    /// SDKが設定完了を返さない場合は新しいコマンドを重ねず、接続を閉じて
    /// 保存済みスナップショットからの再同期へ移る。遅れて届く旧コールバックは
    /// generation不一致で破棄する。
    private func scheduleSettingCommandTimeout(
        generation: Int,
        sdkDevice: BLEDevice,
        label: String,
        duration: Duration
    ) {
        settingCommandTimeoutTask?.cancel()
        settingCommandTimeoutTask = Task { [weak self] in
            do {
                try await Task.sleep(for: duration)
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  generation == self.settingCommandGeneration,
                  self.settingCommandInFlight,
                  self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }

            self.settingCommandTimeoutTask = nil
            self.settingCommandGeneration += 1
            self.settingCommandInFlight = false

            if self.manualDisconnectInProgress {
                self.configurationState = .unavailable
                self.trace(
                    "Original scanner mode restore timed out during disconnect; "
                        + "recovery snapshot retained"
                )
                self.performManualDisconnect(sdkDevice)
                return
            }

            self.cancelSDKOutputConfiguration()
            self.stopLinkLivenessMonitor()
            self.illuminationState = .unknown
            self.pendingIlluminationRequest = nil
            self.tuningState = .unknown
            self.connectedSDKDevice = nil
            self.sdkOutputConfigurationCompleted = false
            self.connectedScannerSettings = nil
            self.connectedSymbologyValues = nil
            self.connectedScannerSettingsAreFresh = false
            self.configurationState = .unavailable
            self.state = .failed(
                AppLocalization.string("スキャナーの読み取り設定通信が完了しませんでした。安全のためカメラへ切り替え、再接続します。")
            )
            self.trace(
                "Setting command \(label) timed out "
                    + "(generation \(generation)); reconnecting"
            )

            sdkDevice.disconnect { [weak self] result in
                Task { @MainActor in
                    guard let self else { return }
                    switch result {
                    case .success:
                        self.trace("Scanner link closed after symbology timeout")
                    case .failure(let error):
                        self.trace(
                            "Scanner link close after symbology timeout failed: "
                                + error.localizedDescription
                        )
                    }
                    // 切断完了前に再接続を開始すると、同じSDKデバイス上で
                    // 古いリンクと新しい設定取得が競合するため、必ず完了後に再試行する。
                    self.scheduleAutomaticReconnect(reason: "symbology command timeout")
                }
            }
        }
    }

    /// 終了時の元設定復元もSDK応答を無期限には待たない。タイムアウト時は
    /// スナップショットを消さずに切断し、次回接続時の復旧へ引き継ぐ。
    private func scheduleBaselineRestoreTimeout(
        generation: Int,
        sdkDevice: BLEDevice
    ) {
        settingCommandTimeoutTask?.cancel()
        settingCommandTimeoutTask = Task { [weak self] in
            do {
                try await Task.sleep(for: Self.symbologyCommandTimeout)
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  generation == self.settingCommandGeneration,
                  self.settingCommandInFlight,
                  self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }

            self.settingCommandTimeoutTask = nil
            self.settingCommandGeneration += 1
            self.settingCommandInFlight = false
            self.configurationState = .unavailable
            self.trace(
                "Original scanner mode restore timed out "
                    + "(generation \(generation)); recovery snapshot retained"
            )
            self.performManualDisconnect(sdkDevice)
        }
    }

    /// 旧版はQR／Code 128の制限状態だけを記録し、変更前スナップショットを
    /// 持っていなかった。旧版が変更したのはこの2項目だけなので、初回の最新取得値を
    /// 基に両方をONへ戻す復旧値を作り、アップデート直後にも制限を残さない。
    private func migrateLegacySymbologyRecoveryIfNeeded(
        deviceID: String,
        settingsAreFresh: Bool
    ) {
        // 旧版の記録は1台分の制限状態だけなので、どのスキャナーの復元待ちもないときに限る。
        // 別のスキャナーの復元待ちで残った制限状態を、今のスキャナーの復旧値にしない。
        guard settingsAreFresh,
              persistedSymbologySnapshots.isEmpty,
              persistedSymbologyMode != .unrestricted,
              var values = connectedSymbologyValues,
              Self.hasRequiredSymbologyValues(values) else { return }

        values["qrcode_on"] = 1
        values["code128_on"] = 1
        persistSymbologySnapshot(
            BluetoothScannerSymbologySnapshot(deviceID: deviceID, values: values)
        )
        trace("Migrated legacy QR/Code 128 recovery state to a full barcode snapshot")
    }

#else
    private static let simulatorDevice = BluetoothScannerDevice(
        id: "SIMULATOR-BCST-47",
        name: "BCST-47 (Simulator)"
    )
    private static let simulatorSymbologyValues = [
        "code39_on": 1,
        "code128_on": 1,
        "ean_13_on": 1,
        "qrcode_on": 1,
        "datamatrix_on": 1
    ]
#endif

    /// 制限を適用する前に記録し、プロセスが途中終了しても次回接続で復旧できるようにする。
    /// 元設定への復元では、SDKの成功応答を受けるまで既存の制限記録を残す。
    private func recordPendingSymbologyMode(_ mode: BluetoothScannerSymbologyMode) {
        guard mode != .unrestricted else { return }
        defaults.set(mode.rawValue, forKey: Self.symbologyRecoveryModeKey)
    }

    private func recordAppliedSymbologyMode(_ mode: BluetoothScannerSymbologyMode) {
        if mode == .unrestricted {
            defaults.removeObject(forKey: Self.symbologyRecoveryModeKey)
        } else {
            defaults.set(mode.rawValue, forKey: Self.symbologyRecoveryModeKey)
        }
    }

    private func persistSymbologySnapshot(_ snapshot: BluetoothScannerSymbologySnapshot) {
        symbologySnapshots.save(snapshot)
    }

    private func clearPersistedSymbologySnapshot(deviceID: String) {
        symbologySnapshots.remove(deviceID: deviceID)
    }

    /// 別のスキャナーの復元待ちが残っていることを診断へ添える。識別子は含めない。
    private func pendingOtherScannerRestoreNote(excluding deviceID: String) -> String {
        let count = persistedSymbologySnapshots.keys.filter { $0 != deviceID }.count
        return count == 0 ? "" : " (\(count) other scanner restore(s) pending)"
    }

#if !INATECK_SDK
    private func persistSimulatorSymbologySnapshotIfNeeded() {
        guard let deviceID = connectedDevice?.id,
              persistedSymbologySnapshot(for: deviceID) == nil else { return }
        persistSymbologySnapshot(
            BluetoothScannerSymbologySnapshot(
                deviceID: deviceID,
                values: Self.simulatorSymbologyValues
            )
        )
    }
#endif

    struct IlluminationSetting: Equatable {
        let area: String
        let value: Int
    }

    /// inventoryに存在するチューニング項目（name, 現在値）。inventoryが読めなければnil。
    /// GATTモードへの切替で名前と識別子が変わったスキャナーを探す（#134）。
    /// 例: 切替前「Hyper 160B-4F5F-UNI」→ 切替後「HPRT-4F5F」。切替後の名前の
    /// 最後の「-」以降が4桁の16進数（MAC由来）で、切替前の名前の「-」区切りの
    /// 一部と一致する、切替元以外のスキャナーを返す。
    static func gattModeSuccessors(
        of origin: BluetoothScannerDevice,
        among devices: [BluetoothScannerDevice]
    ) -> [BluetoothScannerDevice] {
        func components(_ name: String) -> [String] {
            name.split(separator: "-").map {
                $0.trimmingCharacters(in: .whitespaces).uppercased()
            }
        }
        let originComponents = Set(components(origin.name))
        var seen = Set<String>()
        return devices.filter { device in
            guard device.id != origin.id, seen.insert(device.id).inserted else { return false }
            let parts = components(device.name)
            guard parts.count >= 2, let suffix = parts.last,
                  suffix.count == 4, suffix.allSatisfy(\.isHexDigit) else { return false }
            return originComponents.contains(suffix)
        }
    }

    static func tuningItemsPresent(in settings: String) -> [TuningItem]? {
        guard let items = scannerSettingItems(from: settings) else { return nil }
        var current: [String: Int] = [:]
        for item in items {
            guard let name = item["name"] as? String,
                  let value = integerSettingValue(item["value"]) else { continue }
            current[name] = value
        }
        return tuningProfile.compactMap { desired in
            current[desired.name].map { TuningItem(name: desired.name, value: $0) }
        }
    }

    /// inventoryにあり、現在値がプロファイルと異なる項目（書くべき項目）。
    static func tuningDifferences(settings: String) -> [TuningItem] {
        guard let present = tuningItemsPresent(in: settings) else { return [] }
        let currentByName = Dictionary(uniqueKeysWithValues: present.map { ($0.name, $0.value) })
        return tuningProfile.filter { desired in
            guard let current = currentByName[desired.name] else { return false }
            return current != desired.value
        }
    }

    static func tuningCommand(settings: String, items: [TuningItem]) -> String? {
        guard !items.isEmpty else { return nil }
        let values = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value) })
        return symbologySettingCommand(values: values, settings: settings)
    }

    /// inventoryの`lighting_lamp_control`をarea付きで読む。項目が1つでない、
    /// areaが空、値が0〜2以外のときは扱わない（Android版`InateckIlluminationSettings.read`と同じ規則）。
    static func illuminationSetting(from settings: String) -> IlluminationSetting? {
        guard let items = scannerSettingItems(from: settings) else { return nil }
        let entries = items.filter { ($0["name"] as? String) == illuminationSettingName }
        guard entries.count == 1, let entry = entries.first,
              let value = integerSettingValue(entry["value"]), (0...2).contains(value) else {
            return nil
        }
        let area: String
        if let string = entry["area"] as? String {
            area = string
        } else if let number = entry["area"] as? NSNumber {
            area = number.stringValue
        } else {
            return nil
        }
        guard !area.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return IlluminationSetting(area: area, value: value)
    }

    /// ON=0（読取中点灯）、OFF=2（常時消灯）。1（常時点灯）は提供しない。
    static func illuminationValue(enabled: Bool) -> Int { enabled ? 0 : 2 }

    static func illuminationCommand(settings: String, enabled: Bool) -> String? {
        guard let current = illuminationSetting(from: settings) else { return nil }
        let command: [[String: String]] = [[
            "area": current.area,
            "name": illuminationSettingName,
            "value": String(illuminationValue(enabled: enabled))
        ]]
        guard let data = try? JSONSerialization.data(withJSONObject: command),
              let string = String(data: data, encoding: .utf8) else { return nil }
        return string
    }

    static func illuminationConfirmed(settings: String, area: String, enabled: Bool) -> Bool {
        illuminationSetting(from: settings)
            == IlluminationSetting(area: area, value: illuminationValue(enabled: enabled))
    }

    /// 固定している公式iOS SDKは、取得した設定の`area`と`name`を
    /// `setSettingInfo`へ返す形式を使う。機種固有のareaはハードコードしない。
    static func hasRequiredSymbologySettings(_ settings: String) -> Bool {
        guard let values = symbologySettingValues(from: settings) else { return false }
        return hasRequiredSymbologyValues(values)
    }

    static func hasRequiredSymbologyValues(_ values: [String: Int]) -> Bool {
        values["qrcode_on"] != nil && values["code128_on"] != nil
    }

    static func symbologySettingValues(from settings: String) -> [String: Int]? {
        guard let items = scannerSettingItems(from: settings) else { return nil }

        var values: [String: Int] = [:]
        for item in items where isSymbologySetting(item) {
            guard let name = item["name"] as? String,
                  let value = integerSettingValue(item["value"]),
                  value == 0 || value == 1 else { continue }
            values[name] = value
        }
        return values.isEmpty ? nil : values
    }

    static func symbologySettingValues(
        for mode: BluetoothScannerSymbologyMode,
        original: [String: Int]
    ) -> [String: Int]? {
        guard hasRequiredSymbologyValues(original) else { return nil }
        switch mode {
        case .unrestricted:
            return original
        case .sessionCodes:
            var restricted = original.mapValues { _ in 0 }
            restricted["qrcode_on"] = 1
            restricted["code128_on"] = 1
            return restricted
        case .qrOnly, .code128Only:
            var restricted = original.mapValues { _ in 0 }
            restricted[mode == .qrOnly ? "qrcode_on" : "code128_on"] = 1
            return restricted
        }
    }

    static func symbologySettingCommand(values: [String: Int], settings: String) -> String? {
        guard !values.isEmpty,
              let items = scannerSettingItems(from: settings) else { return nil }

        var commands: [[String: String]] = []
        for item in items {
            guard let name = item["name"] as? String,
                  let value = values[name] else { continue }
            guard let areaValue = item["area"] else { return nil }

            let area: String
            if let string = areaValue as? String {
                area = string
            } else if let number = areaValue as? NSNumber {
                area = number.stringValue
            } else {
                return nil
            }
            commands.append(["area": area, "value": String(value), "name": name])
        }

        guard commands.count == values.count else { return nil }

        guard let commandData = try? JSONSerialization.data(withJSONObject: commands),
              let command = String(data: commandData, encoding: .utf8) else { return nil }
        return command
    }

    private static func scannerSettingItems(from settings: String) -> [[String: Any]]? {
        guard let data = settings.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any] else { return nil }
        return root["data"] as? [[String: Any]] ?? root["info"] as? [[String: Any]]
    }

    private static func integerSettingValue(_ value: Any?) -> Int? {
        if let string = value as? String { return Int(string) }
        if let number = value as? NSNumber { return number.intValue }
        return nil
    }

    private static func isSymbologySetting(_ item: [String: Any]) -> Bool {
        if let flag = integerSettingValue(item["flag"]), (2001...2028).contains(flag) {
            return true
        }
        guard let name = item["name"] as? String else { return false }
        return legacySymbologySettingNames.contains(name.lowercased())
    }

    /// #59 の候補（多コード・反転・読取時間・照明・照準・厳格度・重複抑制）。値は0〜数十の整数。
    static let tuningSettingNames: Set<String> = [
        "lighting_lamp_control", "positioning_lamp_control", "position_light_twinkle",
        "qrcode_read_more_code", "datamatrix_read_multi", "pdf417_read_more_code",
        "read_inverse_color", "qrcode_read_phase", "datamatrix_read_phase",
        "hanxin_read_phase", "pdf417_read_phase",
        "scan_mode", "auto_close_mode", "time_auto_off", "time_continuous_mode", "auto_off",
        "auto_sense_distan", "security_level", "repeat_data_check_on", "ignore_short_bar",
        "data_transmission_speed", "inventory_mode", "auto_upload_cache", "start_up_clean_cache"
    ]

    private static let legacySymbologySettingNames: Set<String> = [
        "codabar_on", "iata25_on", "interleaved25_on", "matrix25_on", "standard25_on",
        "code39_on", "code93_on", "code128_on", "ean_8_on", "ean_13_on", "upc_a_on",
        "upc_e0_on", "msi_on", "code11_on", "chinese_post_on", "upc_e1_on",
        "aztec_on", "maxicode_on", "hanxin_on", "datamatrix_on", "qrcode_on",
        "pdf417_on", "gs1_128", "rss14_composite_on", "rss_14_composite_on", "plessey_on",
        "telepen_on", "rss_14_on", "rss_expanded_on", "rss_limited_on", "symb_128_on",
        "usps_on", "usps_fedex"
    ]

    /// 設定画面の共有シートから書き出す診断ログ。payload・設定値・device識別子は
    /// `trace`に含めない方針なので、そのまま第三者へ渡せる内容になる。
    func diagnosticLogText() -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "?"
        let build = info["CFBundleVersion"] as? String ?? "?"
        var lines: [String] = [
            "CodeMatch Bluetooth diagnostics",
            "app: \(version) (\(build))",
            "system: \(ProcessInfo.processInfo.operatingSystemVersionString)",
            "connection: \(state.statusText)",
            "configuration: \(String(describing: configurationState))",
            "symbology mode: \(persistedSymbologyMode.rawValue)",
            "pending restores: \(persistedSymbologySnapshots.count)",
            "events: \(diagnosticEvents.count) (limit \(Self.diagnosticEventLimit))",
            ""
        ]
        lines.append(contentsOf: diagnosticEvents.map { event in
            "\(formatter.string(from: event.date)) \(event.message)"
        })
        return lines.joined(separator: "\n")
    }

    func clearDiagnosticEvents() {
        diagnosticEvents = []
        defaults.removeObject(forKey: Self.diagnosticEventsKey)
        logger.info("Diagnostic events cleared")
    }

    private func trace(_ message: String) {
        logger.info("\(message, privacy: .public)")
        diagnosticEvents.append(
            BluetoothScannerDiagnosticEvent(date: now(), message: message)
        )
        if diagnosticEvents.count > Self.diagnosticEventLimit {
            diagnosticEvents.removeFirst(diagnosticEvents.count - Self.diagnosticEventLimit)
        }
        if let data = try? JSONEncoder().encode(diagnosticEvents) {
            defaults.set(data, forKey: Self.diagnosticEventsKey)
        }
#if DEBUG
        print("[BluetoothScanner] \(message)")
#endif
    }
}

#if INATECK_SDK
extension BluetoothScannerService: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor [weak self] in
            guard let self, !self.isConnected else { return }
            switch central.state {
            case .poweredOn:
                self.trace("Core Bluetooth powered on")
                if self.pendingDiscovery {
                    self.startDiscovery()
                } else if case .unavailable = self.state {
                    self.state = .idle
                }
            case .poweredOff:
                self.state = .unavailable(AppLocalization.string("Bluetoothがオフです。"))
                self.trace("Core Bluetooth powered off")
            case .unauthorized:
                self.state = .unavailable(AppLocalization.string("Bluetoothの利用が許可されていません。"))
                self.trace("Core Bluetooth unauthorized")
            case .unsupported:
                self.state = .unavailable(AppLocalization.string("この端末ではBluetoothを利用できません。"))
                self.trace("Core Bluetooth unsupported")
            case .resetting:
                self.state = .unavailable(AppLocalization.string("Bluetoothを再準備しています…"))
            case .unknown:
                break
            @unknown default:
                self.state = .unavailable(AppLocalization.string("Bluetoothの状態を取得できません。"))
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor [weak self] in
            guard let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.handleSDKOutputConfigurationConnect(peripheral)
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor [weak self] in
            guard let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration connection failed: "
                    + (error?.localizedDescription ?? "unknown error")
            )
        }
    }

    /// 補助リンクが途中で閉じた場合も、SDK側のリンク状態とは別に完了扱いにして
    /// 読取設定へ進む。SDK側も切断していれば`handleSDKDisconnect`が状態を片付ける。
    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor [weak self] in
            guard let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration link closed before completion: "
                    + (error?.localizedDescription ?? "no error")
            )
        }
    }
}

extension BluetoothScannerService: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor [weak self] in
            guard let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.handleSDKOutputConfigurationServices(peripheral, error: error)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        Task { @MainActor [weak self] in
            guard let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.handleSDKOutputConfigurationCharacteristics(
                peripheral,
                service: service,
                error: error
            )
        }
    }
}
#endif
