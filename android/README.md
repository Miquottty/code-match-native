# Code Match Android

Android版Code Matchの独立Gradleプロジェクトです。iOS版と同じ照合契約を、Android標準の操作とJetpack Compose / Material 3で実装します。Android版はストアへ提出せず、手元でreleaseビルドしたAPKを自分の端末へ入れて使う運用です。`release`はカメラ入力に加えて公式Inateck Android SDKによるBLE読取を同梱し、`debug`はFake scannerを使います。

## 開発環境

- Android Gradle Plugin 9.3.2
- Gradle 9.5.0（Wrapperで固定）
- Android SDK 37（compile / target）
- Android 12（API 31）以上
- JDK 21（ソース／バイトコードの互換性はJava 17）

AGP 9のbuilt-in Kotlinを使うため、Androidモジュールへ`kotlin-android`プラグインは追加しません。Composeを使うモジュールだけCompose Compiler Gradle pluginを適用します。

## モジュール

```text
app/                  # Hilt統合、Material 3 navigation、Android system bridge
core/model/           # framework-free domain model（Android library境界）
core/matching/        # parser / matcher（fixture parity）
core/designsystem/    # Code MatchのMaterial 3 tokensとtheme
core/data/            # Room履歴とPreferences DataStore設定
core/export/          # 日英PDF生成、保存・共有用export
feature/scan/         # 照合状態機械とstateless Compose UI
feature/history/      # 履歴一覧・詳細のadaptive Compose UI
feature/settings/     # scanner、auto-advance、音、言語の設定UI
scanner/api/          # カメラ/BLEから独立したscanner契約
scanner/camera/       # CameraX + bundled ML Kit（QR / Code 128）
scanner/fake/         # 開発用Fakeの隔離先（debug専用）
scanner/ble/          # SDK/UUID非依存のBLE安全コア（release未接続）
scanner/inateck/      # 公式SDK adapter（release専用、binaryはローカル取得）
```

`core/model`と`core/matching`の本体ロジックはAndroid APIに依存しません。共通fixtureは`../shared/test-fixtures`をテストリソースとしてクラスパスへ追加し、テストからは`ClassLoader.getResourceAsStream`で読み込みます。アプリアイコンは通常・round・adaptive・monochromeを持ち、Android 13以降のper-app languageにも日本語と英語を公開します。

## ビルドとテスト

```bash
./gradlew assembleDebug
./gradlew lintDebug testDebugUnitTest
bash scripts/run-connected-tests.sh
./gradlew :app:assembleRelease
mkdir -p tmp
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > tmp/release-dependencies.txt
bash scripts/verify-release-hardening.sh --dependency-report tmp/release-dependencies.txt
```

公式Inateck SDKのbinaryは固定commitから取得してchecksumを検証し、Git管理外の`scanner/inateck/libs`と`jniLibs`へ置きます（再配付ライセンスがないため、Gitやreleaseの成果物へは含めず、手元利用に限ります）。releaseはこのbinaryがないとビルドできません。

```bash
bash scripts/setup-inateck-sdk.sh
./gradlew :app:assembleRelease
bash scripts/verify-release-hardening.sh        # 権限・backup除外・FileProvider・Fake/analytics不在・ABI・vendorログ除去・ML Kit registrarの検査
adb install -r app/build/outputs/apk/release/app-release.apk
```

releaseは既定でdebug keystoreで署名するため、再ビルドしてもそのまま上書きインストールできます。自分のkeystoreで署名する場合は `~/.gradle/gradle.properties` などに `codematchReleaseStoreFile` / `codematchReleaseStorePassword` / `codematchReleaseKeyAlias` / `codematchReleaseKeyPassword` を設定します（keystoreはGit管理外に置く）。releaseはR8でminifyし、SDKのLog/System.out呼び出しを除去し、ABIはSDKに合わせて`arm64-v8a`だけです。SDKの`libscanner_cmd.so` / `libinateck_scanner_cmd.so`は4KB page alignmentのため、16KB page sizeの端末では動作しません（Pixel 7は4KB）。

Release検証は、まず`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`で解決済み依存グラフを出力し、その後
生成したAPKとproduction sourceを検査します。ネットワーク／Nearby権限、debug/Fake入口、広すぎる
`FileProvider`、カメラ画像/frameの保存や不意のpayload書き出し、analytics/crash SDKの依存を検出した時は失敗します。
Room、Preferences DataStore、将来のBLE復旧／既知端末状態はcloud Auto Backupと
device-to-device transferの両方から除外します。BLE snapshotとversion/profile付き既知端末identityは
`files/datastore/codematch-ble-symbology.preferences_pb`だけを使い、scan payloadや設定値を既知端末envelopeへ含めません。PDF共有で
`FileProvider`が公開するのは専用の`cache/codematch-pdf/`だけです。

