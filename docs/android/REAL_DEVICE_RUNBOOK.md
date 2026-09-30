# Android実機確認ランブック

この手順は、エミュレーターやCIでは代替できないAndroid端末の確認を同じ条件で再実行するためのものです。実機確認済みと記録できるのは、実際に接続した端末で該当項目を完了し、証跡を保存した項目だけです。

2026-09-05の判断（Issue #57）で、Android版は手元利用専用（Pixel 7 / BCST-36、ストア提出なし）とし、各節に「対象外（打ち切り）」と記した項目はこれ以上実施しません。打ち切りは検証成功を意味しません。到達点と打ち切り項目の一覧は[`STATUS.md`](STATUS.md)を正本とし、この文書は手順と実施記録を残します。

## 0. 対象と前提

- Android 12（API 31）以上。対象はPixel 7。Samsung系は対象外（打ち切り）。
- USBデバッグを有効にした実端末。カメラ付き端末を使う。
- リポジトリの `shared/test-fixtures/images/` を端末とは別の表示端末または紙に表示する。実ラベルを使う場合は、値をログやスクリーンショットへ残さない。
- `android/` は独立Gradleプロジェクト。JDK 21とAndroid SDK 37を用意する。
- `release`はカメラ入力に加えて公式Inateck SDKによるBLE読取を同梱する（#56）。`scannerPoc` build typeは廃止した。

端末情報、OS/API、アプリversion、ビルドcommit、実施日時、テスト結果、未実施理由を記録します。失敗時のログにはpayload、カメラ画像、端末アカウント情報を含めないでください。

## 1. ビルドとインストール

```sh
cd android
./gradlew assembleDebug
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

テスト用端末で既存データの影響を除いて再確認する場合だけ、端末を確認してからアプリのデータを消去し、再インストールします。業務端末では実行しないでください。

```sh
adb shell pm clear jp.rimtty.codematch
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

自動化されたドメイン・Compose・Room検査も先に実行します。

```sh
./gradlew testDebugUnitTest lintDebug
bash scripts/run-connected-tests.sh
```

### PDF保存・共有の自動証拠と手動境界

自動testは、長い履歴からA4複数ページPDFを生成し、全ページに描画内容があること、共有用ファイルが専用`cache/codematch-pdf/`配下だけへ作られることを確認します。さらに`CreateDocument(application/pdf)`、保存byteの完全一致、`ACTION_SEND`、PDF MIME、ClipData、read URI grant、FileProvider URIを検査します。

これは実際の保存先や共有先アプリの受け入れを意味しません。最終実機確認では、1件と複数ページになる履歴について保存先を選び、保存したPDFを端末のviewerで開き、共有シートから少なくとも1つの受け取り先へ渡せることを確認します。履歴内容やpayloadをスクリーンショット・外部ログへ残さず、検証用データだけを使用してください。

### 通常debugアプリでのinstrumentation

通常のdebugアプリは製品と同じapplication IDを使うため、業務端末で`:app:connectedDebugAndroidTest`を実行すると既定のRoom/DataStoreが消去されます。実機ではlibrary module（`feature:*`、`core:*`、`scanner:camera`、`scanner:ble`）のテストだけを実行し、`:app`のinstrumentationはCIのemulatorに任せます。scan checkpointの復元はRoom/DataStore再オープンと`ScanViewModel`再生成で自動検査済みで、OS force-stopを伴う専用runnerは廃止しました。

## 2. カメラ受け入れゲート（M3）

### 権限とライフサイクル

1. 初回起動で日本語表示を確認し、照合セッションを開始する。
2. カメラ権限を一度拒否し、画面に回復案内が出てクラッシュしないことを確認する。
3. 再試行で権限を許可し、CameraX previewが開始することを確認する。
4. 「今後表示しない」相当の拒否では、設定を開く導線を確認する。
5. カメラ待機中にアプリを背景化→復帰し、previewと論理工程が二重にならず再開することを確認する。
6. QR待機中、Code 128待機中、結果表示中に画面回転を行い、工程と表示が失われないことを確認する。

### 実読取と業務フロー

1. QR fixtureを正方形ガイド内で読む。QR工程から進み、Code 128以外では進まないことを確認する。
2. Code 128 fixtureを横長ガイド内で読む。同値を連続して読み、一致結果になることを確認する。
3. 品番違いのCode 128を読み、不一致表示になり履歴・件数へ保存されないことを確認する。
4. 一致した同一品番を連続して2箱以上読み、箱数と履歴詳細が増えることを確認する。
5. Code 128待機中に「QRを読み取りなおす」を押し、既存件数を変えずQR工程へ戻ることを確認する。
6. 結果表示中の手動「次の照合」が、設定に関係なく使えることを確認する。
7. 照合中にpreviewをタップし、タップ位置へのfocus要求が発生することを端末上で確認する。focusの成否は照明・端末差があるため、端末名と結果を記録する。

