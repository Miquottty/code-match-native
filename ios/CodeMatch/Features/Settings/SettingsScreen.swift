import SwiftUI
import CoreImage.CIFilterBuiltins
import UIKit

enum BluetoothScannerSetupCode: String, CaseIterable, Identifiable {
    case enterSetup = "/*EnterSet*/"
    case gattMode = "/*BLE_GATT*/"
    case saveAndExit = "/*ExitSave*/"

    var id: String { rawValue }

    var accessibilityID: String {
        switch self {
        case .enterSetup: "enterSetup"
        case .gattMode: "gattMode"
        case .saveAndExit: "saveAndExit"
        }
    }

    var title: String {
        switch self {
        case .enterSetup: AppLocalization.string("設定を開始")
        case .gattMode: AppLocalization.string("GATTモードへ切り替え")
        case .saveAndExit: AppLocalization.string("設定を保存して終了")
        }
    }

    var scannerDisplayText: String {
        switch self {
        case .enterSetup: AppLocalization.string("Enter Setup")
        case .gattMode: AppLocalization.string("BLE_GATT")
        case .saveAndExit: AppLocalization.string("Exit / Save")
        }
    }

    var resultGuidance: String {
        switch self {
        case .enterSetup:
            AppLocalization.string(
                "このコードは設定受付を開始するためのものです。ここではまだGATTモードには切り替わりません。次の画面でGATTを指定します。"
            )
        case .gattMode:
            AppLocalization.string(
                "読み取るとスキャナ画面に「BLE_GATT」が表示されます。まだ保存は完了していないため、次の画面へ進みます。"
            )
        case .saveAndExit:
            AppLocalization.string("最後にこのコードを読み取り、GATT設定を保存して設定モードを終了します。")
        }
    }
}

struct SettingsScreen: View {
    @ObservedObject var bluetoothScanner: BluetoothScannerService
    @ObservedObject var scanLog: ScanLogStore
    @AppStorage(FeedbackSettings.volumeKey) private var volume = FeedbackSettings.defaultVolume
    @AppStorage(FeedbackSettings.successSoundKey) private var successSound = SuccessSound.posBeep.rawValue
    @AppStorage(FeedbackSettings.failureSoundKey) private var failureSound = FailureSound.buzzer.rawValue
    @AppStorage(AppLanguage.storageKey) private var selectedLanguageRawValue = AppLanguage.japanese.rawValue
    @AppStorage(AutoAdvanceSettings.enabledKey) private var autoAdvanceEnabled = AutoAdvanceSettings.defaultEnabled
    @AppStorage(AutoAdvanceSettings.delaySecondsKey) private var autoAdvanceDelaySeconds = AutoAdvanceSettings.defaultDelay.rawValue
    private let player = FeedbackPlayer.shared
    @State private var showsScannerSetupGuide = false
    @State private var scanLogShareItem: ScanLogShareItem?
    @State private var showsScanLogExportError = false
    @State private var showsScanLogClearConfirmation = false

    private struct ScanLogShareItem: Identifiable {
        let id = UUID()
        let url: URL
    }

    /// 診断ログを管理者へ送るメール作成画面（#143）。
    private struct DiagnosticsMailItem: Identifiable {
        let id = UUID()
        let content: DiagnosticsMailContent
        let logText: String
    }

    /// 「メール」にアカウントがない端末で使う、従来どおりの共有シート。
    private struct DiagnosticsShareItem: Identifiable {
        let id = UUID()
        let text: String
    }

    @State private var diagnosticsMailItem: DiagnosticsMailItem?
    @State private var diagnosticsShareItem: DiagnosticsShareItem?

