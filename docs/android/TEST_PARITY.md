# iOS↔Android テスト意図パリティ表

監査日: 2026-09-04。対象は [`ios/CodeMatchTests/`](../../ios/CodeMatchTests) 配下の XCTest（監査時点で71本。2026-09-06にテストクラスごとのファイルへ分割し、以下の行番号はその分割後のもの）と [`CodeMatchUITests.swift`](../../ios/CodeMatchUITests/CodeMatchUITests.swift) の UI テスト5本。件数やテスト名の一致ではなく、各テストが保証する意図を Android 側の証拠へ対応付ける。Android の `D` は同じ契約を同等の層で検査、`P` は近接する状態・部品の検査（元テスト全体の代替ではない）、`—` は Android に適用されるが証拠がない行、`N/A` は Android の現行共通仕様に含まれず対応不要と根拠リンクで確認した行を表す。

行番号は記載時点のものであり、その後の変更（#82 / #83 / #93 / #95 / #97 / #113 / #115）で前後している。行の同定はテスト名で行うこと。2026-09-07時点のSwiftソースは単体が約155本・UI 7本で、監査後に追加された仕向地関連のテストは末尾の「仕向地モルテンの追加テスト（2026-09-07）」と「仕向地デンソーの追加テスト（2026-09-07）」で対応付ける。

共通照合データは [`matching-cases.json`](../../shared/test-fixtures/matching-cases.json)（schemaVersion 2、63ケース、各ケースにQRの`destination`）であり、Swift はファイルを直接読み、Kotlin は test runtime classpath から読む。`src/test` は JVM テスト、`src/androidTest` は端末・エミュレーター依存の証拠である。D/P は「実カメラ読取」や「対象 BLE scanner 通信」の成功を意味しない。

### Android 証拠ファイル（略記の正本）

表中のテストクラス名は、次の相対パスのファイルを指す。

| テストクラス | 正確なファイル |
|---|---|
| `CameraUiContractTest` | `android/feature/scan/src/test/kotlin/jp/rimtty/codematch/feature/scan/CameraUiContractTest.kt` |
| `CameraStageTest` | `android/feature/scan/src/androidTest/kotlin/jp/rimtty/codematch/feature/scan/CameraStageTest.kt` |
| `CameraScannerAsyncTest` | `android/scanner/camera/src/test/kotlin/jp/rimtty/codematch/scanner/camera/CameraScannerAsyncTest.kt` |
| `BundledMlKitImageDecodeTest` | `android/scanner/camera/src/androidTest/kotlin/jp/rimtty/codematch/scanner/camera/BundledMlKitImageDecodeTest.kt` |
| `CameraStopBoundaryTest` | `android/app/src/test/java/jp/rimtty/codematch/scan/CameraStopBoundaryTest.kt` |
| `CameraPermissionStateTest` | `android/app/src/test/java/jp/rimtty/codematch/scan/CameraPermissionStateTest.kt` |
| `CameraModelsTest` | `android/scanner/camera/src/test/kotlin/jp/rimtty/codematch/scanner/camera/CameraModelsTest.kt` |
| `CodeMatcherTest` | `android/core/matching/src/test/kotlin/jp/rimtty/codematch/core/matching/CodeMatcherTest.kt` |
| `SettingsModelsTest` | `android/core/model/src/test/kotlin/jp/rimtty/codematch/core/model/SettingsModelsTest.kt` |
| `SettingsRepositoryTest` | `android/core/data/src/androidTest/kotlin/jp/rimtty/codematch/core/data/SettingsRepositoryTest.kt` |
| `HistoryUiTextTest` | `android/feature/history/src/test/kotlin/jp/rimtty/codematch/feature/history/HistoryUiTextTest.kt` |
| `HistoryExportTextTest` | `android/core/export/src/test/kotlin/jp/rimtty/codematch/core/export/HistoryExportTextTest.kt` |
| `HistoryPdfContentTest` | `android/core/export/src/test/kotlin/jp/rimtty/codematch/core/export/HistoryPdfContentTest.kt` |
| `InspectionReportContentTest` | `android/core/export/src/test/kotlin/jp/rimtty/codematch/core/export/InspectionReportContentTest.kt` |
| `InspectionPdfContentTest` | `android/core/export/src/test/kotlin/jp/rimtty/codematch/core/export/InspectionPdfContentTest.kt` |
| `HistoryDeliveryGroupsTest` | `android/core/export/src/test/kotlin/jp/rimtty/codematch/core/export/HistoryDeliveryGroupsTest.kt` |
| `HistoryJsonExporterTest` | `android/core/export/src/test/kotlin/jp/rimtty/codematch/core/export/HistoryJsonExporterTest.kt` |
| `HistoryPdfBridgeTest` | `android/app/src/test/java/jp/rimtty/codematch/history/HistoryPdfBridgeTest.kt` |
| `HistoryPdfExporterInstrumentationTest` | `android/core/export/src/androidTest/kotlin/jp/rimtty/codematch/core/export/HistoryPdfExporterInstrumentationTest.kt` |
| `ScanModelsTest` | `android/core/model/src/test/kotlin/jp/rimtty/codematch/core/model/ScanModelsTest.kt` |
| `Code128BarcodeTest` | `android/feature/settings/src/test/kotlin/jp/rimtty/codematch/feature/settings/Code128BarcodeTest.kt` |
| `SetupBarcodeDecodeTest` | `android/feature/settings/src/androidTest/kotlin/jp/rimtty/codematch/feature/settings/SetupBarcodeDecodeTest.kt` |
| `BleSymbologyTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologyTest.kt` |
| `BleSymbologySessionTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySessionTest.kt` |
| `FakeExternalScannerTest` | `android/scanner/fake/src/test/kotlin/jp/rimtty/codematch/scanner/fake/FakeExternalScannerTest.kt` |
| `BleSymbologySnapshotSerializerTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotSerializerTest.kt` |
| `BlePayloadTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BlePayloadTest.kt` |
| `BleConnectionCoordinatorTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleConnectionCoordinatorTest.kt` |
| `BleSymbologySnapshotStoreTest` | `android/scanner/ble/src/androidTest/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotStoreTest.kt` |
| `BleKnownDeviceRecoveryTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceRecoveryTest.kt` |
| `BleKnownDeviceStoreTest` | `android/scanner/ble/src/androidTest/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStoreTest.kt` |
| `ScanReducerTest` | `android/feature/scan/src/test/kotlin/jp/rimtty/codematch/feature/scan/ScanReducerTest.kt` |
| `ScanSessionCoordinatorTest` | `android/feature/scan/src/test/kotlin/jp/rimtty/codematch/feature/scan/ScanSessionCoordinatorTest.kt` |
| `ScanCheckpointMappingTest` | `android/feature/scan/src/test/kotlin/jp/rimtty/codematch/feature/scan/ScanCheckpointMappingTest.kt` |
| `BleScannerSessionCoordinatorTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleScannerSessionCoordinatorTest.kt` |
| `HistoryRepositoryTest` | `android/core/data/src/androidTest/kotlin/jp/rimtty/codematch/core/data/HistoryRepositoryTest.kt` |
| `CodeMatchDatabaseMigrationTest` | `android/core/data/src/androidTest/kotlin/jp/rimtty/codematch/core/data/CodeMatchDatabaseMigrationTest.kt` |
| `HistoryModelsTest` | `android/core/model/src/test/kotlin/jp/rimtty/codematch/core/model/HistoryModelsTest.kt` |
| `ScanScreenTest` | `android/feature/scan/src/androidTest/kotlin/jp/rimtty/codematch/feature/scan/ScanScreenTest.kt` |
| `SettingsScreenTest` | `android/feature/settings/src/androidTest/kotlin/jp/rimtty/codematch/feature/settings/SettingsScreenTest.kt` |
| `FeedbackContractTest` | `android/app/src/test/java/jp/rimtty/codematch/feedback/FeedbackContractTest.kt` |
| `HistoryScreenTest` | `android/feature/history/src/androidTest/kotlin/jp/rimtty/codematch/feature/history/HistoryScreenTest.kt` |
| `AppFlowInstrumentationTest` | `android/app/src/androidTest/java/jp/rimtty/codematch/AppFlowInstrumentationTest.kt` |
| `ScanViewModelCheckpointInstrumentationTest` | `android/app/src/androidTest/java/jp/rimtty/codematch/scan/ScanViewModelCheckpointInstrumentationTest.kt` |
| `NavigationTest` | `android/app/src/androidTest/java/jp/rimtty/codematch/NavigationTest.kt` |


## Swift 単体テスト（71本）

