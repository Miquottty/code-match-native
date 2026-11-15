import XCTest

final class CodeMatchUITests: XCTestCase {
    private let uiLanguageResetArgument = "-resetLanguage"

    private func launchApp(
        _ arguments: [String] = [],
        resetLanguage: Bool = true
    ) -> XCUIApplication {
        let app = XCUIApplication()
        if resetLanguage {
            app.launchArguments.append(uiLanguageResetArgument)
        }
        app.launchArguments += arguments
        app.launch()
        return app
    }

    private func revealLanguageCard(in app: XCUIApplication) {
        let languageCard = app.descendants(matching: .any)["languageSelectionCard"]
        let englishOption = app.buttons["languageOption_en"]
        let maxScrollAttempts = 10
        for _ in 0..<maxScrollAttempts {
            if englishOption.exists && englishOption.isHittable {
                return
            }
            app.swipeUp()
        }
        XCTAssertTrue(languageCard.waitForExistence(timeout: 2))
        XCTAssertTrue(englishOption.waitForExistence(timeout: 2))
    }

    private func selectLanguage(
        in app: XCUIApplication,
        optionId: String,
        fallbackLabel: String,
        alternateFallbackLabel: String? = nil
    ) {
        if app.buttons[optionId].waitForExistence(timeout: 0.5) {
            app.buttons[optionId].tap()
            return
        }
        let picker = app.descendants(matching: .any)["languageSelectionPicker"]
        if picker.buttons[fallbackLabel].waitForExistence(timeout: 0.5) {
            picker.buttons[fallbackLabel].tap()
            return
        }
        if let alternate = alternateFallbackLabel, picker.buttons[alternate].waitForExistence(timeout: 0.5) {
            picker.buttons[alternate].tap()
            return
        }
        XCTFail("No language picker option found for id=\(optionId), fallback='\(fallbackLabel)', alternate='\(alternateFallbackLabel ?? "")'")
    }