    var body: some View {
        ZStack {
            AppTheme.paper.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("SETTINGS")
                            .font(.caption2.weight(.black))
                            .tracking(2.2)
                            .foregroundStyle(AppTheme.green)
                        Text("アプリの設定")
                            .font(.title2.weight(.bold))
                            .foregroundStyle(AppTheme.ink)
                    }
                    .padding(.horizontal, 4)

                    bluetoothScannerCard
                    autoAdvanceCard
                    volumeCard
                    soundCard(
                        title: AppLocalization.string("成功音"),
                        subtitle: AppLocalization.string("品目番号が一致したときに鳴ります"),
                        icon: "checkmark.circle.fill",
                        tint: AppTheme.green
                    ) {
                        ForEach(SuccessSound.allCases) { sound in
                            SoundOptionRow(
                                label: sound.label,
                                isSelected: successSound == sound.rawValue,
                                tint: AppTheme.green
                            ) {
                                successSound = sound.rawValue
                                player.preview(success: sound)
                            }
                            .accessibilityIdentifier("successSound_\(sound.rawValue)")
                        }
                    }
                    soundCard(
                        title: AppLocalization.string("失敗音"),
                        subtitle: AppLocalization.string("品目番号が一致しないときに鳴ります"),
                        icon: "exclamationmark.triangle.fill",
                        tint: AppTheme.red
                    ) {
                        ForEach(FailureSound.allCases) { sound in
                            SoundOptionRow(
                                label: sound.label,
                                isSelected: failureSound == sound.rawValue,
                                tint: AppTheme.red
                            ) {
                                failureSound = sound.rawValue
                                player.preview(failure: sound)
                            }
                            .accessibilityIdentifier("failureSound_\(sound.rawValue)")
                        }
                    }

                    Label(
                        "マナーモード中でも効果音は再生されます。実際の音量は端末の音量ボタンの設定にも依存します。",
                        systemImage: "speaker.wave.2.bubble.left.fill"
                    )
                    .font(.caption)
                    .foregroundStyle(AppTheme.muted)
                    .lineSpacing(3)
                    .padding(.horizontal, 6)

                    languageSelectionCard
                    scanLogCard
                }
                .padding(.horizontal, 18)
                .padding(.top, 20)
                .padding(.bottom, 84)
            }
        }
        .preferredColorScheme(.light)
        .accessibilityIdentifier("settingsScreen")
        .sheet(isPresented: $showsScannerSetupGuide) {
            BluetoothScannerSetupGuide {
                showsScannerSetupGuide = false
                bluetoothScanner.startDiscovery()
            }
        }
        .sheet(item: $diagnosticsMailItem) { item in
            MailComposeView(
                recipients: DiagnosticsMailContent.recipients,
                subject: item.content.subject,
                body: item.content.body,
                attachment: MailComposeView.Attachment(
                    data: Data(item.logText.utf8),
                    fileName: item.content.fileName,
                    mimeType: "text/plain"
                )
            ) {
                diagnosticsMailItem = nil
            }
            .ignoresSafeArea()
        }
        .sheet(item: $diagnosticsShareItem) { item in
            ActivityShareSheet(items: [item.text])
                .presentationDetents([.medium, .large])
        }
        .sheet(item: $scanLogShareItem) { item in
            ActivityShareSheet(items: [item.url])
                .presentationDetents([.medium, .large])
        }
        .alert(
            AppLocalization.string("照合ログの書き出しに失敗しました。"),
            isPresented: $showsScanLogExportError
        ) {
            Button(AppLocalization.string("閉じる"), role: .cancel) {}
        }
        .confirmationDialog(
            AppLocalization.string("照合ログを消去しますか？"),
            isPresented: $showsScanLogClearConfirmation,
            titleVisibility: .visible
        ) {
            Button(AppLocalization.string("消去する"), role: .destructive) {
                scanLog.clear()
            }
            Button(AppLocalization.string("キャンセル"), role: .cancel) {}
        }
    }

    /// 照合ログ（カメラ・Bluetoothの判定と不受理）の件数表示と、共有・消去。
    /// 読み取った生ペイロードを含むため、共有は利用者の操作でだけ行う。
    private var scanLogCard: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 8) {
                Image(systemName: "doc.text.magnifyingglass")
                    .foregroundStyle(AppTheme.green)
                VStack(alignment: .leading, spacing: 2) {
                    Text(AppLocalization.string("照合ログ"))
                        .font(.headline)
                        .foregroundStyle(AppTheme.ink)
                    Text(
                        AppLocalization.string(
                            "カメラ・Bluetooth の照合結果と不受理を直近5,000件まで端末内に保持します。読み取った値を含むので共有先に注意してください。"
                        )
                    )
                        .font(.caption2)
                        .foregroundStyle(AppTheme.muted)
                        .lineSpacing(3)
                }
            }

            Text(AppLocalization.string("記録: \(scanLog.eventCount)件"))
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.ink)
                .accessibilityIdentifier("scanLogCount")

            HStack(spacing: 10) {
                Button {
                    shareScanLog()
                } label: {
                    Label(
                        AppLocalization.string("照合ログをすべて共有"),
                        systemImage: "square.and.arrow.up"
                    )
                    .font(.caption.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 9)
                }
                .buttonStyle(.bordered)
                .tint(AppTheme.green)
                .disabled(scanLog.eventCount == 0)
                .accessibilityIdentifier("shareScanLogButton")

                Button(role: .destructive) {
                    showsScanLogClearConfirmation = true
                } label: {
                    Label(
                        AppLocalization.string("照合ログを消去"),
                        systemImage: "trash"
                    )
                    .font(.caption.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 9)
                }
                .buttonStyle(.bordered)
                .disabled(scanLog.eventCount == 0)
                .accessibilityIdentifier("clearScanLogButton")
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .scannerCard()
    }

    /// 宛先・件名・本文・ログ添付を埋めたメール作成画面を開き、操作者は送信を押すだけにする。
    /// 「メール」にアカウントがない端末では従来どおりの共有シートに切り替える（#143）。
    private func sendDiagnostics() {
        let logText = bluetoothScanner.diagnosticLogText()
        guard MailComposeView.canSendMail else {
            diagnosticsShareItem = DiagnosticsShareItem(text: logText)
            return
        }
        let device = "\(UIDevice.current.model) / iOS \(UIDevice.current.systemVersion)"
        diagnosticsMailItem = DiagnosticsMailItem(
            content: bluetoothScanner.diagnosticsMailContent(device: device),
            logText: logText
        )
    }

    /// ヘッダ行つきのJSONLを一時ファイルへ書き出して共有シートに渡す。
    private func shareScanLog() {
        guard scanLog.eventCount > 0 else { return }
        do {
            scanLogShareItem = ScanLogShareItem(url: try scanLog.writeTemporaryExport())
        } catch {
            showsScanLogExportError = true
        }
    }

    private var bluetoothScannerCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: "barcode.viewfinder")
                    .font(.title3)
                    .foregroundStyle(AppTheme.green)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Bluetoothスキャナ")
                        .font(.headline)
                        .foregroundStyle(AppTheme.ink)
                    Text(bluetoothScanner.state.statusText)
                        .font(.caption2)
                        .foregroundStyle(scannerStatusColor)
                        .accessibilityIdentifier("bluetoothScannerStatus")
                }
                Spacer()
                if case .searching = bluetoothScanner.state {
                    ProgressView()
                        .tint(AppTheme.green)
                }
            }

            if bluetoothScanner.isConnected {
                configurationStatus
                illuminationControls

                Button(role: .destructive) {
                    bluetoothScanner.disconnect()
                } label: {
                    Label("スキャナを切断", systemImage: "xmark.circle")
                        .font(.subheadline.weight(.bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("disconnectBluetoothScannerButton")
            } else {
                if let knownDevice = bluetoothScanner.reconnectableDevice {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("以前接続したスキャナ")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(AppTheme.muted)
                        Button {
                            bluetoothScanner.reconnectKnownDevice()
                        } label: {
                            Label(
                                AppLocalization.string("\(knownDevice.name) に再接続"),
                                systemImage: "arrow.trianglehead.2.clockwise.rotate.90"
                            )
                                .font(.subheadline.weight(.bold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(AppTheme.green)
                        .disabled(isSearching || bluetoothScanner.connectingDeviceID == knownDevice.id)
                        .accessibilityIdentifier("knownScannerReconnectButton")
                    }

                    Button {
                        bluetoothScanner.startDiscovery()
                    } label: {
                        Label(
                            isSearching
                                ? AppLocalization.string("検索中…")
                                : AppLocalization.string("別のスキャナを検索"),
                            systemImage: "antenna.radiowaves.left.and.right"
                        )
                        .font(.subheadline.weight(.bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.bordered)
                    .tint(AppTheme.green)
                    .disabled(isSearching)
                    .accessibilityIdentifier("searchBluetoothScannerButton")
                } else {
                    Button {
                        bluetoothScanner.startDiscovery()
                    } label: {
                        Label(
                            isSearching
                                ? AppLocalization.string("検索中…")
                                : AppLocalization.string("スキャナを検索"),
                            systemImage: "antenna.radiowaves.left.and.right"
                        )
                        .font(.subheadline.weight(.bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppTheme.green)
                    .disabled(isSearching)
                    .accessibilityIdentifier("searchBluetoothScannerButton")
                }

                Button {
                    showsScannerSetupGuide = true
                } label: {
                    Label("初回接続ガイドを開く", systemImage: "list.number")
                        .font(.subheadline.weight(.bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                }
                .buttonStyle(.bordered)
                .tint(AppTheme.amber)
                .accessibilityIdentifier("scannerSetupGuideButton")

                if !visibleDiscoveredDevices.isEmpty {
                    VStack(spacing: 8) {
                        ForEach(visibleDiscoveredDevices) { device in
                            Button {
                                bluetoothScanner.connectSelectedDevice(device)
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "barcode")
                                        .foregroundStyle(AppTheme.green)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(device.name)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(AppTheme.ink)
                                        Text("タップして接続")
                                            .font(.caption2)
                                            .foregroundStyle(AppTheme.muted)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.caption.weight(.bold))
                                        .foregroundStyle(AppTheme.muted)
                                }
                                .padding(12)
                                .background(AppTheme.ink.opacity(0.04), in: RoundedRectangle(cornerRadius: 12))
                            }
                            .buttonStyle(.plain)
                            // 接続中の端末だけを押せなくし、別の端末を選べば接続し直す（#137）。
                            .disabled(bluetoothScanner.connectingDeviceID == device.id)
                            .accessibilityIdentifier("bluetoothScannerDevice_\(device.id)")
                        }
                    }
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Label(
                    "iPhoneの「設定 > Bluetooth」ではペアリングせず、この画面から接続してください。",
                    systemImage: "exclamationmark.triangle.fill"
                )
                .foregroundStyle(AppTheme.amber)

                Text("初回は「初回接続ガイド」でGATT／APPモードにします。この画面から切断した後は「以前接続したスキャナ」から再接続できます。")
                    .foregroundStyle(AppTheme.muted)
            }
            .font(.caption2)
            .lineSpacing(3)
            .accessibilityIdentifier("bluetoothScannerConnectionHelp")

            if !bluetoothScanner.diagnosticEvents.isEmpty {
                // 一覧を開かなくても押せるよう、送信ボタンは折りたたみの外に置く（#143）。
                Button {
                    sendDiagnostics()
                } label: {
                    Label(
                        AppLocalization.string("診断ログを管理者へ送る"),
                        systemImage: "envelope"
                    )
                    .font(.subheadline.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                }
                .buttonStyle(.bordered)
                .tint(AppTheme.green)
                .accessibilityIdentifier("shareBluetoothDiagnosticsButton")

                DisclosureGroup("接続診断（直近20件）") {
                    VStack(alignment: .leading, spacing: 7) {
                        ForEach(
                            bluetoothScanner.diagnosticEvents
                                .suffix(BluetoothScannerService.diagnosticDisplayLimit)
                                .reversed()
                        ) { event in
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text(event.date, style: .time)
                                    .foregroundStyle(AppTheme.muted)
                                Text(event.message)
                                    .foregroundStyle(AppTheme.ink)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                    .font(.caption2.monospaced())
                    .padding(.top, 8)
                    .accessibilityIdentifier("bluetoothDiagnosticEvents")

                    HStack(spacing: 10) {
                        Button(role: .destructive) {
                            bluetoothScanner.clearDiagnosticEvents()
                        } label: {
                            Label(
                                AppLocalization.string("診断ログを消去"),
                                systemImage: "trash"
                            )
                            .font(.caption.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("clearBluetoothDiagnosticsButton")
                    }
                    .padding(.top, 10)
                }
                .font(.caption.weight(.bold))
                .tint(AppTheme.green)
                .accessibilityIdentifier("bluetoothDiagnosticsDisclosure")
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .scannerCard()
    }

    private var languageSelectionCard: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 8) {
                Image(systemName: "globe")
                    .foregroundStyle(AppTheme.green)
                VStack(alignment: .leading, spacing: 2) {
                    Text(AppLocalization.string("言語"))
                        .font(.headline)
                        .foregroundStyle(AppTheme.ink)
                    Text(AppLocalization.string("表示言語を選択します。"))
                        .font(.caption2)
                        .foregroundStyle(AppTheme.muted)
                }
            }

            Picker(AppLocalization.string("アプリの言語"), selection: $selectedLanguageRawValue) {
                ForEach(AppLanguage.allCases) { language in
                    Text(language.displayName)
                        .tag(language.rawValue)
                        .accessibilityIdentifier("languageOption_\(language.rawValue)")
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("languageSelectionPicker")
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .scannerCard()
        .accessibilityIdentifier("languageSelectionCard")
    }

    @ViewBuilder
    private var configurationStatus: some View {
        switch bluetoothScanner.configurationState {
        case .ready:
            Label(
                bluetoothScanner.persistedSymbologyMode.statusText,
                systemImage: "checkmark.circle.fill"
            )
                .foregroundStyle(AppTheme.green)
        case .configuring:
            Label(
                AppLocalization.string("バーコード種類の読み取り設定を確認中です…"),
                systemImage: "gearshape.2.fill"
            )
                .foregroundStyle(AppTheme.muted)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(AppTheme.red)
            Button {
                bluetoothScanner.retryConfiguration()
            } label: {
                Label(
                    AppLocalization.string("読み取り設定をやり直す"),
                    systemImage: "arrow.clockwise"
                )
                    .font(.subheadline.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.bordered)
            .tint(AppTheme.green)
            .accessibilityIdentifier("retryScannerConfigurationButton")
        case .unavailable:
            EmptyView()
        }
    }

    /// 照明はAndroid版と同じ扱い: 接続ごとにOFFを適用し、SDKから再取得して一致した
    /// ときだけスイッチを表示する。未確認・適用中は進捗、失敗は再適用ボタンにして
    /// 「OFFに見えて点灯している」表示を避ける。
    @ViewBuilder
    private var illuminationControls: some View {
        if bluetoothScanner.illuminationState != .unsupported {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 10) {
                    Label(AppLocalization.string("スキャナー照明"), systemImage: "lightbulb")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.ink)
                    Spacer()
                    switch bluetoothScanner.illuminationState {
                    case .on, .off:
                        Toggle(
                            AppLocalization.string("スキャナー照明"),
                            isOn: Binding(
                                get: { bluetoothScanner.illuminationState == .on },
                                set: { bluetoothScanner.setIllumination($0) }
                            )
                        )
                        .labelsHidden()
                        .tint(AppTheme.green)
                        .accessibilityIdentifier("scannerIlluminationToggle")
                    case .applying:
                        ProgressView()
                            .tint(AppTheme.green)
                            .accessibilityIdentifier("scannerIlluminationPending")
                    case .failed:
                        Button {
                            bluetoothScanner.setIllumination(false)
                        } label: {
                            Text(AppLocalization.string("OFFを再適用"))
                                .font(.caption.weight(.bold))
                        }
                        .buttonStyle(.bordered)
                        .tint(AppTheme.amber)
                        .accessibilityIdentifier("scannerIlluminationRetryButton")
                    case .unknown, .unsupported:
                        EmptyView()
                    }
                }
                Text(illuminationDescription)
                    .font(.caption2)
                    .foregroundStyle(illuminationDescriptionColor)
                    .lineSpacing(3)
                    .accessibilityIdentifier("scannerIlluminationDescription")
                if let tuningDescription {
                    Text(tuningDescription)
                        .font(.caption2)
                        .foregroundStyle(tuningDescriptionColor)
                        .lineSpacing(3)
                        .accessibilityIdentifier("scannerTuningDescription")
                }
            }
            .padding(.vertical, 4)
        }
    }

    /// 多コード・反転・赤光消灯時間は接続ごとに差分だけ書く固定プロファイル。UIは状態表示のみ。
    private var tuningDescription: String? {
        switch bluetoothScanner.tuningState {
        case .unknown, .unsupported:
            nil
        case .applying:
            AppLocalization.string("読取チューニングを適用しています…")
        case .matched(let applied):
            applied
                ? AppLocalization.string("読取チューニング: 適用済み（多コードOFF・反転OFF・赤光4秒）")
                : AppLocalization.string("読取チューニング: 確認済み（変更なし）")
        case .failed(let message):
            message
        }
    }

    private var tuningDescriptionColor: Color {
        if case .failed = bluetoothScanner.tuningState { return AppTheme.red }
        return AppTheme.muted
    }

    private var illuminationDescription: String {
        switch bluetoothScanner.illuminationState {
        case .applying:
            AppLocalization.string("照明設定を確認しています…")
        case .failed(let message):
            message
        case .unknown:
            AppLocalization.string("接続後に照明設定を確認します。")
        case .on, .off, .unsupported:
            AppLocalization.string("ONにすると読取中に点灯します。接続時はOFFにします。設定はスキャナー本体に保存され、切断しても元に戻りません。")
        }
    }

    private var illuminationDescriptionColor: Color {
        if case .failed = bluetoothScanner.illuminationState { return AppTheme.red }
        return AppTheme.muted
    }

    private var isSearching: Bool {
        if case .searching = bluetoothScanner.state { return true }
        return false
    }

    private var visibleDiscoveredDevices: [BluetoothScannerDevice] {
        let reconnectableID = bluetoothScanner.reconnectableDevice?.id
        return bluetoothScanner.devices.filter { $0.id != reconnectableID }
    }

    private var scannerStatusColor: Color {
        switch bluetoothScanner.state {
        case .connected: AppTheme.green
        case .failed, .unavailable: AppTheme.red
        default: AppTheme.muted
        }
    }

    private var autoAdvanceCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Toggle(isOn: $autoAdvanceEnabled) {
                Label {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("成功時の自動「次の照合」")
                            .font(.headline)
                            .foregroundStyle(AppTheme.ink)
                        Text("一致結果を確認後、自動で次のQR読み取りへ進みます")
                            .font(.caption2)
                            .foregroundStyle(AppTheme.muted)
                    }
                } icon: {
                    Image(systemName: "forward.end.fill")
                        .foregroundStyle(AppTheme.green)
                }
            }
            .tint(AppTheme.green)
            .accessibilityIdentifier("autoAdvanceSettingsToggle")

            VStack(alignment: .leading, spacing: 9) {
                Text("カウントダウン")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.muted)

                Picker("自動で進むまでの時間", selection: $autoAdvanceDelaySeconds) {
                    ForEach(AutoAdvanceDelay.allCases) { delay in
                        Text(delay.label).tag(delay.rawValue)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("autoAdvanceDelayPicker")
            }

            Label(
                autoAdvanceEnabled
                    ? AppLocalization.string(
                        "一致画面に残り秒数を表示します。手動の「次の照合」もいつでも使えます。"
                    )
                    : AppLocalization.string(
                        "初期設定はOFFです。ONにしても不一致時は自動で進みません。"
                    ),
                systemImage: autoAdvanceEnabled ? "timer" : "pause.circle"
            )
            .font(.caption)
            .foregroundStyle(AppTheme.muted)
            .lineSpacing(3)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .scannerCard()
    }

    private var volumeCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label {
                    Text("音量")
                        .font(.headline)
                        .foregroundStyle(AppTheme.ink)
                } icon: {
                    Image(systemName: "speaker.wave.3.fill")
                        .foregroundStyle(AppTheme.green)
                }
                Spacer()
                Text("\(Int(volume * 100))%")
                    .font(.system(.subheadline, design: .monospaced, weight: .bold))
                    .foregroundStyle(AppTheme.green)
                    .contentTransition(.numericText())
            }

            HStack(spacing: 12) {
                Image(systemName: "speaker.fill")
                    .font(.caption)
                    .foregroundStyle(AppTheme.muted)
                Slider(value: $volume, in: 0...1, step: 0.05) { editing in
                    if !editing {
                        player.success()
                    }
                }
                .tint(AppTheme.green)
                .accessibilityIdentifier("volumeSlider")
                .accessibilityLabel(AppLocalization.string("効果音の音量"))
                Image(systemName: "speaker.wave.3.fill")
                    .font(.caption)
                    .foregroundStyle(AppTheme.muted)
            }

            Text("スライダーを離すと現在の成功音でプレビューされます")
                .font(.caption2)
                .foregroundStyle(AppTheme.muted)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .scannerCard()
    }

    private func soundCard(
        title: String,
        subtitle: String,
        icon: String,
        tint: Color,
        @ViewBuilder rows: () -> some View
    ) -> some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .foregroundStyle(tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(AppTheme.ink)
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundStyle(AppTheme.muted)
                }
            }
            VStack(spacing: 8, content: rows)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .scannerCard()
    }
}

private struct BluetoothScannerSetupGuide: View {
    let onSearch: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var step = 0
    @State private var enlargedCode: BluetoothScannerSetupCode?

    private let stepCount = 5

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ProgressView(value: Double(step + 1), total: Double(stepCount))
                    .tint(AppTheme.green)
                    .padding(.horizontal, 22)
                    .padding(.top, 14)

                ScrollView {
                    VStack(spacing: 20) {
                        Text(AppLocalization.string("STEP \(step + 1) / \(stepCount)"))
                            .font(.caption2.weight(.black))
                            .tracking(1.8)
                            .foregroundStyle(AppTheme.green)

                        guideContent
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 22)
                    .padding(.vertical, 24)
                }

                HStack(spacing: 12) {
                    if step > 0 {
                        Button("戻る") {
                            step -= 1
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("scannerSetupPreviousButton")
                    }

                    if step < stepCount - 1 {
                        Button {
                            step += 1
                        } label: {
                            HStack(spacing: 8) {
                                Text(
                                    step == 0
                                        ? AppLocalization.string("次へ")
                                        : AppLocalization.string("読み取りました・次へ")
                                )
                                Image(systemName: "chevron.right")
                            }
                            .frame(maxWidth: .infinity, minHeight: 58)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(AppTheme.green)
                        .accessibilityIdentifier("scannerSetupNextButton")
                    } else {
                        Button {
                            onSearch()
                        } label: {
                            Label("スキャナの検索を開始", systemImage: "antenna.radiowaves.left.and.right")
                                .frame(maxWidth: .infinity, minHeight: 58)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(AppTheme.green)
                        .accessibilityIdentifier("scannerSetupSearchButton")
                    }
                }
                .font(.subheadline.weight(.bold))
                .padding(20)
                .background(.ultraThinMaterial)
            }
            .background(AppTheme.paper.ignoresSafeArea())
            .navigationTitle("BCST-47 初回接続")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.light)
        .presentationDetents([.large])
        .accessibilityIdentifier("scannerSetupGuide")
        .fullScreenCover(item: $enlargedCode) { code in
            FullscreenScannerSetupBarcode(code: code)
        }
    }

    @ViewBuilder
    private var guideContent: some View {
        switch step {
        case 0:
            VStack(spacing: 18) {
                Image(systemName: "barcode.viewfinder")
                    .font(.system(size: 64, weight: .medium))
                    .foregroundStyle(AppTheme.green)
                Text("スキャナを準備します")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AppTheme.ink)
                VStack(alignment: .leading, spacing: 12) {
                    setupChecklistRow(number: 1, text: AppLocalization.string("BCST-47の電源を入れます"))
                    setupChecklistRow(number: 2, text: AppLocalization.string("Macなど別の端末との接続を解除します"))
                    setupChecklistRow(
                        number: 3,
                        text: AppLocalization.string("iPhoneの設定アプリではペアリングせず、このガイドを進めます")
                    )
                }
                Text("次の3画面に表示されるコードを、上から順に1回ずつ読み取ってください。")
                    .font(.subheadline)
                    .foregroundStyle(AppTheme.muted)
                    .multilineTextAlignment(.center)
            }
        case 1:
            setupCodePage(.enterSetup)
        case 2:
            setupCodePage(.gattMode)
        case 3:
            setupCodePage(.saveAndExit)
        default:
            VStack(spacing: 18) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 68))
                    .foregroundStyle(AppTheme.green)
                Text("GATTモードの準備完了")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AppTheme.ink)
                Text("スキャナ画面に「BLE_GATT」と成功表示が出たことを確認して、検索を開始してください。iOSのペアリング確認が表示された場合は「ペアリング」を押します。")
                    .font(.subheadline)
                    .foregroundStyle(AppTheme.muted)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                Label(
                    "見つからない場合は、スキャナの電源をOFF／ONしてからもう一度検索します。",
                    systemImage: "power"
                )
                .font(.caption)
                .foregroundStyle(AppTheme.amber)
                .padding(14)
                .background(AppTheme.amber.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }

    private func setupChecklistRow(number: Int, text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.caption.weight(.black))
                .foregroundStyle(AppTheme.paper)
                .frame(width: 26, height: 26)
                .background(AppTheme.ink, in: Circle())
            Text(text)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func setupCodePage(_ code: BluetoothScannerSetupCode) -> some View {
        VStack(spacing: 18) {
            Text(code.title)
                .font(.title2.weight(.bold))
                .foregroundStyle(AppTheme.ink)
            Text("BCST-47で下のコードを1回読み取ります")
                .font(.subheadline)
                .foregroundStyle(AppTheme.muted)
            Code128SetupBarcode(code: code)
            Button {
                enlargedCode = code
            } label: {
                Label("全画面で大きく表示", systemImage: "arrow.up.left.and.arrow.down.right")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppTheme.ink)
            .accessibilityIdentifier("scannerSetupEnlarge_\(code.accessibilityID)")
            VStack(spacing: 5) {
                Text("スキャナ表示の目安")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(AppTheme.muted)
                Text(code.scannerDisplayText)
                    .font(.system(.headline, design: .monospaced, weight: .bold))
                    .foregroundStyle(AppTheme.green)
            }
            Label(code.resultGuidance, systemImage: guidanceIcon(for: code))
                .font(.caption.weight(.semibold))
                .foregroundStyle(code == .enterSetup ? AppTheme.amber : AppTheme.green)
                .multilineTextAlignment(.leading)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    (code == .enterSetup ? AppTheme.amber : AppTheme.green).opacity(0.08),
                    in: RoundedRectangle(cornerRadius: 12)
                )
            Text("読取音または成功表示を確認してから「読み取りました・次へ」を押してください。")
                .font(.caption)
                .foregroundStyle(AppTheme.muted)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
        }
    }

    private func guidanceIcon(for code: BluetoothScannerSetupCode) -> String {
        switch code {
        case .enterSetup: "info.circle.fill"
        case .gattMode: "antenna.radiowaves.left.and.right"
        case .saveAndExit: "checkmark.seal.fill"
        }
    }
}

private struct Code128SetupBarcode: View {
    let code: BluetoothScannerSetupCode

    var body: some View {
        Group {
            if let image = Self.makeImage(payload: code.rawValue) {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
            } else {
                Label("設定コードを表示できません", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(AppTheme.red)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 128)
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 16))
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .stroke(AppTheme.line, lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(AppLocalization.string("\(code.title)のCode 128設定コード"))
        .accessibilityIdentifier("scannerSetupBarcode_\(code.accessibilityID)")
    }

    fileprivate static func makeImage(payload: String) -> UIImage? {
        let filter = CIFilter.code128BarcodeGenerator()
        filter.message = Data(payload.utf8)
        filter.quietSpace = 18
        guard let outputImage = filter.outputImage else { return nil }

        // The BCST-47 setup manual uses substantially taller bars than Core Image's
        // default aspect ratio. Preserve the Code 128 modules while elongating only
        // the bar height so the physical scanner can acquire them from an iPhone.
        let scaled = outputImage.transformed(by: CGAffineTransform(scaleX: 4, y: 8))
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let image = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}

private struct FullscreenScannerSetupBarcode: View {
    let code: BluetoothScannerSetupCode

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        GeometryReader { proxy in
            let barcodeWidth = min(max(proxy.size.height - 240, 480), 680)
            let barcodeHeight = min(max(proxy.size.width - 120, 120), barcodeWidth / 3.45)

            Color.white
                .ignoresSafeArea()
                .overlay {
                    if let image = Code128SetupBarcode.makeImage(payload: code.rawValue) {
                        Image(uiImage: image)
                            .resizable()
                            .interpolation(.none)
                            .scaledToFit()
                            .frame(width: barcodeWidth, height: barcodeHeight)
                            .rotationEffect(.degrees(90))
                            .accessibilityIdentifier("scannerSetupFullscreenBarcode_\(code.accessibilityID)")
                    } else {
                        Label("設定コードを表示できません", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(AppTheme.red)
                    }
                }
                .overlay(alignment: .top) {
                    VStack(spacing: 5) {
                        Text(code.title)
                            .font(.headline.weight(.bold))
                            .foregroundStyle(AppTheme.ink)
                        Text("iPhoneを横向きにして、画面全体のコードを読み取ります")
                            .font(.caption)
                            .foregroundStyle(AppTheme.muted)
                    }
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 60)
                    .padding(.top, 24)
                }
                .overlay(alignment: .topTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 34))
                            .foregroundStyle(AppTheme.ink)
                            .padding(18)
                    }
                    .accessibilityLabel(AppLocalization.string("拡大表示を閉じる"))
                    .accessibilityIdentifier("scannerSetupFullscreenCloseButton")
                }
                .overlay(alignment: .bottom) {
                    Label("読取音またはスキャナ画面の表示を確認してください", systemImage: "barcode.viewfinder")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.green)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(AppTheme.green.opacity(0.08), in: Capsule())
                        .padding(.bottom, 30)
                }
        }
        .preferredColorScheme(.light)
    }
}

private struct SoundOptionRow: View {
    let label: String
    let isSelected: Bool
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isSelected ? tint : AppTheme.line)
                Text(label)
                    .font(.subheadline.weight(isSelected ? .bold : .regular))
                    .foregroundStyle(AppTheme.ink)
                Spacer()
                Image(systemName: "play.circle")
                    .font(.title3)
                    .foregroundStyle(AppTheme.muted.opacity(0.7))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(
                isSelected ? tint.opacity(0.08) : AppTheme.ink.opacity(0.03),
                in: RoundedRectangle(cornerRadius: 12)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? tint.opacity(0.5) : .clear, lineWidth: 1.5)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isSelected ? AppLocalization.string("\(label)、選択中") : label)
        .accessibilityHint(AppLocalization.string("タップで選択して試聴します"))
    }
}

#Preview {
    SettingsScreen(bluetoothScanner: BluetoothScannerService(), scanLog: ScanLogStore())
}
