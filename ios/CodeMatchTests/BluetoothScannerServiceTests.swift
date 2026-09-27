import XCTest
import AVFoundation
import Combine
@testable import CodeMatch

@MainActor
final class BluetoothScannerServiceTests: XCTestCase {
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

    func testInitialGATTSetupCodesUseTheVerifiedInateckSequence() {
        XCTAssertEqual(
            BluetoothScannerSetupCode.allCases.map(\.rawValue),
            ["/*EnterSet*/", "/*BLE_GATT*/", "/*ExitSave*/"]
        )
    }

    func testSymbologyModeStatusTextExplainsActiveRestriction() {
        XCTAssertEqual(BluetoothScannerSymbologyMode(expectedCode: .qr), .sessionCodes)
        XCTAssertEqual(BluetoothScannerSymbologyMode(expectedCode: .barcode), .sessionCodes)
        XCTAssertEqual(BluetoothScannerSymbologyMode(expectedCode: nil), .unrestricted)
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.unrestricted.statusText,
            "読取対象：接続前の設定へ復元済み"
        )
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.sessionCodes.statusText,
            "読取対象：QR・Code 128（照合セッション）"
        )
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.qrOnly.statusText,
            "読取対象：QRのみ（旧設定から復旧中）"
        )
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.code128Only.statusText,
            "読取対象：Code 128のみ（旧設定から復旧中）"
        )
    }

    func testLogicalStepChangesDoNotReconfigureReadySessionMode() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        service.connect(service.devices[0])

        var updates: [BluetoothScannerConfigurationState] = []
        let observation = service.$configurationState
            .dropFirst()
            .sink { updates.append($0) }

        service.setExpectedCode(.qr)
        XCTAssertEqual(updates, [.ready])
        updates.removeAll()

        service.setExpectedCode(.barcode)
        service.setExpectedCode(.qr)

        XCTAssertTrue(updates.isEmpty)
        XCTAssertEqual(service.expectedCode, .qr)
        XCTAssertEqual(service.persistedSymbologyMode, .sessionCodes)
        withExtendedLifetime(observation) {}
    }

    func testSymbologyCommandUsesEveryBarcodeTypeReportedByScanner() throws {
        let settings = """
        {"data":[
          {"area":"11","value":"1","name":"code39_on"},
          {"area":"42","value":"1","name":"qrcode_on"},
          {"area":17,"value":1,"name":"code128_on"},
          {"area":"12","value":"0","name":"ean_13_on"},
          {"area":"15","value":"1","name":"USPS_On","flag":3019},
          {"area":"34","value":"1","name":"rss_expanded_on","flag":3038},
          {"area":"31","value":"1","name":"shake_reminder"}
        ]}
        """
        let original = try XCTUnwrap(
            BluetoothScannerService.symbologySettingValues(from: settings)
        )
        XCTAssertEqual(
            original,
            [
                "code39_on": 1,
                "qrcode_on": 1,
                "code128_on": 1,
                "ean_13_on": 0,
                "USPS_On": 1,
                "rss_expanded_on": 1
            ]
        )

        let restricted = try XCTUnwrap(
            BluetoothScannerService.symbologySettingValues(
                for: .sessionCodes,
                original: original
            )
        )
        XCTAssertEqual(
            restricted,
            [
                "code39_on": 0,
                "qrcode_on": 1,
                "code128_on": 1,
                "ean_13_on": 0,
                "USPS_On": 0,
                "rss_expanded_on": 0
            ]
        )

        let command = try XCTUnwrap(
            BluetoothScannerService.symbologySettingCommand(
                values: restricted,
                settings: settings
            )
        )
        let data = try XCTUnwrap(command.data(using: .utf8))
        let items = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [[String: String]]
        )

        XCTAssertEqual(items.first(where: { $0["name"] == "qrcode_on" })?["area"], "42")
        XCTAssertEqual(items.first(where: { $0["name"] == "qrcode_on" })?["value"], "1")
        XCTAssertEqual(items.first(where: { $0["name"] == "code128_on" })?["area"], "17")
        XCTAssertEqual(items.first(where: { $0["name"] == "code128_on" })?["value"], "1")
        XCTAssertEqual(items.first(where: { $0["name"] == "code39_on" })?["value"], "0")
        XCTAssertEqual(items.first(where: { $0["name"] == "ean_13_on" })?["value"], "0")
        XCTAssertEqual(items.first(where: { $0["name"] == "USPS_On" })?["value"], "0")
        XCTAssertEqual(items.first(where: { $0["name"] == "rss_expanded_on" })?["value"], "0")
        XCTAssertNil(items.first(where: { $0["name"] == "shake_reminder" }))
        XCTAssertTrue(BluetoothScannerService.hasRequiredSymbologySettings(settings))
        XCTAssertFalse(
            BluetoothScannerService.hasRequiredSymbologySettings(
                "{\"data\":[{\"area\":\"42\",\"value\":\"1\",\"name\":\"qrcode_on\"}]}"
            )
        )
    }

    func testOriginalBarcodeSettingsRoundTripWithoutChangingValues() throws {
        let settings = """
        {"data":[
          {"area":"11","value":"0","name":"code39_on"},
          {"area":"11","value":"1","name":"code128_on"},
          {"area":"12","value":"1","name":"ean_13_on"},
          {"area":"28","value":"0","name":"qrcode_on"},
          {"area":"27","value":"1","name":"datamatrix_on"}
        ]}
        """
        let original = try XCTUnwrap(
            BluetoothScannerService.symbologySettingValues(from: settings)
        )
        let restricted = try XCTUnwrap(
            BluetoothScannerService.symbologySettingValues(
                for: .sessionCodes,
                original: original
            )
        )
        XCTAssertEqual(restricted.values.filter { $0 == 1 }.count, 2)
        XCTAssertEqual(restricted["code128_on"], 1)
        XCTAssertEqual(restricted["qrcode_on"], 1)

        let restoreCommand = try XCTUnwrap(
            BluetoothScannerService.symbologySettingCommand(
                values: original,
                settings: settings
            )
        )
        let data = try XCTUnwrap(restoreCommand.data(using: .utf8))
        let items = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [[String: String]]
        )
        XCTAssertEqual(
            Dictionary(uniqueKeysWithValues: items.compactMap { item in
                guard let name = item["name"], let value = item["value"].flatMap(Int.init) else {
                    return nil
                }
                return (name, value)
            }),
            original
        )
    }

    func testNormalizedPayloadRemovesOnlyTrailingTransportTerminators() {
        XCTAssertEqual(
            BluetoothScannerService.normalizedPayload("QR DATA   0*\r\n\0"),
            "QR DATA   0*"
        )
        XCTAssertEqual(BluetoothScannerService.normalizedPayload("  KEEP  "), "  KEEP  ")
    }

    func testDecodedSDKScanPayloadUnwrapsPinnedIOSSDKJSON() throws {
        let expected = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*\n"
        let callback = """
        {"code":"DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*\\n","source_code":"44434C5036373533303042434A483532383147473032303030303132303030303030313230304C303030303030303030303030424C4244494C4C553932202020302A0A","status":0}
        """

        XCTAssertEqual(BluetoothScannerService.decodedSDKScanPayload(callback), expected)
    }

    func testDecodedSDKScanPayloadUnwrapsScannerLibNotificationJSON() throws {
        let expected = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*\n"
        let jsonObject: [String: Any] = [
            "notify_type": 1,
            "notify_status": 1,
            "notify_data": Array(expected.utf8)
        ]
        let data = try JSONSerialization.data(withJSONObject: jsonObject)
        let callback = try XCTUnwrap(String(data: data, encoding: .utf8))

        XCTAssertEqual(BluetoothScannerService.decodedSDKScanPayload(callback), expected)
    }

    func testDecodedSDKScanPayloadKeepsDirectTextAndRejectsNonScanNotifications() throws {
        XCTAssertEqual(BluetoothScannerService.decodedSDKScanPayload("DIRECT-CODE"), "DIRECT-CODE")

        let configuration = """
        {"notify_type":0,"notify_status":1,"notify_data":[1,2,3]}
        """
        let incomplete = """
        {"notify_type":1,"notify_status":0,"notify_data":[68,67]}
        """
        let failedCode = """
        {"code":"BAD","source_code":"424144","status":1}
        """
        XCTAssertNil(BluetoothScannerService.decodedSDKScanPayload(configuration))
        XCTAssertNil(BluetoothScannerService.decodedSDKScanPayload(incomplete))
        XCTAssertNil(BluetoothScannerService.decodedSDKScanPayload(failedCode))
    }

    func testSimulatorDiscoveryConnectAndPreferredReconnect() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)

        service.startDiscovery()
        XCTAssertEqual(service.devices.count, 1)
        XCTAssertFalse(service.isConnected)

        service.connect(service.devices[0])
        XCTAssertTrue(service.isConnected)
        XCTAssertEqual(
            defaults.string(forKey: BluetoothScannerService.preferredDeviceIDKey),
            service.devices[0].id
        )

        let restored = BluetoothScannerService(defaults: defaults)
        restored.reconnectPreferredDevice()
        XCTAssertTrue(restored.isConnected)
        XCTAssertEqual(restored.connectedDevice?.id, service.devices[0].id)
    }

    func testDiagnosticsKeepOnlyRecentConnectionEventsWithoutScanPayloads() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)

        for _ in 0..<BluetoothScannerService.diagnosticEventLimit {
            service.startDiscovery()
            service.stopDiscovery()
        }
        service.connect(service.devices[0])
        service.simulateScan("PRIVATE-SCAN-PAYLOAD")

        XCTAssertEqual(service.diagnosticEvents.count, BluetoothScannerService.diagnosticEventLimit)
        XCTAssertTrue(
            service.diagnosticEvents.contains(where: { $0.message.contains("Connect requested") })
        )
        XCTAssertFalse(
            service.diagnosticEvents.contains(where: { $0.message.contains("PRIVATE-SCAN-PAYLOAD") })
        )

        let relaunched = BluetoothScannerService(defaults: defaults)
        XCTAssertLessThanOrEqual(
            relaunched.diagnosticEvents.count,
            BluetoothScannerService.diagnosticEventLimit
        )
        XCTAssertTrue(
            relaunched.diagnosticEvents.contains(where: { $0.message.contains("Connect requested") })
        )
    }

    func testRelaunchRecoversRestrictedScannerToSafeBaseline() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let firstLaunch = BluetoothScannerService(defaults: defaults)
        firstLaunch.startDiscovery()
        firstLaunch.connect(firstLaunch.devices[0])
        firstLaunch.setExpectedCode(.barcode)

        XCTAssertEqual(firstLaunch.persistedSymbologyMode, .sessionCodes)
        XCTAssertEqual(
            firstLaunch.persistedSymbologySnapshot(for: "SIMULATOR-BCST-47")?.values,
            [
                "code39_on": 1,
                "code128_on": 1,
                "ean_13_on": 1,
                "qrcode_on": 1,
                "datamatrix_on": 1
            ]
        )

        // 照合セッション中にプロセスが終了した状況を、同じUserDefaultsを使う
        // 新しいサービスインスタンスで再現する。照合画面がなくても再接続時に
        // 保存した照合開始前の全バーコード設定へ戻す。
        let relaunched = BluetoothScannerService(defaults: defaults)
        relaunched.reconnectPreferredDevice()

        XCTAssertTrue(relaunched.isReadyForScanning)
        XCTAssertNil(relaunched.expectedCode)
        XCTAssertEqual(relaunched.persistedSymbologyMode, .unrestricted)
        XCTAssertTrue(relaunched.persistedSymbologySnapshots.isEmpty)
    }

    func testRelaunchRecoversLegacyCode128OnlyStateFromStuckBuild() throws {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let deviceID = "SIMULATOR-BCST-47"
        let snapshot = BluetoothScannerSymbologySnapshot(
            deviceID: deviceID,
            values: [
                "code39_on": 1,
                "code128_on": 1,
                "ean_13_on": 1,
                "qrcode_on": 1,
                "datamatrix_on": 1
            ]
        )
        defaults.set(deviceID, forKey: BluetoothScannerService.preferredDeviceIDKey)
        defaults.set(deviceID, forKey: BluetoothScannerService.lastKnownDeviceIDKey)
        defaults.set("BCST-47 (Simulator)", forKey: BluetoothScannerService.lastKnownDeviceNameKey)
        defaults.set(
            BluetoothScannerSymbologyMode.code128Only.rawValue,
            forKey: BluetoothScannerService.symbologyRecoveryModeKey
        )
        defaults.set(
            try JSONEncoder().encode(snapshot),
            forKey: BluetoothScannerSymbologySnapshotStore.legacySnapshotKey
        )

        let relaunched = BluetoothScannerService(defaults: defaults)
        XCTAssertEqual(relaunched.persistedSymbologyMode, .code128Only)

        relaunched.reconnectPreferredDevice()

        XCTAssertTrue(relaunched.isReadyForScanning)
        XCTAssertNil(relaunched.expectedCode)
        XCTAssertEqual(relaunched.persistedSymbologyMode, .unrestricted)
        XCTAssertTrue(relaunched.persistedSymbologySnapshots.isEmpty)
        XCTAssertNil(defaults.data(forKey: BluetoothScannerSymbologySnapshotStore.legacySnapshotKey))
    }

    func testManualDisconnectRestoresSafeBaseline() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        service.connect(service.devices[0])
        service.setExpectedCode(.qr)

        XCTAssertEqual(service.persistedSymbologyMode, .sessionCodes)

        service.disconnect()

        XCTAssertFalse(service.isConnected)
        XCTAssertFalse(service.isReadyForScanning)
        XCTAssertNil(service.expectedCode)
        XCTAssertEqual(service.persistedSymbologyMode, .unrestricted)
        XCTAssertTrue(service.persistedSymbologySnapshots.isEmpty)
    }

    func testSnapshotStoreKeepsEachScannerSeparately() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = BluetoothScannerSymbologySnapshotStore(defaults: defaults)
        let first = BluetoothScannerSymbologySnapshot(deviceID: "SCANNER-A", values: ["qrcode_on": 1])
        let second = BluetoothScannerSymbologySnapshot(deviceID: "SCANNER-B", values: ["code128_on": 0])

        store.save(first)
        store.save(second)
        XCTAssertEqual(store.snapshot(for: "SCANNER-A"), first)
        XCTAssertEqual(store.snapshot(for: "SCANNER-B"), second)

        store.remove(deviceID: "SCANNER-A")
        XCTAssertNil(store.snapshot(for: "SCANNER-A"))
        XCTAssertEqual(store.snapshot(for: "SCANNER-B"), second)

        store.remove(deviceID: "SCANNER-B")
        XCTAssertTrue(store.all.isEmpty)
        XCTAssertNil(defaults.data(forKey: BluetoothScannerSymbologySnapshotStore.snapshotsKey))
    }

    func testSnapshotStoreCarriesOverTheLegacySingleSlotForItsOwnScanner() throws {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let legacy = BluetoothScannerSymbologySnapshot(deviceID: "SCANNER-A", values: ["qrcode_on": 1])
        defaults.set(
            try JSONEncoder().encode(legacy),
            forKey: BluetoothScannerSymbologySnapshotStore.legacySnapshotKey
        )
        let store = BluetoothScannerSymbologySnapshotStore(defaults: defaults)

        XCTAssertEqual(store.snapshot(for: "SCANNER-A"), legacy)
        XCTAssertNil(store.snapshot(for: "SCANNER-B"))

        let other = BluetoothScannerSymbologySnapshot(deviceID: "SCANNER-B", values: ["qrcode_on": 0])
        store.save(other)
        XCTAssertNil(defaults.data(forKey: BluetoothScannerSymbologySnapshotStore.legacySnapshotKey))
        XCTAssertEqual(store.snapshot(for: "SCANNER-A"), legacy)
        XCTAssertEqual(store.snapshot(for: "SCANNER-B"), other)
    }

    /// 照合中に離れた別のスキャナーの復元待ちがあっても、今つないだスキャナーは
    /// 照合に使え、別のスキャナーの分は次回接続での復元用に残る（#135）。
    func testAnotherScannersPendingRestoreDoesNotBlockTheConnectedScanner() throws {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let otherScanner = BluetoothScannerSymbologySnapshot(
            deviceID: "OTHER-SCANNER",
            values: ["qrcode_on": 1, "code128_on": 1, "ean_13_on": 1]
        )
        defaults.set(
            try JSONEncoder().encode(otherScanner),
            forKey: BluetoothScannerSymbologySnapshotStore.legacySnapshotKey
        )
        defaults.set(
            BluetoothScannerSymbologyMode.sessionCodes.rawValue,
            forKey: BluetoothScannerService.symbologyRecoveryModeKey
        )

        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        service.connect(service.devices[0])
        XCTAssertTrue(service.isReadyForScanning)

        service.setExpectedCode(.qr)
        XCTAssertTrue(service.isReadyForScanning)
        XCTAssertEqual(service.persistedSymbologyMode, .sessionCodes)
        XCTAssertNotNil(service.persistedSymbologySnapshot(for: "SIMULATOR-BCST-47"))
        XCTAssertEqual(service.persistedSymbologySnapshot(for: "OTHER-SCANNER"), otherScanner)
        XCTAssertTrue(service.diagnosticLogText().contains("pending restores: 2"))

        service.disconnect()
        XCTAssertNil(service.persistedSymbologySnapshot(for: "SIMULATOR-BCST-47"))
        XCTAssertEqual(service.persistedSymbologySnapshot(for: "OTHER-SCANNER"), otherScanner)
    }

    func testGATTModeSuccessorIsTheScannerRenamedWithTheSameSuffix() {
        let origin = BluetoothScannerDevice(id: "2B94A2A1", name: "Hyper 160B-4F5F-UNI")
        let renamed = BluetoothScannerDevice(id: "4CD0F65B", name: "HPRT-4F5F")
        let unrelated = BluetoothScannerDevice(id: "6680C70B", name: "HPRT-636E")
        // 「Hyper 160B」の160Bは「-」区切りの一部ではないため、別のスキャナーと取り違えない。
        let modelNumber = BluetoothScannerDevice(id: "11111111", name: "HPRT-160B")

        XCTAssertEqual(
            BluetoothScannerService.gattModeSuccessors(
                of: origin,
                among: [origin, unrelated, renamed, modelNumber, renamed]
            ),
            [renamed]
        )
        XCTAssertEqual(
            BluetoothScannerService.gattModeSuccessors(
                of: BluetoothScannerDevice(id: "2B94A2A1", name: "hyper 160b-4f5f-uni"),
                among: [renamed]
            ),
            [renamed]
        )
    }

    func testGATTModeSuccessorIsEmptyWhenTheIdentityDoesNotChange() {
        let origin = BluetoothScannerDevice(id: "4CD0F65B", name: "HPRT-4F5F")
        XCTAssertTrue(
            BluetoothScannerService.gattModeSuccessors(
                of: origin,
                among: [origin, BluetoothScannerDevice(id: "6680C70B", name: "HPRT-636E")]
            ).isEmpty
        )
        XCTAssertTrue(
            BluetoothScannerService.gattModeSuccessors(
                of: BluetoothScannerDevice(id: "A", name: "Inateck Scanner"),
                among: [BluetoothScannerDevice(id: "B", name: "Inateck Scanner")]
            ).isEmpty
        )
    }

    func testManualDisconnectKeepsKnownDeviceAvailableForReconnectAfterSearch() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        let device = service.devices[0]
        service.connect(device)

        service.disconnect()

        XCTAssertNil(defaults.string(forKey: BluetoothScannerService.preferredDeviceIDKey))
        XCTAssertEqual(service.reconnectableDevice, device)

        // 実機SDKでは、iOSと接続済みのスキャナが広告を出さず検索結果が0件でも、
        // SDKキャッシュと保存済み端末を捨てずに再接続できる必要がある。
        service.startDiscovery()
        XCTAssertEqual(service.reconnectableDevice, device)
        service.reconnectKnownDevice()

        XCTAssertTrue(service.isReadyForScanning)
        XCTAssertEqual(service.connectedDevice, device)
        XCTAssertEqual(
            defaults.string(forKey: BluetoothScannerService.preferredDeviceIDKey),
            device.id
        )
    }

    func testKnownDeviceSurvivesServiceRelaunchAfterManualDisconnect() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        let device = service.devices[0]
        service.connect(device)
        service.disconnect()

        let relaunched = BluetoothScannerService(defaults: defaults)

        XCTAssertEqual(relaunched.reconnectableDevice, device)
        relaunched.reconnectKnownDevice()
        XCTAssertTrue(relaunched.isReadyForScanning)
        XCTAssertEqual(relaunched.connectedDevice, device)
    }

    func testUpgradeMigratesLastConnectedDeviceFromDiagnosticsAfterOldManualDisconnect() throws {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let device = BluetoothScannerDevice(
            id: "9BBF90F3-6D04-6D53-69D5-E101FF61E548",
            name: "Nano 160D-636E-UNI"
        )
        let events = [
            BluetoothScannerDiagnosticEvent(
                date: Date(timeIntervalSince1970: 1),
                message: "Connected: \(device.name) [\(device.id)]"
            ),
            BluetoothScannerDiagnosticEvent(
                date: Date(timeIntervalSince1970: 2),
                message: "Scanner Bluetooth mode confirmed: GATT (2)"
            ),
            BluetoothScannerDiagnosticEvent(
                date: Date(timeIntervalSince1970: 3),
                message: "Disconnected"
            )
        ]
        defaults.set(
            try JSONEncoder().encode(events),
            forKey: BluetoothScannerService.diagnosticEventsKey
        )
        defaults.removeObject(forKey: BluetoothScannerService.preferredDeviceIDKey)

        let upgraded = BluetoothScannerService(defaults: defaults)

        XCTAssertEqual(upgraded.reconnectableDevice, device)
        XCTAssertEqual(
            defaults.string(forKey: BluetoothScannerService.lastKnownDeviceIDKey),
            device.id
        )
        XCTAssertEqual(
            defaults.string(forKey: BluetoothScannerService.lastKnownDeviceNameKey),
            device.name
        )
    }

    func testDuplicateCallbackIsSuppressedInsideDebounceWindow() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        var currentDate = Date(timeIntervalSince1970: 1_700_000_000)
        let service = BluetoothScannerService(defaults: defaults, now: { currentDate })
        service.startDiscovery()
        service.connect(service.devices[0])
        var received: [String] = []
        service.onCode = { received.append($0) }

        service.simulateScan("ABC\r")
        currentDate.addTimeInterval(0.2)
        service.simulateScan("ABC\n")
        currentDate.addTimeInterval(0.8)
        service.simulateScan("ABC")

        XCTAssertEqual(received, ["ABC", "ABC"])
    }

    private let suiteName = "BluetoothScannerServiceTests"

    func testIlluminationSettingUsesReportedAreaAndConfirmsReadback() throws {
        let inventory = """
        {"status":0,"data":[
          {"area":"7","name":"code128_on","value":"1","flag":"2008"},
          {"area":"21","name":"qrcode_on","value":"1","flag":"2022"},
          {"area":"33","name":"lighting_lamp_control","value":"2","flag":"1003"},
          {"area":"1","name":"bt_mode_low","value":"0"}
        ]}
        """
        XCTAssertEqual(
            BluetoothScannerService.illuminationSetting(from: inventory),
            BluetoothScannerService.IlluminationSetting(area: "33", value: 2)
        )

        let command = try XCTUnwrap(
            BluetoothScannerService.illuminationCommand(settings: inventory, enabled: true)
        )
        let items = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(command.utf8)) as? [[String: String]]
        )
        XCTAssertEqual(items, [["area": "33", "name": "lighting_lamp_control", "value": "0"]])

        let readbackOn = inventory.replacingOccurrences(
            of: "\"lighting_lamp_control\",\"value\":\"2\"",
            with: "\"lighting_lamp_control\",\"value\":\"0\""
        )
        XCTAssertTrue(
            BluetoothScannerService.illuminationConfirmed(settings: readbackOn, area: "33", enabled: true)
        )
        XCTAssertFalse(
            BluetoothScannerService.illuminationConfirmed(settings: inventory, area: "33", enabled: true)
        )
        XCTAssertFalse(
            BluetoothScannerService.illuminationConfirmed(settings: readbackOn, area: "34", enabled: true)
        )

        // 項目が無い・重複・範囲外の値は扱わない。
        let missing = """
        {"status":0,"data":[{"area":"7","name":"code128_on","value":"1"}]}
        """
        XCTAssertNil(BluetoothScannerService.illuminationSetting(from: missing))
        XCTAssertNil(BluetoothScannerService.illuminationCommand(settings: missing, enabled: false))
        let duplicated = """
        {"status":0,"data":[
          {"area":"33","name":"lighting_lamp_control","value":"2"},
          {"area":"34","name":"lighting_lamp_control","value":"2"}
        ]}
        """
        XCTAssertNil(BluetoothScannerService.illuminationSetting(from: duplicated))
        let outOfRange = """
        {"status":0,"data":[{"area":"33","name":"lighting_lamp_control","value":"5"}]}
        """
        XCTAssertNil(BluetoothScannerService.illuminationSetting(from: outOfRange))
    }

    func testSimulatorIlluminationStartsOffAndFollowsRequests() {
        let defaults = isolatedDefaults()
        let service = BluetoothScannerService(defaults: defaults)
        XCTAssertEqual(service.illuminationState, .unknown)

        service.startDiscovery()
        service.connect(service.devices[0])
        XCTAssertEqual(service.illuminationState, .off)

        service.setIllumination(true)
        XCTAssertEqual(service.illuminationState, .on)
        service.setIllumination(false)
        XCTAssertEqual(service.illuminationState, .off)

        service.disconnect()
        XCTAssertEqual(service.illuminationState, .unknown)
        service.setIllumination(true)
        XCTAssertEqual(service.illuminationState, .unknown)
    }

    func testTuningProfileWritesOnlyDifferingItemsUsingReportedAreas() throws {
        let inventory = """
        {"status":0,"data":[
          {"area":"7","name":"code128_on","value":"1"},
          {"area":"40","name":"qrcode_read_more_code","value":"0"},
          {"area":"41","name":"datamatrix_read_multi","value":"1"},
          {"area":"42","name":"read_inverse_color","value":"0"},
          {"area":"43","name":"time_auto_off","value":"10"},
          {"area":"44","name":"auto_close_mode","value":"10"},
          {"area":"33","name":"lighting_lamp_control","value":"2"}
        ]}
        """
        let present = try XCTUnwrap(BluetoothScannerService.tuningItemsPresent(in: inventory))
        XCTAssertEqual(
            present.map(\.name),
            ["qrcode_read_more_code", "datamatrix_read_multi", "read_inverse_color", "auto_close_mode"]
        )

        let differences = BluetoothScannerService.tuningDifferences(settings: inventory)
        XCTAssertEqual(
            differences,
            [
                BluetoothScannerService.TuningItem(name: "datamatrix_read_multi", value: 0),
                BluetoothScannerService.TuningItem(name: "auto_close_mode", value: 20)
            ]
        )

        let command = try XCTUnwrap(
            BluetoothScannerService.tuningCommand(settings: inventory, items: differences)
        )
        let items = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(command.utf8)) as? [[String: String]]
        )
        XCTAssertEqual(items.count, 2)
        XCTAssertTrue(items.contains(["area": "41", "name": "datamatrix_read_multi", "value": "0"]))
        XCTAssertTrue(items.contains(["area": "44", "name": "auto_close_mode", "value": "20"]))

        // 既に一致していれば書く項目はない。inventoryに対象項目が無ければ空。
        let matching = inventory
            .replacingOccurrences(of: "\"datamatrix_read_multi\",\"value\":\"1\"", with: "\"datamatrix_read_multi\",\"value\":\"0\"")
            .replacingOccurrences(of: "\"auto_close_mode\",\"value\":\"10\"", with: "\"auto_close_mode\",\"value\":\"20\"")
        XCTAssertTrue(BluetoothScannerService.tuningDifferences(settings: matching).isEmpty)
        XCTAssertNil(BluetoothScannerService.tuningCommand(settings: matching, items: []))
        let none = """
        {"status":0,"data":[{"area":"7","name":"code128_on","value":"1"}]}
        """
        XCTAssertEqual(BluetoothScannerService.tuningItemsPresent(in: none), [])
        XCTAssertNil(BluetoothScannerService.tuningItemsPresent(in: "not json"))
    }

    func testSimulatorTuningStateFollowsConnection() {
        let defaults = isolatedDefaults()
        let service = BluetoothScannerService(defaults: defaults)
        XCTAssertEqual(service.tuningState, .unknown)
        service.startDiscovery()
        service.connect(service.devices[0])
        XCTAssertEqual(service.tuningState, .matched(applied: false))
        service.disconnect()
        XCTAssertEqual(service.tuningState, .unknown)
    }

    func testDiagnosticLogKeepsRecentEventsAndOmitsPayloads() throws {
        let defaults = isolatedDefaults()
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        service.connect(service.devices[0])
        service.setExpectedCode(.qr)
        var delivered: [String] = []
        service.onCode = { delivered.append($0) }

        let payload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
        service.simulateScan(payload)
        XCTAssertEqual(delivered, [payload])

        let log = service.diagnosticLogText()
        XCTAssertTrue(log.contains("Scan callback accepted"))
        XCTAssertFalse(log.contains("BCJH5281GG"))
        XCTAssertFalse(log.contains(payload))
        XCTAssertTrue(log.contains("symbology mode: sessionCodes"))

        // 上限を超えた古いイベントは捨て、保持分だけを永続化する。
        for _ in 0..<(BluetoothScannerService.diagnosticEventLimit + 40) {
            service.stopDiscovery()
        }
        XCTAssertEqual(service.diagnosticEvents.count, BluetoothScannerService.diagnosticEventLimit)
        let data = try XCTUnwrap(defaults.data(forKey: BluetoothScannerService.diagnosticEventsKey))
        let persisted = try JSONDecoder().decode([BluetoothScannerDiagnosticEvent].self, from: data)
        XCTAssertEqual(persisted.count, BluetoothScannerService.diagnosticEventLimit)

        service.clearDiagnosticEvents()
        XCTAssertTrue(service.diagnosticEvents.isEmpty)
        XCTAssertNil(defaults.data(forKey: BluetoothScannerService.diagnosticEventsKey))
    }

    private func isolatedDefaults() -> UserDefaults {
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