    func testMockBluetoothScannerConnectsAndCompletesMatch() {
        let app = launchApp(["-resetHistory", "-resetScanLog", "-resetAutoAdvance", "-demoBluetoothConnected"])

        app.buttons["startSessionButton"].tap()
        let inputPicker = app.segmentedControls["scanInputSourcePicker"]
        XCTAssertTrue(inputPicker.waitForExistence(timeout: 5))
        XCTAssertTrue(inputPicker.buttons["Bluetooth"].isSelected)
        XCTAssertTrue(app.staticTexts["BCST-47 (Simulator)"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["1  四角いQRコード"].waitForExistence(timeout: 3))

        // Bluetoothからカメラへ切り替えた際にもプレビュー層が表示され、
        // 再びBluetoothへ戻して照合を継続できることを確認する。
        inputPicker.buttons["カメラ"].tap()
        XCTAssertTrue(inputPicker.buttons["カメラ"].isSelected)
        XCTAssertTrue(app.descendants(matching: .any)["cameraStage"].waitForExistence(timeout: 3))
        inputPicker.buttons["Bluetooth"].tap()
        XCTAssertTrue(inputPicker.buttons["Bluetooth"].isSelected)

        app.swipeUp()
        let demoToggle = app.staticTexts["カメラなしで判定をテスト"]
        XCTAssertTrue(demoToggle.waitForExistence(timeout: 3))
        demoToggle.tap()

        // Code 128を先に読んでもQR工程のままで、誤った値を照合へ進めない。
        app.buttons["demoBluetoothBarcodeButton"].tap()
        XCTAssertTrue(app.staticTexts["読み取り順序が違います。先に納品書兼現品票のQRコードを読み取ってください。 読み取った値は照合に使用していません。"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["scannerTitle"].label, "QRコードを読み取る")

        app.buttons["demoBluetoothQRButton"].tap()
        XCTAssertTrue(app.staticTexts["バーコードを読み取る"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["2  横長のCode 128"].waitForExistence(timeout: 3))

        let rereadQRButton = app.buttons["rereadQRButton"]
        let nextCodeButton = app.buttons["resetButton"]
        XCTAssertTrue(rereadQRButton.waitForExistence(timeout: 3))
        XCTAssertTrue(nextCodeButton.exists)
        XCTAssertLessThan(rereadQRButton.frame.maxY, nextCodeButton.frame.minY)
        rereadQRButton.tap()
        XCTAssertEqual(app.staticTexts["scannerTitle"].label, "QRコードを読み取る")

        app.buttons["demoBluetoothQRButton"].tap()
        XCTAssertTrue(app.staticTexts["バーコードを読み取る"].waitForExistence(timeout: 3))
        app.buttons["demoBluetoothBarcodeButton"].tap()

        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")

        // 照合ログは設定画面の最下部から共有でき、判定の分だけ件数が増えている。
        app.tabBars.buttons["設定"].tap()
        let shareScanLogButton = app.buttons["shareScanLogButton"]
        revealScanLogCard(in: app)
        XCTAssertTrue(shareScanLogButton.waitForExistence(timeout: 3))
        XCTAssertTrue(shareScanLogButton.isEnabled)
        XCTAssertTrue(app.buttons["clearScanLogButton"].exists)
        let scanLogCount = app.staticTexts["scanLogCount"]
        XCTAssertTrue(scanLogCount.waitForExistence(timeout: 3))
        XCTAssertNotEqual(scanLogCount.label, "記録: 0件")
    }

    private func revealScanLogCard(in app: XCUIApplication) {
        let shareScanLogButton = app.buttons["shareScanLogButton"]
        let maxScrollAttempts = 12
        for _ in 0..<maxScrollAttempts {
            if shareScanLogButton.exists && shareScanLogButton.isHittable {
                return
            }
            app.swipeUp()
        }
    }

    func testMockBluetoothScannerMoltenFlowLocksDestination() {
        let app = launchApp(["-resetHistory", "-resetScanLog", "-resetAutoAdvance", "-demoBluetoothConnected"])

        app.buttons["startSessionButton"].tap()
        let inputPicker = app.segmentedControls["scanInputSourcePicker"]
        XCTAssertTrue(inputPicker.waitForExistence(timeout: 5))
        XCTAssertTrue(inputPicker.buttons["Bluetooth"].isSelected)
        XCTAssertTrue(app.staticTexts["1  四角いQRコード"].waitForExistence(timeout: 3))

        app.swipeUp()
        let demoToggle = app.staticTexts["カメラなしで判定をテスト"]
        XCTAssertTrue(demoToggle.waitForExistence(timeout: 3))
        demoToggle.tap()

        let moltenQRButton = app.buttons["demoBluetoothMoltenQRButton"]
        XCTAssertTrue(moltenQRButton.waitForExistence(timeout: 3))
        moltenQRButton.tap()
        XCTAssertTrue(app.staticTexts["2  横長のCode 128"].waitForExistence(timeout: 3))

        app.buttons["demoBluetoothMoltenBarcodeButton"].tap()
        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")
        XCTAssertEqual(app.staticTexts["sessionDestination"].label, "モルテン")

        // 仕向地はセッション終了まで固定され、別の仕向地のQRは照合へ進めない。
        app.buttons["resetButton"].tap()
        let scannerTitle = app.staticTexts["scannerTitle"]
        let backToQRStep = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "QRコードを読み取る"),
            object: scannerTitle
        )
        XCTAssertEqual(XCTWaiter.wait(for: [backToQRStep], timeout: 5), .completed)

        let sawaiQRButton = app.buttons["demoBluetoothQRButton"]
        var scrollAttempts = 0
        while !(sawaiQRButton.exists && sawaiQRButton.isHittable), scrollAttempts < 5 {
            app.swipeUp()
            scrollAttempts += 1
        }
        XCTAssertTrue(sawaiQRButton.waitForExistence(timeout: 3))
        sawaiQRButton.tap()

        XCTAssertTrue(
            app.staticTexts[
                "このセッションは仕向地「モルテン」で照合中です。別の仕向地のQRコードは照合できません。仕向地を変えるにはセッションを終了してください。 読み取った値は照合に使用していません。"
            ].waitForExistence(timeout: 3)
        )
        XCTAssertEqual(scannerTitle.label, "QRコードを読み取る")
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")

        // セッションを終了すると、履歴にも仕向地とモルテンの納品書情報が残る。
        app.buttons["endSessionButton"].tap()
        app.alerts.buttons["終了する"].tap()
        XCTAssertTrue(app.buttons["startSessionButton"].waitForExistence(timeout: 5))

        app.tabBars.buttons["履歴"].tap()
        XCTAssertTrue(app.navigationBars["照合履歴"].waitForExistence(timeout: 3))
        let sessionRow = app.buttons["historySessionRow"]
        XCTAssertTrue(sessionRow.waitForExistence(timeout: 3))
        sessionRow.tap()

        // LabeledContentは見出しと値を1つの読み上げ要素にまとめる。
        let sessionDestination = app.staticTexts["historySessionDestination"]
        XCTAssertTrue(sessionDestination.waitForExistence(timeout: 3))
        XCTAssertEqual(sessionDestination.label, "仕向地、モルテン")

        let matchEntryRow = app.buttons["matchEntryRow"]
        XCTAssertTrue(matchEntryRow.waitForExistence(timeout: 3))
        matchEntryRow.tap()

        let boxEntryRow = app.buttons["boxEntryRow"]
        XCTAssertTrue(boxEntryRow.waitForExistence(timeout: 3))
        boxEntryRow.tap()

        // モルテンの納品書情報（QR解析）に納品番号が並ぶ。
        XCTAssertTrue(app.staticTexts["納品番号"].waitForExistence(timeout: 3))
        let deliveryNumber = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", "UAG5560", "UAG5560")
        ).firstMatch
        XCTAssertTrue(deliveryNumber.waitForExistence(timeout: 3))
    }