### 記録すべき証跡

- 端末メーカー/モデル、Android version/API、背面カメラ、アプリversion/commit。
- 権限拒否・再要求・背景復帰・回転の各結果。
- QR→Code 128、一致、不一致、連続箱、読み直し、手動次工程の結果。
- カメラ開始/停止と工程表示のスクリーンショット。payloadやカメラ画像そのものは保存しない。
- 失敗時は再現手順、時刻、端末状態、payloadを含まないlogcat抜粋。

Pixel 7での実施結果は本文末尾の実施記録を参照してください。画面回転中の工程保持、連続箱の実ラベル確認、Samsungでの受入は対象外（打ち切り）です。

## 3. アクセシビリティと適応レイアウト

端末の設定を一つずつ変更し、変更後に照合・履歴・設定の主要操作へ到達できることを確認します。

1. TalkBackを有効にし、トップレベル移動、Stepperの現在工程、結果、一致件数、終了、手動次工程を読み上げる。
2. カメラ枠の説明と、座標を持たない利用者向けの中央focusアクションが読み上げられることを確認する。
3. 設定でSwitch、遅延選択、音選択、試聴、言語、scanner行が操作でき、選択状態が色だけでなく文言/roleで分かることを確認する。
4. 画面フォント倍率を1.0、1.3、2.0相当に変更し、主要ボタン、結果、戻る操作、保存・共有へスクロールで到達する。
5. compact phone、横長またはfoldable相当、tablet幅で履歴を開き、狭幅では一覧→詳細、広幅では一覧と詳細の同時表示になることを確認する。
6. Switch Accessまたはキーボード/D-padで、カメラ以外の主要操作を順番に実行する。

Compose側には、主要画面のsemanticsと最小タッチ領域、履歴のcompact/expanded表示を検査するUI testがあります。フォント倍率1.3/2.0はPixel 7でユーザーが承認済みです。手順1・2・6のTalkBack、Switch Access、Accessibility Scannerは対象外（打ち切り）です。

## 4. プライバシーとrelease確認

実機確認用debug APKと配布候補release artifactを混同しないでください。release検証は端末へインストールする前に行います。

```sh
./gradlew :app:assembleRelease
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > /tmp/codematch-release-dependencies.txt
bash scripts/verify-release-hardening.sh --dependency-report /tmp/codematch-release-dependencies.txt
```

確認する境界は次のとおりです。

- release Manifest/APK/AABに `INTERNET`、`ACCESS_NETWORK_STATE`、legacy Bluetooth、位置情報、広告権限がなく、Nearby権限は`BLUETOOTH_SCAN`（neverForLocation）と`BLUETOOTH_CONNECT`だけである。
- releaseにFake scanner、debug demo、診断用payloadがない。
- カメラ画像/frame、不一致値、scan payloadをファイル・ログ・analytics/crash SDKへ書き出さない。
- Room/DataStoreとBLE復旧状態がcloud backupとdevice-to-device transferから除外される。
- FileProviderは専用 `cache/codematch-pdf/` だけを一時共有する。

checker通過は静的・artifact証拠です。パケット監査による無通信の最終確認とストア向け回答は対象外（打ち切り）です。依存ライブラリを変更した時はcheckerと[`PRIVACY.md`](PRIVACY.md)を再確認してください。

## 5. BLE実機ゲート（M4、release）

公式SDK binaryを固定commitからローカル取得し、SHA-256を検証したうえで`release`を組み立ててPixelへ入れます。SDK binaryとAPKは配付しません（手元利用のみ）。