checkerは依存ライブラリを追加せず、lockfile、SBOM、その他の生成物をリポジトリへ
作りません。Gradle dependency verificationとSBOM／ライセンス出力は、依存artifactの
供給元と署名ポリシーを固定してから別途導入します。現時点の再現可能なゲートは
Gradle Wrapper validation、release依存グラフ検査、checkerによるAPK/source検査です。

日英リソースはAPKに常に同梱されます（AAB・言語splitは使いません）。キーの欠落はAndroid lintの`MissingTranslation`で`lintDebug`が失敗します。

エミュレーターは状態遷移とCompose UIの継続検証に使います。カメラの読み取り完了判定はPixelなどの実Android端末で行います。実端末で行う確認項目、証跡、未実施の扱いは [実機確認ランブック](../docs/android/REAL_DEVICE_RUNBOOK.md) に従ってください。現時点では、このREADMEや自動テストの結果だけでQR/Code 128の実読取、focus、連続箱、BLE通信の成功を宣言しません。

GitHub ActionsのemulatorテストはPixel 7と同じAPI 36で実行し、`android/**`または`shared/**`の変更時だけ動きます。compile/target SDK 37はbuild jobで保証します。

## 現在の検証境界

到達点と打ち切った確認項目は [Android版の到達点](../docs/android/STATUS.md) にまとめています。プライバシー・権限・backup・FileProviderの境界は [Android版プライバシー境界](../docs/android/PRIVACY.md) を正本とします。公式SDKの固定version、ABI、権限、rawログ対策、scan callbackの評価は [Android BLE SDK評価メモ](../docs/android/BLE_SDK_EVALUATION.md) に記録しています。ライセンスが明示されていないためbinaryはGitへ含めず、生成したAPKも配付しません。

## Fake scannerの境界

Fake scannerは`scanner/fake`へ置き、`app`からは`debugImplementation`だけで参照します。`releaseImplementation`や`implementation`では参照しないため、リリース依存グラフとAPKにFake入口を含めない構成です。CIの`android-release-build` jobがこの境界を確認します。

`scanner/ble`には、command直列化、timeout後の停止、完全設定snapshot、復元前Ready禁止、payload正規化と重複抑制を置いています。`scanner/inateck`は公式2.0.0 SDKのscan/connect/getSettingInfo/setSettingInfoと公式native通知parserをこの安全コアへ接続し、FF01の設定応答と分割scan通知を単一ルーターで分離します。`release`は`BLUETOOTH_SCAN`（neverForLocation）と`BLUETOOTH_CONNECT`だけを要求し、legacy Bluetooth・位置情報・広告・ネットワーク権限を`tools:node="remove"`で除外します（`app/src/release/AndroidManifest.xml`）。minified releaseはSDKのLog/System.out呼び出しを除去します。BLE診断は直近1,000件を保持し、設定画面の「接続診断」から共有シート（Quick Share・メール等）またはSAFの保存先へテキストとして書き出せます（段階名のみ、読取値は含まない）。BLE診断は直近1,000件を保持し、設定画面の「接続診断」から共有シート（Quick Share・メール等）またはSAFの保存先へテキストとして書き出せます（段階名のみ、読取値は含まない）。接続ごとに照明（`lighting_lamp_control`=2）を適用した後、読取チューニング（`qrcode_read_more_code`など多コード=0、`read_inverse_color`など反転=0、`auto_close_mode`=20＝赤光約4秒）をinventoryと比較し、差分がある項目だけを書いて再取得で確認します（name/flag対応表は`docs/ios/IMPLEMENTATION_GUIDE.md`）。Pixel 7 / BCST-36ではQR→Code 128一致、背景復元、active session中のapp force-stop後の既知端末自動再接続・Ready復帰、手動切断・電源OFF後の再接続、`release` APKでの照合完了まで実機確認済みです。重複・連続箱・予期しない切断・scanner再起動・timeout・Samsung等の残るゲートは手元利用専用の方針（Issue #57）で打ち切りとし、一覧は[`docs/android/STATUS.md`](../docs/android/STATUS.md)にあります。
