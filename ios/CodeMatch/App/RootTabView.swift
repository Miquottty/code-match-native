import SwiftUI

struct RootTabView: View {
    @StateObject private var historyStore: HistoryStore
    @StateObject private var scanLog: ScanLogStore
    @StateObject private var bluetoothScanner: BluetoothScannerService
    @StateObject private var cameraScanner: CameraScanner
    @Environment(\.scenePhase) private var scenePhase

    init(cameraScanner: @autoclosure @escaping () -> CameraScanner = CameraScanner()) {
        // このinitはCodeMatchApp.bodyの再評価ごとに呼ばれる。ストア生成・
        // AVCaptureSessionのセットアップ・UIテスト用リセットの副作用を
        // 毎回実行するとUIが再描画ループへ陥るため、@StateObjectの
        // autoclosureへ包んで最初のレンダリング時の一度きりに限定する。
        _historyStore = StateObject(wrappedValue: Self.makeHistoryStore())
        _scanLog = StateObject(wrappedValue: ScanLogStore())
        _bluetoothScanner = StateObject(wrappedValue: BluetoothScannerService())
        _cameraScanner = StateObject(wrappedValue: cameraScanner())
    }

    private static func makeHistoryStore() -> HistoryStore {
        let arguments = ProcessInfo.processInfo.arguments
        AutoAdvanceSettings.resetForUITestingIfRequested(arguments: arguments)
        let store = HistoryStore()
        if arguments.contains("-demoMatch") || arguments.contains("-demoMismatch") {
            store.beginSession()
        }
        return store
    }

    var body: some View {
        TabView {
            ScannerFlowView(
                historyStore: historyStore,
                scanLog: scanLog,
                bluetoothScanner: bluetoothScanner,
                cameraScanner: cameraScanner
            )
                .tabItem {
                    Label(AppLocalization.string("照合"), systemImage: "barcode.viewfinder")
                }

            HistoryScreen(historyStore: historyStore)
                .tabItem {
                    Label(AppLocalization.string("履歴"), systemImage: "clock.arrow.circlepath")
                }

            SettingsScreen(bluetoothScanner: bluetoothScanner, scanLog: scanLog)
                .tabItem {
                    Label(AppLocalization.string("設定"), systemImage: "gearshape.fill")
                }
        }
        .tint(AppTheme.green)
        .preferredColorScheme(.light)
        .task {
            bluetoothScanner.recordApplicationPhase(String(describing: scenePhase))
            bluetoothScanner.setApplicationActive(scenePhase == .active)
        }
        .onChange(of: scenePhase) { _, phase in
            bluetoothScanner.recordApplicationPhase(String(describing: phase))
            bluetoothScanner.setApplicationActive(phase == .active)
        }
    }
}

private struct ScannerFlowView: View {
    @ObservedObject var historyStore: HistoryStore
    @ObservedObject var scanLog: ScanLogStore
    @ObservedObject var bluetoothScanner: BluetoothScannerService
    @ObservedObject var cameraScanner: CameraScanner

    var body: some View {
        Group {
            if let session = historyStore.activeSession {
                ScannerScreen(
                    historyStore: historyStore,
                    scanLog: scanLog,
                    bluetoothScanner: bluetoothScanner,
                    cameraScanner: cameraScanner,
                    sessionID: session.id
                )
                    .id(session.id)
            } else {
                SessionStartView(
                    historyStore: historyStore,
                    scanLog: scanLog,
                    bluetoothScanner: bluetoothScanner
                )
            }
        }
    }
}

private struct SessionStartView: View {
    @ObservedObject var historyStore: HistoryStore
    @ObservedObject var scanLog: ScanLogStore
    @ObservedObject var bluetoothScanner: BluetoothScannerService
    @State private var sessionName = ""
    @Environment(\.locale) private var locale

    var body: some View {
        ZStack {
            AppTheme.paper.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(AppLocalization.string("SCAN & VERIFY"))
                            .font(.caption2.weight(.black))
                            .tracking(2.2)
                            .foregroundStyle(AppTheme.green)

                        Text(AppLocalization.string("照合作業を、\n記録して始める。"))
                        .font(.system(size: 36, weight: .bold, design: .rounded))
                        .tracking(-1.2)
                        .foregroundStyle(AppTheme.ink)

                        Text(
                            AppLocalization.string(
                                "作業単位ごとにセッションを開始します。一致したコードと時刻が端末内の履歴へ記録されます。"
                            )
                        )
                            .font(.subheadline)
                            .foregroundStyle(AppTheme.muted)
                            .lineSpacing(5)
                    }

                    VStack(spacing: 18) {
                        Image(systemName: "list.clipboard.fill")
                            .font(.system(size: 42))
                            .foregroundStyle(AppTheme.green)
                            .frame(width: 86, height: 86)
                            .background(AppTheme.green.opacity(0.10), in: Circle())

                        VStack(spacing: 6) {
                        Text(AppLocalization.string("新しい照合セッション"))
                            .font(.title3.weight(.bold))
                        Text(AppLocalization.string("開始後は照合済み件数を常に確認できます"))
                            .font(.caption)
                            .foregroundStyle(AppTheme.muted)
                    }

                        TextField(AppLocalization.string("セッション名（任意）"), text: $sessionName)
                            .font(.subheadline)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .background(AppTheme.ink.opacity(0.04), in: RoundedRectangle(cornerRadius: 12))
                            .overlay {
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(AppTheme.line, lineWidth: 1)
                            }
                            .submitLabel(.done)
                            .accessibilityIdentifier("sessionNameField")

                        Button {
                            let sessionID = historyStore.beginSession(name: sessionName)
                            scanLog.record(
                                ScanLogEvent(
                                    at: Date(),
                                    session: sessionID,
                                    // 接続済みならBluetoothが初期入力になる。
                                    source: bluetoothScanner.isConnected
                                        ? ScanInputSource.bluetooth.scanLogValue
                                        : ScanInputSource.camera.scanLogValue,
                                    step: "none",
                                    event: "session_start"
                                )
                            )
                            sessionName = ""
                        } label: {
                            Label(AppLocalization.string("記録を開始する"), systemImage: "play.fill")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .frame(minHeight: 68)
                                .contentShape(Rectangle())
                                .foregroundStyle(.white)
                                .background(
                                    AppTheme.green,
                                    in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                                )
                        }
                        .buttonStyle(.plain)
                        .contentShape(Rectangle())
                        .accessibilityIdentifier("startSessionButton")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(22)
                    .scannerCard()

                    if !historyStore.sessions.isEmpty {
                        Label(
                            AppLocalization.string("これまでに\(historyStore.sessions.count)セッションを端末内へ保存しています"),
                            systemImage: "clock.arrow.circlepath"
                        )
                        .font(.caption)
                        .foregroundStyle(AppTheme.muted)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 24)
                .padding(.bottom, 32)
            }
        }
    }
}