    func testMockBluetoothScannerDensoFlowLocksDestination() {
        let app = launchApp(["-resetHistory", "-resetScanLog", "-resetAutoAdvance", "-demoBluetoothConnected"])

        app.buttons["startSessionButton"].tap()
        let inputPicker = app.segmentedControls["scanInputSourcePicker"]
        XCTAssertTrue(inputPicker.waitForExistence(timeout: 5))
        XCTAssertTrue(inputPicker.buttons["Bluetooth"].isSelected)
        XCTAssertTrue(app.staticTexts["1  四角いQRコード"].waitForExistence(timeout: 3))

        app.swipeUp()
        let demoToggle = app.staticTexts["カメラなしで判定をテスト"]
        XCTAssertTrue(demoToggle.waitForExistence(timeout: 3))
        demoToggle.tap()

        let densoQRButton = app.buttons["demoBluetoothDensoQRButton"]
        var qrScrollAttempts = 0
        while !(densoQRButton.exists && densoQRButton.isHittable), qrScrollAttempts < 5 {
            app.swipeUp()
            qrScrollAttempts += 1
        }
        XCTAssertTrue(densoQRButton.waitForExistence(timeout: 3))
        densoQRButton.tap()
        XCTAssertTrue(app.staticTexts["2  横長のCode 128"].waitForExistence(timeout: 3))

        app.buttons["demoBluetoothDensoBarcodeButton"].tap()
        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")
        XCTAssertEqual(app.staticTexts["sessionDestination"].label, "デンソー")

        // 仕向地はセッション終了まで固定され、別の仕向地のQRは照合へ進めない。
        app.buttons["resetButton"].tap()
        let scannerTitle = app.staticTexts["scannerTitle"]
        let backToQRStep = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "QRコードを読み取る"),
            object: scannerTitle
        )
        XCTAssertEqual(XCTWaiter.wait(for: [backToQRStep], timeout: 5), .completed)

        let sawaiQRButton = app.buttons["demoBluetoothQRButton"]
        var scrollAttempts = 0
        while !(sawaiQRButton.exists && sawaiQRButton.isHittable), scrollAttempts < 5 {
            app.swipeUp()
            scrollAttempts += 1
        }
        XCTAssertTrue(sawaiQRButton.waitForExistence(timeout: 3))
        sawaiQRButton.tap()

        XCTAssertTrue(
            app.staticTexts[
                "このセッションは仕向地「デンソー」で照合中です。別の仕向地のQRコードは照合できません。仕向地を変えるにはセッションを終了してください。 読み取った値は照合に使用していません。"
            ].waitForExistence(timeout: 3)
        )
        XCTAssertEqual(scannerTitle.label, "QRコードを読み取る")
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")

        // セッションを終了すると、履歴にも仕向地とデンソーのかんばん項目が残る。
        app.buttons["endSessionButton"].tap()
        app.alerts.buttons["終了する"].tap()
        XCTAssertTrue(app.buttons["startSessionButton"].waitForExistence(timeout: 5))

        app.tabBars.buttons["履歴"].tap()
        XCTAssertTrue(app.navigationBars["照合履歴"].waitForExistence(timeout: 3))
        let sessionRow = app.buttons["historySessionRow"]
        XCTAssertTrue(sessionRow.waitForExistence(timeout: 3))
        sessionRow.tap()

        // LabeledContentは見出しと値を1つの読み上げ要素にまとめる。
        let sessionDestination = app.staticTexts["historySessionDestination"]
        XCTAssertTrue(sessionDestination.waitForExistence(timeout: 3))
        XCTAssertEqual(sessionDestination.label, "仕向地、デンソー")

        let matchEntryRow = app.buttons["matchEntryRow"]
        XCTAssertTrue(matchEntryRow.waitForExistence(timeout: 3))
        matchEntryRow.tap()

        let boxEntryRow = app.buttons["boxEntryRow"]
        XCTAssertTrue(boxEntryRow.waitForExistence(timeout: 3))
        boxEntryRow.tap()

        // デンソーのかんばん項目（QR解析）にかんばん連番が並ぶ。
        let kanbanSerial = app.staticTexts["かんばん連番"]
        var detailScrollAttempts = 0
        while !kanbanSerial.exists, detailScrollAttempts < 5 {
            app.swipeUp()
            detailScrollAttempts += 1
        }
        XCTAssertTrue(kanbanSerial.waitForExistence(timeout: 3))
        // 澤井製作所として解析した欄は出さない。
        XCTAssertFalse(app.staticTexts["カード番号"].exists)
    }

    func testSettingsDiscoversAndConnectsMockScanner() {
        let app = launchApp(["-resetHistory", "-resetScanLog", "-resetAutoAdvance", "-resetBluetoothScanner"])

        app.tabBars.buttons["設定"].tap()
        let setupGuideButton = app.buttons["scannerSetupGuideButton"]
        XCTAssertTrue(setupGuideButton.waitForExistence(timeout: 5))
        setupGuideButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["scannerSetupGuide"].waitForExistence(timeout: 3))

        app.buttons["scannerSetupNextButton"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["scannerSetupBarcode_enterSetup"].waitForExistence(timeout: 3))
        let enlargeButton = app.buttons["scannerSetupEnlarge_enterSetup"]
        XCTAssertTrue(enlargeButton.waitForExistence(timeout: 3))
        enlargeButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["scannerSetupFullscreenBarcode_enterSetup"].waitForExistence(timeout: 3))
        app.buttons["scannerSetupFullscreenCloseButton"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["scannerSetupBarcode_enterSetup"].waitForExistence(timeout: 3))
        // 工場出荷時設定に戻す → 保存 → 設定モードへ入り直す → GATT → 保存、の6画面を順に進む。
        for stepID in ["factoryReset", "saveFactoryReset", "enterSetupAgain", "gattMode", "saveAndExit"] {
            app.buttons["scannerSetupNextButton"].tap()
            XCTAssertTrue(
                app.descendants(matching: .any)["scannerSetupBarcode_\(stepID)"].waitForExistence(timeout: 3),
                "setup guide page \(stepID)"
            )
        }
        app.buttons["scannerSetupNextButton"].tap()
        let searchButton = app.buttons["scannerSetupSearchButton"]
        XCTAssertTrue(searchButton.waitForExistence(timeout: 3))
        searchButton.tap()

        let device = app.buttons["bluetoothScannerDevice_SIMULATOR-BCST-47"]
        XCTAssertTrue(device.waitForExistence(timeout: 3))
        device.tap()

        XCTAssertTrue(app.staticTexts["bluetoothScannerStatus"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["bluetoothScannerStatus"].label, "BCST-47 (Simulator) 接続済み")
        let disconnectButton = app.buttons["disconnectBluetoothScannerButton"]
        XCTAssertTrue(disconnectButton.exists)
        disconnectButton.tap()

        let reconnectButton = app.buttons["knownScannerReconnectButton"]
        XCTAssertTrue(reconnectButton.waitForExistence(timeout: 3))
        app.buttons["searchBluetoothScannerButton"].tap()
        XCTAssertTrue(reconnectButton.waitForExistence(timeout: 3))
        reconnectButton.tap()

        XCTAssertEqual(app.staticTexts["bluetoothScannerStatus"].label, "BCST-47 (Simulator) 接続済み")
    }

    /// スキャナーの主要フローを1回のアプリ起動でまとめて検証する。
    /// 起動が最も時間を要するため、一致→重複→リセット→不一致を連続で確認する。
    func testScannerFlowMatchDuplicateResetAndMismatch() {
        let app = launchApp(["-resetHistory", "-resetScanLog", "-resetAutoAdvance", "-demoMatch"])

        // 起動引数による一致状態と件数
        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")

        // 同じ成功済みラベルを再照合するとエラーになり、件数は増えない
        app.swipeUp()
        let demoToggle = app.staticTexts["カメラなしで判定をテスト"]
        XCTAssertTrue(demoToggle.waitForExistence(timeout: 3))
        demoToggle.tap()
        let matchButton = app.buttons["demoMatchButton"]
        XCTAssertTrue(matchButton.waitForExistence(timeout: 3))
        matchButton.tap()
        XCTAssertTrue(app.staticTexts["すでに照合済みです"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["このコードは照合件数に加えていません。次のコードを読み取ってください。"].exists)
        let countUnchanged = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "1件照合済み"),
            object: app.staticTexts["sessionMatchCount"]
        )
        XCTAssertEqual(XCTWaiter.wait(for: [countUnchanged], timeout: 5), .completed)

        // リセットでQR読み取りステップへ戻る
        let resetButton = app.buttons["resetButton"]
        XCTAssertTrue(resetButton.waitForExistence(timeout: 5))
        resetButton.tap()
        let scannerTitle = app.staticTexts["scannerTitle"]
        XCTAssertTrue(scannerTitle.waitForExistence(timeout: 5))
        let resetCompleted = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "QRコードを読み取る"),
            object: scannerTitle
        )
        XCTAssertEqual(XCTWaiter.wait(for: [resetCompleted], timeout: 5), .completed)

        // 不一致は両方の品番を表示し、件数に加算されない
        app.swipeUp()
        let mismatchButton = app.buttons["demoMismatchButton"]
        XCTAssertTrue(mismatchButton.waitForExistence(timeout: 3))
        mismatchButton.tap()
        XCTAssertTrue(app.staticTexts["一致しません"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["BCJH-52-81GG"].exists)
        XCTAssertTrue(app.staticTexts["BCJH-55-81GG"].exists)
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")

        // セッションを終了すると照合済みのセッションが履歴に掲載される
        app.buttons["endSessionButton"].tap()
        app.alerts.buttons["終了する"].tap()
        XCTAssertTrue(app.buttons["startSessionButton"].waitForExistence(timeout: 5))

        app.tabBars.buttons["履歴"].tap()
        XCTAssertTrue(app.navigationBars["照合履歴"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["historySessionRow"].waitForExistence(timeout: 3))
    }

    func testSettingsSoundSelectionAndLanguageSwitchPersists() {
        var app = launchApp(["-resetHistory", "-resetScanLog", "-resetAutoAdvance"])

        app.tabBars.buttons["設定"].tap()
        let autoAdvanceToggle = app.switches["autoAdvanceSettingsToggle"]
        XCTAssertTrue(autoAdvanceToggle.waitForExistence(timeout: 5))
        XCTAssertEqual(autoAdvanceToggle.value as? String, "0")

        let autoAdvanceDelayPicker = app.segmentedControls["autoAdvanceDelayPicker"]
        XCTAssertTrue(autoAdvanceDelayPicker.waitForExistence(timeout: 3))
        XCTAssertTrue(autoAdvanceDelayPicker.buttons["3秒"].isSelected)
        autoAdvanceDelayPicker.buttons["1秒"].tap()
        XCTAssertTrue(autoAdvanceDelayPicker.buttons["1秒"].isSelected)
        autoAdvanceDelayPicker.buttons["5秒"].tap()
        XCTAssertTrue(autoAdvanceDelayPicker.buttons["5秒"].isSelected)
        autoAdvanceDelayPicker.buttons["3秒"].tap()

        let volumeSlider = app.sliders["volumeSlider"]
        XCTAssertTrue(volumeSlider.waitForExistence(timeout: 5))

        // 成功音をチャイムへ変更すると選択状態が反映される
        let chime = app.buttons["successSound_chime"]
        XCTAssertTrue(chime.waitForExistence(timeout: 3))
        chime.tap()
        XCTAssertTrue(app.buttons["チャイム（3音）、選択中"].waitForExistence(timeout: 3))

        // 失敗音をブザーへ変更
        app.buttons["failureSound_buzzer"].tap()
        XCTAssertTrue(app.buttons["ブブー（ブザー）、選択中"].waitForExistence(timeout: 3))

        // 音量スライダーを操作できる
        volumeSlider.adjust(toNormalizedSliderPosition: 0.6)

        // 既定値へ戻す
        app.buttons["successSound_posBeep"].tap()
        app.buttons["failureSound_alarm"].tap()
        XCTAssertTrue(app.buttons["ピッ（POSレジ風・標準）、選択中"].waitForExistence(timeout: 3))

        // 言語を英語へ切り替えると即座に反映され、再起動後も保持される
        revealLanguageCard(in: app)
        XCTAssertTrue(app.descendants(matching: .any)["languageSelectionCard"].waitForExistence(timeout: 5))

        selectLanguage(in: app, optionId: "languageOption_en", fallbackLabel: "English")
        XCTAssertTrue(app.staticTexts["Language"].waitForExistence(timeout: 5))

        app.terminate()
        app = launchApp(["-resetHistory", "-resetScanLog", "-resetAutoAdvance"], resetLanguage: false)

        XCTAssertTrue(app.tabBars.buttons["Settings"].waitForExistence(timeout: 5))
        app.tabBars.buttons["Settings"].tap()
        revealLanguageCard(in: app)
        XCTAssertTrue(app.descendants(matching: .any)["languageSelectionCard"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Language"].waitForExistence(timeout: 5))

        selectLanguage(
            in: app,
            optionId: "languageOption_ja",
            fallbackLabel: "日本語",
            alternateFallbackLabel: "Japanese"
        )
        XCTAssertTrue(app.staticTexts["言語"].waitForExistence(timeout: 5))
    }

    func testSessionAutoAdvanceShowsCountdownAndStartsNextMatch() {
        let app = launchApp(["-resetHistory", "-resetScanLog", "-resetAutoAdvance"])

        let startButton = app.buttons["startSessionButton"]
        XCTAssertTrue(startButton.waitForExistence(timeout: 5))
        // 緑のボタン中央だけでなく、左端寄りでも操作できることを確認する。
        startButton.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.5)).tap()

        let autoAdvanceToggle = app.switches["sessionAutoAdvanceToggle"]
        XCTAssertTrue(autoAdvanceToggle.waitForExistence(timeout: 3))
        XCTAssertEqual(autoAdvanceToggle.value as? String, "0")
        autoAdvanceToggle.tap()
        XCTAssertEqual(autoAdvanceToggle.value as? String, "1")
        XCTAssertTrue(app.buttons["sessionAutoAdvanceDelayMenu"].exists)

        for _ in 0..<3 {
            app.swipeUp()
        }
        let demoToggle = app.staticTexts["カメラなしで判定をテスト"]
        XCTAssertTrue(demoToggle.waitForExistence(timeout: 3))
        demoToggle.tap()
        app.buttons["demoMatchButton"].tap()

        XCTAssertTrue(app.descendants(matching: .any)["autoAdvanceCountdown"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.staticTexts["自動で次の照合へ進みます"].exists)

        let scannerTitle = app.staticTexts["scannerTitle"]
        let advancedToNextMatch = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "QRコードを読み取る"),
            object: scannerTitle
        )
        XCTAssertEqual(XCTWaiter.wait(for: [advancedToNextMatch], timeout: 5), .completed)
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")

        autoAdvanceToggle.tap()
        XCTAssertEqual(autoAdvanceToggle.value as? String, "0")
    }

}