```sh
cd android
bash scripts/setup-inateck-sdk.sh
./gradlew :app:assembleRelease
bash scripts/verify-release-hardening.sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

設定画面が生成する3つのCode 128は、自動instrumentationで同梱ML Kitから `/*EnterSet*/` → `/*BLE_GATT*/` → `/*ExitSave*/` の順にexact decodeできることを確認済みです。これは画像生成と復号の証拠であり、対象BCST-36が実際に読み取って設定を変更・保存した証拠ではありません。

以下は当初の受入項目です。Pixel 7 / BCST-36で実施済みの範囲は本節の実施記録と[`STATUS.md`](STATUS.md)の到達点、未実施のまま打ち切った範囲は同ページの「打ち切った確認項目」を参照してください。

1. Android 12以降のPixel 7で、必要時だけNearby devices権限を要求する（Samsung系は対象外）。
2. 初回設定ガイドの3コードを順に実機scannerで読み、GATT modeが保存されたことを確認してから、検索、接続、pairing、scan通知、切断、既知端末再接続を確認する。
3. QR→Code 128の一致、不一致、逆順拒否、重複callback抑止、連続箱を確認する。
4. 接続前のscanner報告を全symbology保存し、QR+Code 128固定mode後、終了・背景・切断・再接続で完全復元する。
5. timeout後にGATT commandが重ならず、安全にカメラへfallbackすることを確認する。
6. BLE→camera→BLEで工程と読み取り済みQRを維持し、scan payloadが診断やlogcatへ出ないことを確認する。

2026-09-05に`release` APK（#56）でBCST-36と接続し、QR→Code 128の照合完了をユーザーが確認しました。残る不一致・重複・連続箱、予期しない切断、scanner再起動、timeout/fallback、Code 128待機/結果表示中のforce-stopは対象外（打ち切り）です。

### 2026-09-04 部分実施記録（当時は非配付`scannerPoc`で実施）

- 端末: Google Pixel 7、Android 16 / API 36、USB接続
- scanner: Inateck BCST-36、GATT mode
- build: 非配付`scannerPoc`、通常releaseとは別application ID
- 成功: SDK検索・接続、全barcode symbology inventory取得、QR+Code 128 session制限とfresh readback、分割QR通知、分割Code 128通知、同一品番の一致、背景移行時の開始前設定復元とReady復帰、QR待機中のOS force-stop→再起動後の既知端末自動再接続とReady復帰、その後のQR→Code 128一致と次のQR待機への遷移
- privacy: 段階ログにpayload、raw frame、設定値、device IDが含まれないことを確認
- 未実施: 不一致、同一箱重複、異なる箱の連続照合、手動/予期しない切断、scanner再起動、Code 128待機/結果表示中のforce-stop、timeout/fallback、firmware revision記録、Samsung

この記録はPixel 7 / BCST-36の上記項目だけの実機証拠です。未実施項目のうち、その後に実施したものは「2026-09-05 追加受入記録」、残りは打ち切りとして[`STATUS.md`](STATUS.md)に一覧化しています。

### 2026-09-04 カメラ部分実施記録

- 端末: Google Pixel 7、Android 16 / API 36、縦画面
- build: 通常AndroidアプリのCameraX / bundled ML Kit経路
- 成功: 実際に使われるラベルのQRを正方形ガイド内で読み、続いて同じラベルのCode 128を横長ガイド内で読み、一致まで完了
- 調整: preview全体ではなく白いガイド枠内だけを工程別ROIとして解析し、縦画面で不要な上下画素を入力へ含めない構成へ変更後に合格
- 未実施: 不一致、連続箱、QR読み直し、手動/自動次工程、tap focus、回転、背景復帰、Samsung

この記録は上記のQR→Code 128一致だけの実カメラ証拠です。不一致・QR読み直し・tap focus・背景復帰・権限は「2026-09-05 追加受入記録」で承認済み、回転・連続箱・Samsungは対象外（打ち切り）です。

### 2026-09-04 PDF保存・共有の部分実施記録

- 端末: Google Pixel 7、Android 16 / API 36、USB接続
- build: 非配付`scannerPoc`の履歴詳細画面（PDF経路は通常アプリと共通）
- 成功: 履歴1件から「PDFで保存」を選び、Android DocumentsUIでDownloadsへ保存してアプリへ復帰。保存されたファイルは空ではなく、PDF headerを持つことを確認した
- 共有: 「共有する」からAndroidの共有先選択画面が開くことを確認した
- 未実施: 保存PDFを外部viewerで開いて内容・複数ページを確認、実際の共有先アプリへ受け渡し、通常debug/release buildでの再確認

この記録はDocumentProvider保存と共有画面起動の部分証拠です。外部viewerと共有先での表示は「2026-09-05 追加受入記録」で承認済みで、全viewer互換と実共有先アプリ側の受け取り確認は対象外（打ち切り）です。

## 2026-09-05 追加受入記録（Pixel 7限定）

- 対象: Pixel 7 / Android 16（API 36）、非配布`scannerPoc`。ユーザー指定でBCST-36とのローカル運用に限定し、Samsung・ストア配布は対象外。iOSは変更していない。
- ユーザー実機承認: カメラ復帰後のCode128、タップfocus、OS設定からのカメラ権限拒否・再許可、ガイド枠内外の読取境界、縦向き固定、4:3枠内のプレビュー（PR #41）。
- ユーザー実機承認: 日英切替、英語Historyの箱数表示と下部ナビ選択表示（PR #42）。PDFは1ページ・複数ページの最終ページまでの表示、Downloads保存、共有先での表示を確認済み。上記の古いPDF部分証拠に対する追加確認であり、全viewerへの対応保証ではない。
- ユーザー実機承認: 音量0で無音かつ一致時に振動、通常音量で読取短音と一致通知音。無効・不一致通知の体感確認は別途残る。
- ユーザー実機承認: font_scale=1.3で主要表示・操作、結果画面の品番ラベルと値の縦配置（PR #43）。
- 自動確認: PR #43のfeature単位のPixel connected testはScan font scale 3件、Settings 1件、History 3件が失敗・skipなし。これは実TalkBack/Switch Accessの証拠ではない。製品アプリのfont_scaleを一時2.0で確認後、元の1.3へ戻してreadbackした。
- 自動確認: PR #43統合相当のインストール済みアプリで、初期状態の閉じた設定ガイドを開き、AndroidシステムBackで閉じることをUI hierarchyで確認。スキャン開始画面へ戻した。製品アプリの履歴・設定の消去は実施していない。
- 自動確認時の表示所見: ガイドの旧機種名BCST-47を今回の対象BCST-36へ日英とも訂正。この記録はスキャナーで設定コードを実読取した証拠ではない。
- 追加完了（PR #44以降、#46までのscannerPoc）: カメラの権限ダイアログを2回拒否し、恒久拒否表示・設定導線をADB UI操作で確認。許可とフラグは事前状態へ復元した。QR待ち・Code128待ち・一致結果のforce-stop/COLD起動復元も確認し、Code128続行および結果2件保持・次工程で二重加算なしをユーザーが承認した。
- 追加完了（PR #46 / 2f35282）: 無関係なQRを拒否しQR待ちを保持、無効QRの警告音・振動・案内表示をユーザーが承認。不一致表示・音・振動・非加算も承認済み。font scale 2.0のQR→Code128→一致→次工程、履歴詳細・設定の表示/スクロールをユーザーが承認し、元の1.3へ復元した。
- 通信の限定観測: Pixelの当該アプリUIDのnetstatsに、カメラプレビュー稼働中および通常操作・読取受入後も通信量エントリなし。インストール済みPoCにINTERNET権限なし。パケット監査や他プロセスを含む全経路の無通信保証ではない。
- 対象外へ変更: TalkBackとSwitch Accessはユーザー指定で今回の受入から省略。TalkBackの一時有効化は元の無効状態へ復元済み。合格を意味しない。
- BLE追加確認（PR #47 / 34d70ed）: 手動切断後もCode128待ち・1件を保持し再接続後の読取続行成功。さらに3分30秒以上電源OFFで待機後、電源ONのみで自動再接続しCode128から続行をユーザーが承認。これは全symbologyの完全復元や異常タイムアウトを直接検証した証拠ではない。
- 残り: BLEの完全復元・timeout異常系・firmware記録はIssue #19で継続していたが、2026-09-05のIssue #57で打ち切り。BCST-36は検証端末であり、SDK対応端末の接続対象を限定しない。
- 2026-09-05（#56）: `scannerPoc`を廃止し、SDKを同梱した`release` APKをPixel 7へ導入。BCST-36と接続し、QR→Code 128の照合完了をユーザーが確認した。
- 追跡: [Issue #23](https://github.com/Miquottty/code-match-native/issues/23)、[Issue #19](https://github.com/Miquottty/code-match-native/issues/19)。ユーザー承認と自動確認を区別し、未実施項目は完了扱いにしない。

## 6. 証跡テンプレート

```text
実施日:
実施者:
端末（メーカー/モデル）:
Android version / API:
アプリ version / commit:
カメラ権限（拒否・再要求・設定）:
QR → Code 128 実読取:
一致 / 不一致 / 連続箱 / 読み直し:
背景復帰 / 回転 / focus:
TalkBack / font scale / Switch Access:
compact / expanded レイアウト:
PDF保存先 / viewer / 共有先:
release hardening checker:
未実施項目と理由:
添付証跡（payloadを含めない）:
```