| # | Swift の意図（テスト） | Android の証拠 | 判定 |
|---:|---|---|:---:|
| 1 | Preview 層が capture session を付け替え・detach できる（`CameraPreviewTests.swift:8`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::a completed provider binds once when viewport is unchanged` + `::pending provider future restarts with the latest QR to Code 128 format` + `::running binding rebinds when the preview viewport rotates`。AndroidではCameraX use-caseのunbind/rebindで同じ資源境界を固定する | D（CameraX境界） |
| 2 | Preview dismantle で session を外し復旧を無効化する（`CameraPreviewTests.swift:23`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::preview dismantle unbind invalidates a pending provider callback` + `::preview dismantle invalidates an in-flight ML Kit failure callback`。dismantle後のprovider/解析callbackを世代で破棄する | D |
| 3 | inactive 化で再利用 session を detach/rebind する（`CameraPreviewTests.swift:35`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::inactive stop then start rebinds the same host after teardown` + `::clearing the format stops then rebinds on the same attached host` | D |
| 4 | queued teardown 完了まで scanner を保持する（`CameraPreviewTests.swift:50`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::unbind completion waits for an in-flight ML Kit task to drain` + `CameraStopBoundaryTest.kt::sessionEndWaitsForDelayedHostStopCompletion` は処理中frame完了前に論理sessionを終了しないことを検査する | D |
| 5 | metadata region を正規化座標へ clamp する（`CameraPreviewTests.swift:70`、`CameraPreviewTests`） | iOSの [`CameraScanner.swift#L204`](../../ios/CodeMatch/Services/CameraScanner.swift#L204) が正規化矩形をclampするのに対し、Androidは [`CameraModelsTest.kt#L60`](../../android/scanner/camera/src/test/kotlin/jp/rimtty/codematch/scanner/camera/CameraModelsTest.kt#L60) `roi rejects coordinates outside the preview instead of clamping them` + [`#L72`](../../android/scanner/camera/src/test/kotlin/jp/rimtty/codematch/scanner/camera/CameraModelsTest.kt#L72) `roi accepts coordinates exactly on the normalized preview boundary` で範囲外を拒否する安全側policyを検査する。iOSのclamp動作そのものは移植していない | P（policy差） |
| 6 | screen capture 中でも multitasking camera 対応時は開始し、非対応時だけ block（`CameraPreviewTests.swift:85`、`CameraPreviewTests`） | iOSの [`CameraScanner.swift#L209`](../../ios/CodeMatch/Services/CameraScanner.swift#L209) / [`#L217`](../../ios/CodeMatch/Services/CameraScanner.swift#L217) と [`CodeMatcherTests.swift#L85`](../../ios/CodeMatchTests/CodeMatcherTests.swift#L85) がscene captureとmultitasking cameraの組合せを固定する。一方、共通 [`PRODUCT_SPEC.md#L3`](../PRODUCT_SPEC.md#L3) にこの要件はなく、Androidの現行camera状態は [`CameraModels.kt#L11`](../../android/scanner/camera/src/main/kotlin/jp/rimtty/codematch/scanner/camera/CameraModels.kt#L11)、Manifestは [`AndroidManifest.xml#L5`](../../android/app/src/main/AndroidManifest.xml#L5) のCAMERA/任意cameraだけである | N/A（iOS固有の防御策。Android要件が追加される場合は別仕様） |
| 7 | Code 128 から品番を抽出する（`CodeMatcherTests.swift:23`） | `CodeMatcherTest.kt::partNumberFromBarcodeUsesTextBeforeFirstAt` | D |
| 8 | 仕向地ごとの固定位置から品番を抽出し、どちらの様式でもない QR からは抽出しない（`CodeMatcherTests.swift::testPartNumberFromQR` + `::testPartNumberFromQRIsDestinationAware`） | `CodeMatcherTest.kt::partNumberFromQrReadsTheStandardCardAndItemPositions` + `::partNumberFromQrIsDestinationAware` | D |
| 9 | 実データの QR/Code 128 が一致する（`CodeMatcherTests.swift:42`） | `CodeMatcherTest.kt::compareMatchesRealPairAndIgnoresManagementCode` | D |
| 10 | shared fixture の全ケースを照合する（`CodeMatcherTests.swift:46`） | `CodeMatcherTest.kt::sharedMatchingFixturesHaveTheSameResultsAsSwift`（classpath fixture） | D |
| 11 | 別品番は不一致になる（`CodeMatcherTests.swift:83`） | `CodeMatcherTest.kt::compareMatchesRealPairAndIgnoresManagementCode` | D |
| 12 | `@` 以降の管理コード差は一致へ影響しない（`CodeMatcherTests.swift:91`） | `CodeMatcherTest.kt::compareMatchesRealPairAndIgnoresManagementCode` | D |
| 13 | 非標準 QR は品番を含んでいても一致しない（containment fallback は両プラットフォームで削除済み。`CodeMatcherTests.swift::testNonStandardQRIsMismatch`） | `CodeMatcherTest.kt::nonStandardQrNeverMatchesEvenWhenItContainsThePartNumber` | D |
| 14 | 空 payload は不一致になる（`CodeMatcherTests.swift:110`） | `CodeMatcherTest.kt::emptyOrUnparseableValuesMismatch` | D |
| 15 | 品番表示形式を整形する（`CodeMatcherTests.swift:115`） | `CodeMatcherTest.kt::formatPartNumberUsesTheFourTwoFourDisplayShape` | D |
| 16 | Kanban QR の全フィールドを解析する（`CodeMatcherTests.swift:120`） | `CodeMatcherTest.kt::kanbanRecordParsesAllFields` | D |
| 17 | 枝番空白の Kanban QR と数量を扱う（`CodeMatcherTests.swift:132`） | `CodeMatcherTest.kt::kanbanRecordHandlesBlankSuffixAndParsesQuantities` | D |
| 18 | 非標準/短い QR を拒否する（`CodeMatcherTests.swift:144`） | `CodeMatcherTest.kt::kanbanRecordRejectsNonStandardPayloadsAndChecksStrictScanLength` | D |
| 19 | Bluetooth 入力の QR/Code 128 逆順・形式を拒否する（`CodeMatcherTests.swift:149`） | `CodeMatcherTest.kt::kanbanRecordRejectsNonStandardPayloadsAndChecksStrictScanLength` + `::tagRecordValidationRejectsReverseOrderAndWrongShape` | D |
| 20 | Code 128 の品番と管理コードを分離して解析する（`CodeMatcherTests.swift:160`） | `CodeMatcherTest.kt::tagRecordParsingKeepsPartAndManagementCodeSeparate` | D |
| 21 | 保存値なしの言語は日本語へ fallback（`AppLanguageTests.swift:24`） | `SettingsModelsTest.kt::unknownPreferenceValuesUseSafeDefaults`（`fromCode(null)`）+ `SettingsRepositoryTest.kt::defaultsAndUpdatesArePersistedAsOneSettingsFlow` | D（enum/DataStore 契約） |
| 22 | 不正な保存言語は日本語へ fallback（`AppLanguageTests.swift:29`） | `SettingsModelsTest.kt::unknownPreferenceValuesUseSafeDefaults`（未知値） | D |
| 23 | 現在言語に応じて localization を切り替える（`AppLanguageTests.swift:34`） | `ScanScreenTest.kt::languageOverrideRendersEnglishAndRecomposesInJapanese` + `SettingsScreenTest.kt::languageStateRedrawsSettingsTextWithoutWaitingForActivityRecreation` + `HistoryScreenTest.kt::englishBoxCountsUseSingularAndPluralAndRedrawInJapanese` | D |
| 24 | 英語の単数/複数形を自然に表示する（`AppLanguageTests.swift:42`） | `HistoryExportTextTest.kt::boxCountUsesNaturalEnglishSingularAndPlural` + `HistoryPdfContentTest.kt::englishContentInflectsBoxUnitForOneAndMany` + `HistoryScreenTest.kt::englishBoxCountsUseSingularAndPluralAndRedrawInJapanese` | D |
| 25 | auto-advance の既定値 OFF/3秒、保存値 5秒、選択肢 1/3/5秒（`AutoAdvanceSettingsTests.swift:7`） | `ScanModelsTest.kt::autoAdvanceDelaysMatchTheSwiftContract` + `SettingsModelsTest.kt::defaultsMatchProductContract` + `SettingsRepositoryTest.kt::defaultsAndUpdatesArePersistedAsOneSettingsFlow` | D |
| 26 | Inateck setup Code 128 の順序・文字列（`BluetoothScannerServiceTests.swift:25`） | `Code128BarcodeTest.kt::setupCodesKeepTheScannerOrderAndExactAsciiMessages` + `SetupBarcodeDecodeTest.kt::allSetupBarcodesDecodeToTheirExactCommands`（Android同梱ML Kitで3コードをexact decode） | D |
| 27 | symbology mode の active restriction 文言（`BluetoothScannerServiceTests.swift:32`） | `ScanScreenTest.kt::bluetoothConfigurationStatusReturnsToSessionRestrictionAfterReady` はReady後のQR・Code 128 session restriction文言を日英resource経由で検査する。iOS固有のlegacy qrOnly/code128Only mode文言は対象外 | P（session mode） |
| 28 | 論理工程変更で ready session を再設定しない（`BluetoothScannerServiceTests.swift:54`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical` + `FakeExternalScannerTest.kt::expectedFormatChangesAreLogicalAndDoNotReconfigureEveryStep` | D |
| 29 | scanner が報告した全 barcode type を command に含め QR/Code 128 だけ有効化（`BluetoothScannerServiceTests.swift:79`） | `BleSymbologyTest.kt::sessionModeDisablesEveryReportedTypeExceptQrAndCode128` + `::documentedFlagValueCodecRestrictsAndRestoresTheCompleteReportedInventory`。iOS area/name形式に加え、公式SDK-level flag形式でも2008/2022だけを有効化する | D |
| 30 | 元の barcode 設定を値変更なしで round-trip 復元（`BluetoothScannerServiceTests.swift:152`） | `BleSymbologyTest.kt::restoreUsesOriginalValuesAndDeviceReportedAreas` + `::documentedFlagValueCodecRestrictsAndRestoresTheCompleteReportedInventory` + `BleSymbologySnapshotSerializerTest.kt::completeInventoryRoundTripsWithoutDroppingMetadata`。公式形式はflag/valueだけを数値で戻し、areaとの相互変換を仮定しない | D |
| 31 | transport terminator は末尾だけ除去（`BluetoothScannerServiceTests.swift:196`） | `BlePayloadTest.kt::normalizerRemovesOnlyTrailingTransportTerminators` | D |
| 32 | pinned iOS SDK JSON envelope を scan payload へ unwrap（`BluetoothScannerServiceTests.swift:204`） | `BlePayloadTest.kt::decoderAcceptsDirectTextAndPinnedSdkEnvelope` | D |
| 33 | scanner-lib notification JSON を unwrap（`BluetoothScannerServiceTests.swift:213`） | `BlePayloadTest.kt::decoderAcceptsNotificationBytesAndRejectsNonScanNotifications` | D |
| 34 | direct text を維持し、非 scan notification/不正 JSON を reject（`BluetoothScannerServiceTests.swift:226`） | `BlePayloadTest.kt::malformedStructuralJsonIsRejectedWhileValidObjectTextRemainsCompatible` + decoder テスト | D |
| 35 | simulator scanner の discovery/connect と preferred reconnect（`BluetoothScannerServiceTests.swift:243`） | `FakeExternalScannerTest.kt::discoveryAndConnectionAreSynchronousAndObservable` + `::disconnectPreservesKnownDeviceForExplicitReconnect`、`BleKnownDeviceRecoveryTest.kt::coordinatorReusesPersistedKnownDeviceAfterServiceRecreation`、`BleExternalScannerTest.kt::selectableFacadeBindsTheDiscoveredDeviceBeforeConnectionAndConfiguration` + `::selectableFacadeRecreatesTheSessionForAPersistedKnownDevice`。検索後選択と別instanceへのidentity永続化は安全コア層で検査済みだが、実SDK cacheは未検査 | P |
| 36 | 最近の接続イベントだけ保持し scan payload を診断へ出さない（`BluetoothScannerServiceTests.swift:265`） | `FakeExternalScannerTest.kt::diagnosticsKeepOnlyConnectionConfigurationEventsAndNeverPayloads` + `BleConnectionCoordinatorTest.kt::scanPayloadIsForwardedButNeverWrittenToDiagnostics` | D |
| 37 | 再起動後 restricted scanner を安全 baseline へ復元（`BluetoothScannerServiceTests.swift:292`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical` + `BleSymbologySnapshotStoreTest.kt::dataStoreRoundTripsAndClearsOnlyTheMatchingDevice` + `BleKnownDeviceStoreTest.kt::recreatedServiceReconnectsKnownDeviceAndRestoresSnapshotBeforeReady`。再生成後のfresh inventory→restore→Ready境界を検査済みだが、対象scanner実通信は未検査 | P |
| 38 | 旧 stuck build の Code128-only recovery を移行（`BluetoothScannerServiceTests.swift:325`） | iOSの [`BluetoothScannerService.swift#L1396`](../../ios/CodeMatch/Services/BluetoothScannerService.swift#L1396) と [`CodeMatcherTests.swift#L664`](../../ios/CodeMatchTests/CodeMatcherTests.swift#L664) は、旧iOS UserDefaultsの制限状態を新しい全種snapshotへ移す互換処理を検査する。Androidは独立Gradle projectの [`README.md#L1`](../../android/README.md#L1) で、現行の復旧値は [`BleSymbologySnapshotStore.kt#L63`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotStore.kt#L63) のversioned envelopeだけを受理し、未知versionは [`#L109`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotStore.kt#L109) 以降で拒否する。旧iOS stateを受けるAndroid移行契約はない | N/A（Androidに旧iOSデータ移行元なし） |
| 39 | manual disconnect で unrestricted baseline へ戻る（`BluetoothScannerServiceTests.swift:362`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical`（endSession の restore/clear）+ `FakeExternalScannerTest.kt::disconnectPreservesKnownDeviceForExplicitReconnect` | D（安全コア層） |
| 40 | manual disconnect 後、検索結果がなくても既知端末を reconnect（`BluetoothScannerServiceTests.swift:381`） | `FakeExternalScannerTest.kt::disconnectPreservesKnownDeviceForExplicitReconnect` + `::discoveryCanBeStoppedOrEndedByConnection` + `BleKnownDeviceRecoveryTest.kt::coordinatorReusesPersistedKnownDeviceAfterServiceRecreation` + `BleExternalScannerTest.kt::selectableFacadeRecreatesTheSessionForAPersistedKnownDevice` + `BleConnectionCoordinatorTest.kt::failedManualDisconnectCanBeRetriedWithoutReplacingPhysicalLink` + `::automaticReconnectConnectsOnlyAfterRetryCloseIsAcknowledged`。永続IDからdiscoveryなしで正しいsettings sessionを生成し、旧linkの切断確認後にだけ再接続開始できるが、実SDK cache/切断挙動は未検査 | P |
| 41 | service 再起動後も manual disconnect 済み既知端末を保持（`BluetoothScannerServiceTests.swift:408`） | `BleKnownDeviceRecoveryTest.kt::coordinatorReusesPersistedKnownDeviceAfterServiceRecreation` + `BleKnownDeviceStoreTest.kt::knownDeviceSurvivesDataStoreReopenAndWrongDeviceCannotClearIt`。version/profile一致時だけ再生成後に復元し、別deviceからのclearを拒否する | D（安全コア層） |
| 42 | 旧 diagnostics から last connected device を migration（`BluetoothScannerServiceTests.swift:425`） | iOSの [`BluetoothScannerService.swift#L612`](../../ios/CodeMatch/Services/BluetoothScannerService.swift#L612) と [`CodeMatcherTests.swift#L764`](../../ios/CodeMatchTests/CodeMatcherTests.swift#L764) は、旧診断eventの文字列から既知端末identityを復元する互換処理を検査する。Androidの [`BleKnownDeviceStore.kt#L90`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStore.kt#L90) はprofile/device identityだけの新規versioned envelopeであり、DataStore再オープンは [`BleKnownDeviceStoreTest.kt#L34`](../../android/scanner/ble/src/androidTest/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStoreTest.kt#L34) で検査するが、旧diagnosticsを入力する契約はない | N/A（Androidに旧iOS diagnostics移行元なし） |
| 43 | 750ms 未満の重複 callback を抑止（`BluetoothScannerServiceTests.swift:465`） | `BlePayloadTest.kt::duplicateGateUsesStrictLessThanSevenHundredFiftyMillis` + `FakeExternalScannerTest.kt::duplicateCallbacksAreSuppressedByInjectedClock` | D |
| 44 | 一致後 countdown を表示し次の QR 工程へ自動遷移（`BluetoothScannerServiceTests.swift:512`） | `ScanReducerTest.kt::autoAdvanceSupportsOneThreeAndFiveSecondsWithVirtualTicks` + `AppFlowInstrumentationTest.kt::enabledOneSecondAutoAdvanceMovesFromResultToNextQrInRealTime` | D |
| 45 | auto-advance OFF で countdown を取消し結果を残す（`BluetoothScannerServiceTests.swift:548`） | `ScanReducerTest.kt::turningAutoAdvanceOffCancelsAndKeepsMatchResult` | D |
| 46 | countdown を5秒へ変更すると表示を再開（`BluetoothScannerServiceTests.swift:565`） | `ScanReducerTest.kt::changingDelayRestartsOnlyAnActiveMatchCountdown` | D |
| 47 | countdown を1秒へ変更すると表示を再開（`BluetoothScannerServiceTests.swift:579`） | `ScanReducerTest.kt::autoAdvanceSupportsOneThreeAndFiveSecondsWithVirtualTicks` | D |
| 48 | 不一致では auto-advance countdown を開始しない（`BluetoothScannerServiceTests.swift:591`） | `ScanReducerTest.kt::mismatchRemainsVisibleAndNeverProducesRecordEffect` + `::changingDelayRestartsOnlyAnActiveMatchCountdown` | D |
| 49 | 同じ箱QRの成功済み照合は二重計上せず、auto-advanceも開始しない（`BluetoothScannerServiceTests.swift:601`） | `ScanReducerTest.kt::sameBoxQrCannotBeCountedTwiceInOneActiveSession` + `::restoredBoxQrIsDuplicateIgnoringCaseAndSurroundingWhitespace` + `AppFlowInstrumentationTest.kt::fakeScannerDifferentBoxesDuplicateRereadAndMismatchPreserveCountAndHistory` | D（Reducer + debug Fake/Room） |
| 50 | 箱QRが異なれば同一Code 128でも別箱として2件計上（`BluetoothScannerServiceTests.swift:620`） | `ScanReducerTest.kt::differentBoxQrsWithSameBarcodeAreBothRecorded` + `AppFlowInstrumentationTest.kt::fakeScannerDifferentBoxesDuplicateRereadAndMismatchPreserveCountAndHistory` | D（Reducer + debug Fake/Room） |
| 51 | Bluetooth で Code 128 を QR 前に読んでも進めない（`BluetoothScannerServiceTests.swift:649`） | `ScanReducerTest.kt::reverseOrderAndInvalidPayloadAreRejectedWithoutChangingState` + `::bluetoothRequiresBusinessPayloadFormats` | D（論理状態） |
| 52 | Code 128 待機中の QR callback を reject（`BluetoothScannerFlowTests.swift:17`） | `ScanReducerTest.kt::reverseOrderAndInvalidPayloadAreRejectedWithoutChangingState` | D（論理状態） |
| 53 | QR reread で値を消し Bluetooth を QR 待機へ戻す（`BluetoothScannerFlowTests.swift:36`） | `ScanReducerTest.kt::rereadQrReturnsToQrAndPreservesMatchedCount` + `ScanSessionCoordinatorTest.kt::disconnectFallsBackToCameraWithoutDiscardingCurrentQrStep`（工程保持） | D（状態） |
| 54 | Bluetooth の QR→Code 128 で即時一致し、同じ session mode を維持（`BluetoothScannerFlowTests.swift:58`） | `ScanReducerTest.kt::cameraQrThenCode128ProducesMatchAndRecordEffect` + `BleScannerSessionCoordinatorTest.kt::connectionReadAndWriteMustCompleteBeforePayloadsAreForwarded` + `AppFlowInstrumentationTest.kt::fakeScannerConnectsCameraSwitchRejectsReverseOrderAndRecordsTwoMatches` | D（debug Fake） |
| 55 | 接続済み Bluetooth を既定にするが手動 camera 選択を尊重（`BluetoothScannerFlowTests.swift:95`） | `ScanSessionCoordinatorTest.kt::readyBluetoothIsSelectedAtSessionStart` + `::explicitCameraChoiceWinsOverLaterReadyCallbacks` | D |
| 56 | configuration message 後に QR 読取案内へ戻る（`BluetoothScannerFlowTests.swift:111`） | `ScanScreenTest.kt::bluetoothConfigurationStatusReturnsToSessionRestrictionAfterReady` はConfiguring文言が消え、Ready後にQR読取案内とsession restriction文言へ戻ることを検査する。`ScanSessionCoordinatorTest.kt::configurationFailureKeepsTypedIssueWhenBaselineRestoreMakesScannerReady` は設定失敗をbaseline復元後も型付きで保持する | D（stateless UI + coordinator） |
| 57 | background で baseline、foreground で現在工程の mode を再適用（`BluetoothScannerFlowTests.swift:128`） | `ScanSessionCoordinatorTest.kt::backgroundStopsScannerAndForegroundResumesCurrentFormat` + `ScanReducerTest.kt::backgroundCancelsCountdownWithoutDiscardingResult`/`::foregroundResumesExpectedFormatWithoutChangingTheCurrentStep` | D（coordinator/reducer） |
| 58 | Bluetooth session 終了で unrestricted baseline へ復元（`BluetoothScannerFlowTests.swift:153`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical` | D（安全コア層） |
| 59 | camera stop 完了まで session end completion を待つ（`BluetoothScannerFlowTests.swift:173`） | `CameraScannerAsyncTest.kt::unbind completion waits for an in-flight ML Kit task to drain` + `CameraStopBoundaryTest.kt::sessionEndWaitsForDelayedHostStopCompletion`。CameraX unbindと処理中frame完了後にだけ論理sessionを終了 | D |
| 60 | ViewModel deinit が camera stop を drain するまで待つ（`BluetoothScannerFlowTests.swift:203`） | Androidではcamera hostがViewModel外で資源を所有し、composition disposal時にCameraScannerのterminal `close`/`unbind` completionを同じdrain境界へ保持する。`CameraScannerAsyncTest::close completion and a late unbind wait for an in-flight ML Kit task` が処理中frame・client解放順序を検査するが、Activity/process破棄を含む一体化lifetime試験ではない | P |
| 61 | Bluetooth disconnect 後も現在工程・QRを保持して camera へ fallback（`BluetoothScannerFlowTests.swift:234`） | `ScanSessionCoordinatorTest.kt::disconnectFallsBackToCameraWithoutDiscardingCurrentQrStep` は工程・QR保持を検査し、`ScanScreenTest.kt::bluetoothFallbackPreservesCurrentStepAndOffersRetryAndSettings` はtyped fallback文言と再接続・設定actionを検査する | D（stateless UI + coordinator） |
| 62 | 正規化した match を記録して session を終了（`BluetoothScannerFlowTests.swift:296`） | `HistoryRepositoryTest.kt::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` + `::endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt` | D |
| 63 | history を保存し再ロード（`BluetoothScannerFlowTests.swift:316`） | `HistoryRepositoryTest.kt::activeSessionIsRestoredAfterDatabaseRecreation` + `::activeSessionAndCheckpointPhasesSurviveIsolatedDatabaseReopen` + `::endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt` | D |
| 64 | 前 session と別の新 session を作成（`BluetoothScannerFlowTests.swift:331`） | `HistoryRepositoryTest.kt::sessionsAreNewestFirstAndRenameBlankBecomesNull` | D |
| 65 | payload を保存し code ごとの count を返す（`BluetoothScannerFlowTests.swift:347`） | `HistoryRepositoryTest.kt::payloadsArePersistedWithEachDuplicateEntry` + `::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` | D |
| 66 | active sessionの成功済み箱QRだけを正規化して重複判定（`BluetoothScannerFlowTests.swift:368`） | `ScanReducerTest.kt::restoredBoxQrIsDuplicateIgnoringCaseAndSurroundingWhitespace` は履歴から注入したQR identityのみで大文字/小文字と前後空白を正規化して重複とし、Code 128値で別箱を拒否しないことを検査する | D（Reducer復元境界） |
| 67 | 同一品番でも異なるラベルは別箱として記録し first-seen 順に group（`BluetoothScannerFlowTests.swift:390`） | `ScanReducerTest.kt::differentBoxQrsWithSameBarcodeAreBothRecorded` + `HistoryRepositoryTest.kt::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` + `HistoryModelsTest.kt::groupedEntriesKeepFirstSeenPartOrderAndEveryDuplicate` | D |
| 68 | 開始時の名前と rename（空白・再保存含む）（`BluetoothScannerFlowTests.swift:417`） | `HistoryRepositoryTest.kt::beginSessionTrimsNameAndReusesActiveSession` + `::sessionsAreNewestFirstAndRenameBlankBecomesNull` + `::nonBlankRenameSurvivesDatabaseReopen` + `AppFlowInstrumentationTest.kt::completedSessionCanBeRenamedViewedInDetailsAndDeleted` | D |
| 69 | 空名を nil/空 display name として扱う（`BluetoothScannerFlowTests.swift:437`） | `HistoryRepositoryTest.kt::blankNameIsStoredAsNull` + `HistoryModelsTest.kt::blankDisplayNameAndEndedStateAreRepresented` | D |
| 70 | session 削除を保存し cascade する（`BluetoothScannerFlowTests.swift:446`） | `HistoryRepositoryTest.kt::deletingSessionCascadesEntries` + `AppFlowInstrumentationTest.kt::completedSessionCanBeRenamedViewedInDetailsAndDeleted` | D |
| 71 | match なし終了は history へ保存せず破棄（`BluetoothScannerFlowTests.swift:466`） | `HistoryRepositoryTest.kt::endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt` + `AppFlowInstrumentationTest.kt::emptySessionIsNotKeptAfterConfirmedEnd` | D |

## Swift UI テスト（5本）

UI テストは複数の層・起動引数・永続ストレージを一度に検査するため、Android の個別 Compose/reducer/adapter テストを同じ一本の E2E と見なさない。

| # | Swift の意図（テスト） | Android の証拠 | 判定 |
|---:|---|---|:---:|
| 1 | Fake Bluetooth 接続、camera 切替、逆順拒否、QR→Code 128、一致・件数（`CodeMatchUITests.swift:55`） | `AppFlowInstrumentationTest.kt::fakeScannerConnectsCameraSwitchRejectsReverseOrderAndRecordsTwoMatches` は同じdebug Fake、app navigation、ViewModel、repositoryを通す | D（debug Fake） |
| 2 | 設定ガイド3段、拡大表示、検索・接続・切断・既知端末 reconnect（`CodeMatchUITests.swift:103`） | `AppFlowInstrumentationTest.kt::settingsGuideAndFakeScannerReconnectAreConnectedThroughTheApp` + `Code128BarcodeTest` | D（debug Fake） |
| 3 | 一致→duplicate→reset→不一致、件数、終了、history 掲載（`CodeMatchUITests.swift:148`） | `AppFlowInstrumentationTest.kt::fakeScannerMatchDuplicateRereadAndMismatchPreserveCountAndHistory` は同じdebug Fake、app navigation、ViewModel、Roomを通し、2箱だけの保存と不一致非保存を検査 | D（debug Fake） |
| 4 | 音選択、音量、日英切替、再起動後 language 維持（`CodeMatchUITests.swift:202`） | `SettingsScreenTest.kt::scannerActionsAndSoundLanguageCallbacksAreEmitted`、`FeedbackContractTest.kt`、`AppFlowInstrumentationTest.kt::languageSelectionPersistsAcrossActivityRecreation`、`SettingsRepositoryTest.kt::languageSurvivesDataStoreReopen` + `::allSettingsSurviveIsolatedDataStoreReopen`、`AppLanguageSynchronizerTest` 6件、`AppLanguageSynchronizerInstrumentationTest.kt::frameworkAndAppLanguageRoundTripDoesNotCreateARecreationLoop`。Activity再生成、DataStore再オープン、実`LocaleManager`との双方向no-loopも検査済み。OS設定画面の実操作・実端末/OEMでの同じ復元操作は未検査 | P |
| 5 | auto-advance ON、countdown 表示、次の QR へ遷移（`CodeMatchUITests.swift:265`） | `AppFlowInstrumentationTest.kt::enabledOneSecondAutoAdvanceMovesFromResultToNextQrInRealTime` は設定保存、実時間countdown、次QR工程を同じapp graphで検査 | D |

Swift UI 5本の直接対応とは別に、`NavigationTest`は履歴のsession→group→box選択がActivity再生成とHistory→Settings→Scan→History往復後も復元されること、およびcompact system backがbox→group→session→listを順に戻ることを実app graphで検査する。`BackNavigationTest`は通常の完了、無効時のfallback、予測gesture cancel時にcallbackを発火しない境界を検査するが、実gestureの視覚遷移は人手ゲートに残す。`HistoryPdfBridgeTest`と`HistoryPdfExporterInstrumentationTest`は、A4複数ページPDFの実render、専用cache、SAF byte保存、共有Intent/FileProvider契約を検査するが、実DocumentProvider/共有先アプリの操作を代替しない。

## 仕向地モルテンの追加テスト（2026-09-07）

Issue #84（PR #93 / #95 / #96 / #97 / #98 / #99）で追加した Swift テストと、その Android 証拠。番号は上の表の続きで、Swift のファイル内位置は変わるためテスト名で同定する。

| # | Swift の意図（テスト） | Android の証拠 | 判定 |
|---:|---|---|:---:|
| 72 | 伝送終端（CR/LF/NUL）だけを除去して仕向地を判定し、モルテンの空白は削らない（`CodeMatcherTests::testDestinationDetectStripsOnlyTransportTerminators`） | `CodeMatcherTest.kt::detectDestinationSeparatesSawaiAndMoltenAndRejectsOthers` | D |
| 73 | モルテン QR の全フィールドを実データから解析する（`CodeMatcherTests::testMoltenQRRecordParsesAllFields`） | `CodeMatcherTest.kt::moltenRecordParsesAllFieldsFromRealPayloads` | D |
| 74 | 空欄の TY ロケーション・時刻を欠損として扱う（`CodeMatcherTests::testMoltenQRRecordHandlesBlankOptionalFields`） | `CodeMatcherTest.kt::moltenRecordParsesAllFieldsFromRealPayloads`（空欄を含む実レコード） | D |
| 75 | 末尾空白が落ちた読取値を61桁へ補完し、同じ正規形にする（`CodeMatcherTests::testMoltenQRRecordPadsStrippedTrailingSpaces`） | `CodeMatcherTest.kt::moltenRecordPadsShortPayloadAndCanonicalizesIdentity` | D |
| 76 | 桁数・収容数・日付・時刻・部品番号欄が不正なら受理しない（`CodeMatcherTests::testMoltenQRRecordRejectsInvalidFields`） | `CodeMatcherTest.kt::moltenRecordRejectsWrongLengthQuantityDateTimeAndPartField` | D |
| 77 | `4-2-3` の Code 128 は澤井製作所・モルテンで受理し、デンソーでは受理しない（`CodeMatcherTests::testTagBarcodeRecordAcceptsFourTwoThreeForSawaiAndMolten`、#129 で反転） | `CodeMatcherTest.kt::tagValidationAllowsFourTwoThreeForSawaiAndMolten` | D |
| 77a | カード番号の4桁目が数字（`DAH4093870`）と9桁品番（品目番号欄の末尾空白を除去）の澤井製作所 QR を受理する（`CodeMatcherTests::testKanbanQRRecordAcceptsAlphanumericCardCodeAndNineCharacterPart`） | `CodeMatcherTest.kt::kanbanRecordAcceptsAlphanumericCardCodeAndNineCharacterPart` | D |
| 77b | 2026-09-08 の現場ラベル fixture（`sawai-2026-09-08-`）がカメラ・Bluetooth 双方の受理境界を通り、9桁品番5組と同一品番2箱を含む（`CodeMatcherTests::testSharedFieldLabels20260908PassBothScanBoundaries`） | `CodeMatcherTest.kt::sharedFieldLabels20260908PassBothScanBoundaries` | D |
| 78 | 箱固有キーは澤井製作所が QR のみ、モルテンは QR＋Code 128（`CodeMatcherTests::testBoxIdentityPerDestination`） | `CodeMatcherTest.kt::boxIdentityIncludesTagOnlyForMolten` | D |
| 79 | モルテンの共通 fixture がカメラ・Bluetooth 双方の受理境界を通る（`CodeMatcherTests::testSharedMoltenPairsPassBothScanBoundaries`） | `CodeMatcherTest.kt::sharedMoltenFixturesPassBothScanBoundaries` + `ScanReducerTest.kt::moltenFixturesKeepTheirPadding` | D |
| 80 | モルテンの QR→`4-2-3` バーコードが一致し、納品番号の箱数を報告する（`BluetoothScannerFlowTests::testMoltenQRThenFourTwoThreeBarcodeMatchesAndReportsDeliveryBox`） | `ScanReducerTest.kt::moltenQrThenTagMatchesWithNineCharPartAndDeliverySummary` + `ScanScreenTest.kt::moltenMatchResultShowsDeliveryBoxSummaryAndDestinationBadge` | D |
| 81 | 同じ納品番号の別ラベルは2箱目として計上し、累計収容数を積む（`BluetoothScannerFlowTests::testMoltenSecondBoxSameQRDifferentLabelIsCountedNotDuplicate`） | `ScanReducerTest.kt::moltenSameQrDifferentTagIsSecondBoxWithCumulativeQuantity` + `HistoryRepositoryTest.kt::payloadsArePersistedWithEachDuplicateEntry` | D |
| 82 | 同じ箱（同じ QR と同じ Code 128）の読み直しは重複（`BluetoothScannerFlowTests::testMoltenRescanOfSameBoxIsDuplicate`） | `ScanReducerTest.kt::moltenSameQrAndSameTagIsDuplicate` | D |
| 83 | モルテンの不一致は計上しない（`BluetoothScannerFlowTests::testMoltenMismatchIsNotCounted`） | `ScanReducerTest.kt::mismatchRemainsVisibleAndNeverProducesRecordEffect`（記録effectなし）+ `CodeMatcherTest.kt::sharedMatchingFixturesHaveTheSameResultsAsSwift`（モルテンの不一致 fixture） | D |
| 84 | 末尾空白が落ちた読取値でも一致し、別の箱を作らない（`BluetoothScannerFlowTests::testStrippedTrailingSpacesStillMatchAndDoNotCreateSecondBox`） | `ScanReducerTest.kt::moltenQrWithTrailingSpacesStrippedIsAcceptedAndSharesIdentityWithPaddedForm` | D |
| 85 | 最初に受理した QR で仕向地を固定し、別仕向地の QR を拒否する（`BluetoothScannerFlowTests::testSessionLocksToFirstDestinationAndRejectsOtherDestinationQR`、`::testSawaiSessionRejectsMoltenQRViaCameraNamingSawai`） | `ScanReducerTest.kt::firstAcceptedQrLocksDestinationAndOtherDestinationQrIsRejected` + `ScanScreenTest.kt::wrongDestinationMessageNamesLockedDestinationOnCameraAndBluetooth` | D |
| 86 | 澤井製作所のセッションでも 9桁品番の QR と `4-2-3` の Code 128 が一致し、カメラでは同一値2回の確定を通る（`BluetoothScannerFlowTests::testFourTwoThreeBarcodeMatchesInSawaiSession`、#129 で反転） | `ScanReducerTest.kt::sawaiAndMoltenSessionsAcceptFourTwoFourAndFourTwoThreeTags` + `ScanSessionCoordinatorTest.kt::cameraFourTwoThreeTagGoesThroughStabilizerInSawaiAndMoltenSessions` | D |
| 86a | 固定は不一致・手動の次工程でも解除されない（Swift は `::testSessionLocksToFirstDestinationAndRejectsOtherDestinationQR` の再読取で暗黙に確認） | `ScanReducerTest.kt::destinationLockSurvivesMismatchAndManualNext` | D |
| 87 | Code 128 待機中のモルテン QR は順序違いとして拒否（`BluetoothScannerFlowTests::testMoltenQRAtBarcodeStepIsWrongOrder`） | `ScanReducerTest.kt::reverseOrderAndInvalidPayloadAreRejectedWithoutChangingState` | D |
| 88 | ViewModel 再生成時に active session から仕向地を復元（`BluetoothScannerFlowTests::testDestinationIsRestoredFromActiveSessionOnViewModelCreation`） | `ScanSessionCoordinatorTest.kt::restoredCheckpointDestinationSeedsTheLock` + `::sessionDestinationSeedsTheLockWhenCheckpointHasNone` + `::recordedBoxesDeriveTheLockWhenNothingElseIsStored` + `ScanCheckpointMappingTest.kt::destinationRoundTripsInEveryPhase` + `::checkpointWithoutDestinationDerivesItFromAcceptedQr` + `ScanViewModelCheckpointInstrumentationTest.kt::moltenDestinationLockSurvivesIsolatedDatabaseReopen` | D |
| 89 | 同じ QR・別ラベルは別の箱、同じ QR・同じラベルは重複（`HistoryStoreTests::testMoltenSameQRDifferentLabelsAreDistinctBoxes`、`::testMoltenSameQRSameLabelIsDuplicate`） | `ScanReducerTest.kt::restoredMoltenBoxesSeedDuplicateAndDeliveryCounts` + `HistoryRepositoryTest.kt::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` | D |
| 90 | 澤井製作所の重複判定は QR だけを鍵にしたまま（`HistoryStoreTests::testSawaiDuplicateRuleStillKeysOnQROnly`） | `CodeMatcherTest.kt::boxIdentityIncludesTagOnlyForMolten` + `ScanReducerTest.kt::sameBoxQrCannotBeCountedTwiceInOneActiveSession` | D |
| 91 | 納品番号ごとの箱数と累計収容数を集計する（`HistoryStoreTests::testDeliverySummaryCountsBoxesAndQuantity`、`::testDeliveryNumberCount`） | `HistoryDeliveryGroupsTest.kt::deliveryGroupsKeepFirstSeenOrderAndSumPackQuantities` + `::sawaiAndUnrecordedPayloadsProduceNoDeliveryGroups` | D |
| 92 | 品番グループの中を納品番号ごとに最初に見た順で分ける（`HistoryStoreTests::testGroupedEntriesExposeDeliveryGroupsInFirstSeenOrder`） | `HistoryDeliveryGroupsTest.kt::deliveryGroupsKeepFirstSeenOrderAndSumPackQuantities` + `HistoryModelsTest.kt::groupedEntriesKeepFirstSeenPartOrderAndEveryDuplicate` | D |
| 93 | 一致記録で仕向地を1回だけ確定して永続化する（`HistoryStoreTests::testRecordMatchSetsDestinationOnceAndPersists`、`::testSetActiveSessionDestinationIfNeededPersistsAndDoesNotOverwrite`） | `HistoryRepositoryTest.kt::firstMatchLocksSessionDestinationAndLaterValuesCannotChangeIt` + `::checkpointDestinationLocksSessionBeforeFirstMatchAndRoundTrips` | D |
| 94 | 仕向地のない旧履歴・未知の値でも履歴を捨てない（`HistoryStoreTests::testLegacyJSONWithoutDestinationLoadsAndResolvesFromFirstEntry`、`::testUnknownDestinationRawValueDoesNotDiscardHistory`） | `HistoryRepositoryTest.kt::legacySessionWithoutDestinationReadsAsNull` + `HistoryModelsTest.kt::destinationDefaultsToNullForLegacySessions` + `HistoryDeliveryGroupsTest.kt::resolvedDestinationPrefersTheStoredValueThenFallsBackToTheEntries` + `CodeMatchDatabaseMigrationTest.kt::versionTwoMigratesToVersionThreeWithNullableDestinationColumns` + `::versionOneMigratesToVersionThreeThroughBothMigrations` | D |
| UI 6 | Fake Bluetooth のモルテン流れで仕向地が固定され、納品番号ごとに箱が数えられる（`CodeMatchUITests::testMockBluetoothScannerMoltenFlowLocksDestination`） | `AppFlowInstrumentationTest.kt::fakeScannerMoltenFlowCountsBoxesPerDeliveryNumberAndLocksDestination` は同じ debug Fake、app navigation、ViewModel、Room を通す | D（debug Fake） |
| PDF | 履歴詳細と PDF のモルテン項目（仕向地、納品番号数、納品番号ごとの箱数・累計、解析全項目）（`SessionPDFExporterTests::testFixturePayloadsAreFullRecords` + `SessionPDFExporterTests::testMoltenInstructionDateAndTimeAreFormattedForDisplay` + `SessionPDFExporterTests::testMoltenSessionListsDeliveryNoteBlockPerDeliveryNumber` + `SessionPDFExporterTests::testSawaiSessionKeepsPartNumberBlocksAndShowsDestination` + `SessionPDFExporterTests::testSessionWithoutParsableQRHasNoDestinationLine`、`CodeMatchUITests::testMockBluetoothScannerMoltenFlowLocksDestination` の履歴詳細部分） | `HistoryPdfContentTest.kt::moltenReportGroupsBoxesPerDeliveryNumberWithCumulativeQuantityAndAllFields` + `::englishMoltenReportUsesEnglishLabels` + `::legacySawaiReportIsUnchangedWhenDestinationIsNull` + `HistoryDeliveryGroupsTest.kt::instructionDateAndTimeAreFormattedAndUnexpectedValuesArePreserved` + `HistoryScreenTest.kt::moltenEntryDetailDisplaysAllParsedFields` + `::moltenGroupDetailShowsPerDeliveryNumberSummary` + `::sessionDetailAndRowShowDestination` | D |

## 仕向地デンソーの追加テスト（2026-09-07）

Issue #106（PR #113 / #114 / #115 / #116、iOS の履歴・PDF は #117）で追加した Swift テストと、その Android 証拠。番号は上の表の続きで、Swift のファイル内位置は変わるためテスト名で同定する。

| # | Swift の意図（テスト） | Android の証拠 | 判定 |
|---:|---|---|:---:|
| 95 | デンソーの共通 fixture がカメラ・Bluetooth 双方の受理境界を通る（`CodeMatcherTests::testSharedDensoPairsPassBothScanBoundaries`） | `CodeMatcherTest.kt::sharedDensoFixturesPassBothScanBoundaries` + `ScanReducerTest.kt::moltenAndDensoFixturesKeepTheirPadding` | D |
| 96 | 実物のかんばんから JAMA 自己記述形式の全項目を解析する（`CodeMatcherTests::testDensoKanbanRecordParsesAllItemsFromRealPayload`） | `CodeMatcherTest.kt::densoRecordParsesAllItemsFromRealPayload` | D |
| 97 | 項目構成・ヘッダ長が違うかんばんも同じ規則で解析でき、仕向地判定がデンソーを返す（`CodeMatcherTests::testDensoKanbanRecordParsesDifferentItemLayout`） | `CodeMatcherTest.kt::densoRecordParsesADifferentItemLayout` | D |
| 98 | ヘッダ長・データ部の桁合計・必須項目・項目番号重複が崩れた読取値は受理しない（`CodeMatcherTests::testDensoKanbanRecordRejectsBrokenHeaderOrDataLength`） | `CodeMatcherTest.kt::densoRecordRejectsBrokenHeaderOrDataLength` | D |
| 99 | `6-4` の Code 128 はデンソーだけで受理する（`CodeMatcherTests::testTagBarcodeRecordAcceptsSixFourOnlyForDenso`） | `CodeMatcherTest.kt::tagValidationAllowsSixFourOnlyForDenso` | D |
| 100 | 品番の表記は仕向地依存で、デンソーの10桁は `6-4`（`CodeMatcherTests::testFormatPartNumberIsDestinationAware`） | `CodeMatcherTest.kt::formatPartNumberIsDestinationAware` | D |
| 101 | デンソーの QR→`6-4` バーコードが一致し、品番ごとの箱番号を報告する（`BluetoothScannerFlowTests::testDensoQRThenSixFourBarcodeMatches`、`::testDensoQRViaCameraShowsSixFourPartNumber`） | `ScanReducerTest.kt::densoQrThenSixFourTagMatchesAndCountsPerPartNumber` + `ScanScreenTest.kt::densoMatchResultShowsDestinationBadgeAndPlainCard` | D |
| 102 | 同じ品番の別かんばんは2箱目として計上する（`BluetoothScannerFlowTests::testDensoSecondKanbanSamePartIsCountedAsSecondBox`） | `ScanReducerTest.kt::densoSecondKanbanSamePartIsSecondBox` | D |
| 103 | 同じかんばんの読み直しは、ラベルが違っても重複（`BluetoothScannerFlowTests::testDensoRescanOfSameKanbanIsDuplicate`、`HistoryStoreTests::testDensoDuplicateRuleKeysOnQROnly`） | `ScanReducerTest.kt::densoSameKanbanIsDuplicateEvenWithAnotherTag` + `CodeMatcherTest.kt::boxIdentityIncludesTagOnlyForMolten` | D |
| 104 | デンソーの箱数は品番ごとに数える（`HistoryStoreTests::testDensoMatchCountPerPartNumber`） | `ScanReducerTest.kt::restoredDensoBoxesSeedDuplicateAndPartCounts` | D |
| 105 | デンソーで固定したセッションは澤井製作所・モルテンの QR を拒否し、その逆も拒否する（`BluetoothScannerFlowTests::testSessionLockedToDensoRejectsSawaiAndMoltenQR`、`::testSawaiSessionRejectsDensoQR`） | `ScanReducerTest.kt::densoSessionRejectsSawaiAndMoltenQrWithWrongDestination` | D |
| 106 | 澤井製作所・モルテンのセッションでは `6-4` の Code 128 を受理しない（`BluetoothScannerFlowTests::testSixFourBarcodeIsRejectedInSawaiAndMoltenSessions`） | `ScanReducerTest.kt::sawaiAndMoltenSessionsRejectSixFourTag` + `ScanSessionCoordinatorTest.kt::cameraSixFourTagGoesThroughStabilizerOnlyInDensoSession` | D |
| 107 | Code 128 待機中のデンソー QR は順序違いとして拒否（`BluetoothScannerFlowTests::testDensoQRAtBarcodeStepIsWrongOrder`） | `ScanReducerTest.kt::reverseOrderAndInvalidPayloadAreRejectedWithoutChangingState` | D |
| 108 | デンソーの不一致は計上しない（`BluetoothScannerFlowTests::testDensoMismatchIsNotCounted`） | `CodeMatcherTest.kt::sharedMatchingFixturesHaveTheSameResultsAsSwift`（デンソーの不一致 fixture 4件）+ `ScanReducerTest.kt::mismatchRemainsVisibleAndNeverProducesRecordEffect` | D |
| 109 | ViewModel 再生成時に active session からデンソーの固定を復元（`BluetoothScannerFlowTests::testDestinationIsRestoredFromActiveSessionForDenso`） | `ScanCheckpointMappingTest.kt::checkpointWithoutDestinationDerivesDensoFromAcceptedQr` + `ScanViewModelCheckpointInstrumentationTest.kt::densoDestinationLockSurvivesIsolatedDatabaseReopen` | D |
| 110 | 解析できない QR の案内は、デンソーで固定済みでも未固定の `JAMA` 接頭辞でも長さを示さない（Swift は長さ案内自体を持たないため N/A） | `ScanReducerTest.kt::densoLockedInvalidQrIsInvalidWithoutLengthHint` + `::unlockedJamaPrefixedInvalidQrIsInvalidNotIncomplete` | N/A（Android のみ） |
| UI 7 | Fake Bluetooth のデンソー流れで仕向地が固定され、品番ごとに箱が数えられる（`CodeMatchUITests::testMockBluetoothScannerDensoFlowLocksDestination`） | `AppFlowInstrumentationTest.kt::fakeScannerDensoFlowCountsBoxesPerPartNumberAndLocksDestination` は同じ debug Fake、app navigation、ViewModel、Room を通す | D（debug Fake） |
| PDF 2 | 履歴詳細のデンソー13項目（帳票区分・部品番号・包装・収容数・次区・指示・かんばん連番・管理番号・納入日・便・指示数・アイテムNo・受入）、PDF のかんばん要約3行と箱ごとのかんばん連番・管理コード、カード番号と納品番号数が出ないこと、履歴 JSON の `"destination": "denso"`。Swift 側は #117 で追加する | `HistoryPdfContentTest.kt::densoReportPrintsKanbanBlockAndBoxesPerPartNumber` + `::englishDensoReportUsesEnglishLabels` + `HistoryDeliveryGroupsTest.kt::densoEntriesProduceNoDeliveryGroups` + `HistoryJsonExporterTest.kt::densoSessionExportsItsDestinationIdAndKanbanPayloadByteForByte` + `HistoryScreenTest.kt::densoEntryDetailDisplaysAllParsedFields` + `::densoSessionDetailAndRowShowDestination` | D |

## 照合ログ（#122、Android 側の証拠）

親 Issue #120 の iOS 側（#121）は別 PR なので、この節は Swift との行対応ではなく Android 側の証拠だけを挙げる。JSONL のスキーマ一致は両 OS の出力を突き合わせて確認する。

| 対象 | Android の証拠 |
|---|---|
| 一致・不一致・重複と受理された QR / Code 128 が記録される | `ScanSessionCoordinatorTest.kt::scanLogRecordsAcceptedQrBarcodeMatchAndSessionEnd` + `::scanLogRecordsMismatchAndDuplicateWithTheirPartNumberButNoBoxNumber` |
| 不受理 7 種（`session_not_started` / `wrong_order` / `empty` / `incomplete` / `overlong` / `invalid` / `wrong_destination`）が読取値つきで記録される | `ScanSessionCoordinatorTest.kt::scanLogRecordsEveryRejectionReasonWithTheValueThatCausedIt` |
| 入力元不一致の破棄、カメラの確認 1 フレーム目、結果表示中に握りつぶした読取も残る | `ScanSessionCoordinatorTest.kt::scanLogRecordsDroppedSourceConfirmationCandidateAndResultCallbacks` |
| Room v4 の `scan_log`、3→4 と 1→4 の migration | `CodeMatchDatabaseMigrationTest.kt::versionThreeMigratesToVersionFourWithTheScanLogTable` + `::versionOneMigratesToVersionFourThroughEveryMigration`（Pixel 7） |
| 全項目の往復、件数 Flow、消去、5,000 件の切り詰め | `ScanLogRepositoryTest.kt`（Pixel 7、4 件） |
| JSONL のヘッダ行・1 イベント 1 行・null の明示・引用符/改行/バックスラッシュのエスケープ・末尾空白の保持・ファイル名 | `ScanLogJsonExporterTest.kt`（5 件） |
| 設定画面最下部のカード、件数表示、0 件での共有・保存の無効化、48dp、消去の確認ダイアログ | `SettingsScreenTest.kt::scanLogCardReportsItsCountAndGatesSharingSavingAndClearing` + `::explicitlyOpenedGuideShowsThreeStepsAndAllPreferenceGroups` + `::primarySettingsControlsKeepAccessibleTouchTargets`（Pixel 7） |
| 日英の文言と書式トークンの一致 | `SettingsUiTextTest.kt`（新規、`HistoryUiTextTest` と同じ形） |
| 一致 → 同じ箱の再読み → 設定画面の件数 | `AppFlowInstrumentationTest.kt::matchAndRepeatedBoxAreRecordedInTheScanLogAndCountedInSettings`（CI emulator） |
| BLE 診断ログが読取値を含まないままであること | `BleExternalScannerTest.kt::facadeMapsExternalScannerStateAndKeepsPayloadsOutOfDiagnostics`（変更なし） |

実スキャナーでの照合ログ書き出し、共有先アプリでの受け取り、5,000 件を実際に超えた運用は未実施である。

## 検品レポート（2026-09-11）

セッション詳細の第2のPDF「検品レポート」（1行 = 澤井製作所の品番+枝番／モルテンの納品番号／デンソーの品番、品番順、表ヘッダーの繰り返しとページ番号）。Swift と Kotlin の行の作り方は同じ規則で、両側とも現場ラベルの実データで固定する。

| 対象 | Swift | Android の証拠 | 分類 |
|---|---|---|---|
| 行のキーと並び（品番+枝番、`BCJH5281GG (02)` 表記、枝番なしは括弧なし）、箱数、1箱の納入数量、数量計 | `InspectionReportTests::testSawaiRowsAreKeyedByPartNumberAndSuffixAndSortedByPartNumber` | `InspectionReportContentTest.kt::sawaiRowsAreKeyedByPartNumberAndSuffixAndSortedByPartNumber` | D |
| モルテンは納品番号ごと、生の部品番号と納入先、収容数と累計。並びは品番 → 納品番号で同じ品番の行が離れない | `InspectionReportTests::testMoltenRowsAreOnePerDeliveryNumberWithRawPartAndDeliveryPoint` + `::testMoltenRowsSortByPartNumberBeforeDeliveryNumberSoOnePartStaysTogether` | `InspectionReportContentTest.kt::moltenRowsAreOnePerDeliveryNumberWithRawPartAndDeliveryPoint` + `::moltenRowsSortByPartNumberBeforeDeliveryNumberSoOnePartStaysTogether` | D |
| デンソーは品番ごと（`6-4`）、収容数と数量計 | `InspectionReportTests::testDensoRowsAreOnePerPartNumberFormattedSixFour` | `InspectionReportContentTest.kt::densoRowsAreOnePerPartNumberFormattedSixFour` | D |
| 解析できない箱は末尾の行に残り、箱数の合計がセッションの箱数と一致する。仕向地なしは全行未解析 | `InspectionReportTests::testBoxesWithoutAParsableQRTrailAsUnparsedRowsSoNoBoxIsDropped` + `::testSessionWithoutDestinationFallsBackToSawaiLayoutWithEveryBoxUnparsed` | `InspectionReportContentTest.kt::boxesWithoutAParsableQrTrailAsUnparsedRowsSoNoBoxIsDropped` + `::sessionWithoutDestinationFallsBackToSawaiLayoutWithEveryBoxUnparsed` | D |
| デンソーのかんばんを澤井製作所として読まない（寛容な解析器の誤認防止） | `InspectionReportTests::testADensoKanbanIsNeverReadAsASawaiSlip` | `InspectionReportContentTest.kt::aDensoKanbanIsNeverReadAsASawaiSlip` | D |
| 見出し（検査箱数、品番数（枝番別）／納品番号数／品番数）、列名、セルの値、脚注、日英 | `InspectionPDFExporterTests::testSawaiReportPrintsHeaderCountsColumnsAndOneRowPerPartNumberWithSuffix` + `::testMoltenReportPrintsOneRowPerDeliveryNumberWithDeliveryPoint` + `::testDensoReportPrintsOneRowPerPartNumberWithoutInstructedQuantity` + `::testEmptySessionPrintsTheNoMatchesLine` | `InspectionPdfContentTest.kt`（6 件、ja/en 対） | D |
| 複数ページで表ヘッダーが各ページに繰り返され、`n / N` が付く | `InspectionPDFExporterTests::testLongReportRepeatsTheTableHeaderOnEveryPageAndNumbersPages` | `HistoryPdfExporterInstrumentationTest.kt::inspectionReportRendersEveryPageOfAMultiPageTable`（全ページの実 render。ヘッダー繰り返しは pure content では表現できず render 側の責務） | P |
| ファイル名は「検品レポート_」+ 照合履歴と同じ語幹、同じ `cache/codematch-pdf/` | `InspectionPDFExporterTests::testFileNameUsesTheInspectionPrefixAndTheHistoryStem` | `HistoryExportTextTest.kt::inspectionReportPrefixSharesTheHistoryFileNameSanitizing` + `HistoryPdfBridgeTest.kt::inspectionReportDocumentUsesTheInspectionPrefixAndStaysAPdf` + `HistoryPdfExporterInstrumentationTest.kt::inspectionCacheWriteUsesTheInspectionPrefixBelowTheSameCacheDirectory` | D |
| セッション詳細に検品レポートの行が照合履歴の行の上にあり、4 つのボタンがそれぞれのコールバックを呼ぶ。font scale でも 48dp | （UI テストなし。識別子 `saveInspectionReportButton` / `shareInspectionReportButton` を保持） | `HistoryScreenTest.kt::sessionDetailOffersInspectionAndMatchHistoryPdfRowsInJapanese` + `HistoryFontScaleAccessibilityTest.kt::compactSessionDetailKeepsGroupsAndPdfActionsReachableAtLargeFontScales` | P |

Swift 側は `PDFPageWriter`（表の行・ヘッダー再描画・フッター）を新設し、照合履歴レポートも同じ writer に載せ替えた（`SessionPDFExporterTests` は変更なしで通る）。Android 側は release gate の `File(` 許可リストを変えず、`HistoryPdfExporter` に `HistoryReportKind` を足して同じ cache 経路で書き出す。紙の検品表との実突き合わせは人手で行う。

## 残る物理・手動・未対応の証拠

2026-09-05のIssue #57で、この節に挙がる実機・手動ゲートのうち未実施のものは打ち切りとし、これ以上確認しません。打ち切りは検証成功を意味せず、`P`/`—`の分類は変更しません。一覧は[`STATUS.md`](STATUS.md)の「打ち切った確認項目」を参照してください。

- AVFoundationのPreview層attach/detachとqueued shutdownはAndroidのCameraX境界へ対応済みだが、#5はiOSのclampとAndroidのrejectでpolicyが異なるためPのままにする。#6のscreen-capture policyはiOS固有であり、共通仕様にないことを根拠リンクで確認したN/Aである。
- Android の camera adapter について、実端末での QR→Code 128 実読取、focus 成否、連続箱、回転、background/foreground、権限の実結果は [`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md) に記録する。Compose stage/ROI テストだけでは完了にならない。
- Android BLE はSDK非依存 safety coreとFake/UIに加え、公式Inateck Android SDK 2.0.0を非配付`scannerPoc`へ接続した（2026-09-05の#56で`release`へ同梱、同日に`release` APKでのQR→Code 128照合完了をユーザーが確認）。2026-09-04にPixel 7 / BCST-36で検索・接続、実機inventory由来のQR+Code 128制限とfresh readback、分割通知、QR→Code 128一致、背景時復元、QR待機中のapp強制終了後の既知端末自動再接続と追加のQR→Code 128一致を確認した。logical format補正、接続・探索世代取消、一時的なBluetooth/権限不可時の有限再試行、PoC最小権限とvendor-log除去は自動検査する。同一箱重複・不一致・連続箱、予期しない切断、scanner再起動、Code 128待機/結果表示中のapp強制終了、timeout、Samsung、配付条件は未実施のまま打ち切り（手動切断・電源OFF後の再接続は2026-09-05に承認済み）。
- #38のlegacy Code128-only recoveryと#42のdiagnosticsからの既知端末migrationは、旧iOS UserDefaults/diagnosticsをAndroidへ移すproduct-history-only処理なのでN/Aとする。Android側の新規snapshot/known-device envelope、service再生成後のidentity保持、fresh inventory、復元完了前Ready禁止は [`BleKnownDeviceRecoveryTest`](../../android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceRecoveryTest.kt) / [`BleKnownDeviceStoreTest`](../../android/scanner/ble/src/androidTest/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStoreTest.kt) で自動化したが、対象scanner実通信の証拠ではない。
- Swift UI 5本のうち、Fake接続、一致→duplicate→読み直し→不一致、設定ガイド、実時間auto-advanceは同じdebug Fake/DI/repositoryを通すapp instrumentationへ昇格した。0件破棄、履歴名称変更・詳細・削除、履歴選択のActivity再生成/画面往復/system back、英語1/2 plural、テスト専用ランダムファイルを使ったDataStore/Room再オープン（active session、全checkpoint phase、全設定値）、PDF render/Intent契約も自動化済み。repository/Activity再生成テスト自体はOS process境界を含まない（OS force-stopを伴う専用runnerは2026-09-06に廃止し、checkpoint復元は`ScanViewModelCheckpointInstrumentationTest`と`core:data`の再オープンtestで検査する）。実DocumentProvider/共有先、実カメラ、対象BLEはPixel 7で部分的に受入済み、TalkBack/Switch Access、Samsung受け入れは打ち切り（[`STATUS.md`](STATUS.md)）。

## この監査で追加した純 JVM 証拠

`ScanViewModelSessionNameTest`は、DataStoreの初回通知を意図的に止め、初期化前に公開UI actionから作業名の編集・開始を行う。最新の入力をtrimして保存すること、空欄への編集を優先すること、保存済みactive sessionの名前を復元して複数回の開始でも増殖しないことを、製品データと分離したin-memory Roomで検査する。修正前の初期化処理では最新名のtestが`expected newest / actual null`で失敗することも確認した。実端末の操作速度やストレージ性能の計測ではない。

日英リソースの対応はAndroid lintの`MissingTranslation`（`lintDebug`で失敗）で保証する。配付はAPKのみでAAB/言語splitを使わないため、両言語は常に同梱される。

`android/core/model/src/test/kotlin/jp/rimtty/codematch/core/model/SettingsModelsTest.kt::unknownPreferenceValuesUseSafeDefaults` に `AppLanguage.fromCode(null) == JAPANESE` を追加した。これは Swift の「保存値なし」fallback を Android の framework-free model 層で直接固定するもので、production code や BLE/camera/navigation は変更していない。#6/#38/#42は根拠付きN/Aであり、AVCapture lifetimeや旧iOS migrationを推測するテストは追加していない。

今回のBLE UI監査では、`ScanSessionCoordinator`が設定失敗をbaseline復元で上書きする前に`ScannerIssue`を渡す境界を追加した（当時は明示的な非同期再接続後のReadyだけがBluetoothへ再昇格できたが、#145で設定完了のたびに戻る規則へ変えた）。`ScanScreenTest::bluetoothConfigurationStatusReturnsToSessionRestrictionAfterReady`と`SettingsScreenTest::configurationStatusIsLocalizedAndFailureReasonStaysHidden`は日英の設定中・session restriction・失敗文言を検査し、adapter由来のraw reasonを表示しない。いずれも`ExternalScanner`の安全境界を使う状態/UI証拠であり、release camera-onlyのNearby permission、GATT/UUID/protocol、対象scanner実通信は追加していない。

今回のカメラ監査では、実カメラを要求しない境界として、既存バインディング後のPreviewView回転時rebind、権限待ち、背面カメラなし、停止後のfocus結果破棄、focus開始例外の型付き通知を `CameraScannerAsyncTest` に追加した。`CameraStageTest::pointerFocusTracksRunningStateAcrossStartAndStop` は開始/停止を跨いで古いCompose pointer callbackがfocusを発火しないことを検査し、`CameraPermissionStateTest::canceledPermissionResultCannotAffectTheNextCameraBinding` はキャンセル済みActivityResultを次の要求へ誤帰属しないtombstone境界を固定する。ROI中間Bitmapの例外経路も解放する。iOS固有の画面録画ポリシー、実カメラdecode、process kill/relaunchは追加していない。

## 検証

- 監査時点の Swift source の `func test` 数: unit 71、UI 5。2026-09-07時点では unit が約155、UI 7（仕向地モルテン・デンソーの追加分を含む）。fixture は JSON として schemaVersion 2、47 case、ID 重複なし、`destination` は `sawai` 23 件・`molten` 11 件・`denso` 11 件・どれでもない 2 件。Android の JVM test は 446 件。
- Android の focused Gradle test は Android Studio の JDK と SDK を明示して実行し、次の2系統がともに `BUILD SUCCESSFUL` になった。`./gradlew :core:model:testDebugUnitTest :core:matching:testDebugUnitTest :feature:scan:testDebugUnitTest :scanner:ble:testDebugUnitTest :scanner:fake:testDebugUnitTest`、および `./gradlew :core:export:testDebugUnitTest :feature:history:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest`。
- `:scanner:camera:testDebugUnitTest` は非同期境界20テストを含め `BUILD SUCCESSFUL`、`:scanner:camera:lintDebug` も `BUILD SUCCESSFUL` になった。`BundledMlKitImageDecodeTest` 3件は共有QR/Code 128画像の実decodeと誤形式拒否に成功したが、実機 camera readを意味しない。
- 2026-09-03の追加hardening後、Android JVM testは全249件が成功した。Pixel 7/API 36では通常のdebugアプリ保存領域を消去せず、`core:data` 21件、`feature:scan` 15件、`scanner:camera` 3件の計39件を実行し、失敗・skip 0だった。以前の全module instrumentation 80件、BLE変更時のfocused 36件、Android 17/API 37.1・16KB emulator 63件は別時点の記録であり、この差分全体はPRのAPI 31/36 CIで再確認する。
- Android実装の証拠にiOS Simulatorは使用しない。iOS XCTestは、同一リポジトリの既存iOS版を壊していないことを確認するPR CIの回帰ゲートとしてのみ扱う。
- 2026-09-04の追加差分は、`feature:scan`/`scanner:ble`/`app`の対象JVM test、Android test compile、lintが成功した。Android 17/API 37.1・16KB emulatorで`ScanScreenTest` 11件と実ContentProvider経由のPDF write/read 1件が成功し、検証後にemulatorを終了した。対象BCST-36はオートスリープ中のため、BLE実通信の追加合否には使用していない。
- 実行可能だった静的確認は source/test 数、fixture 構造、`git diff --check`。実機 camera/BLE の結果はこの表に含めていない。
