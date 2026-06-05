# Horizon OS UVC → PC マルチストリーミング

Meta Horizon OS 2.x（Meta Quest）上で、UVC（USB Video Class）カメラに **USB Host API + 自前 usbdevfs(usbfs) ioctl（JNI）** 経由でアクセスし、取得した MJPEG フレームを **HTTP/MJPEG で PC へストリーミング** する単機能アプリです。**最大3台のカメラを同時配信**でき、カメラごとに**解像度をドロップダウンで選択**、**CPU 使用率**も表示します。

> ⚠️ **これは動作確認用の開発者／研究向けツールです。一般配布・ストア公開は想定していません。**
> UVC アクセスのゲートである `horizonos.permission.USB_CAMERA` は **Horizon OS アプリストアでは許可されない**ため、
> この方式で UVC を扱えるのは **開発者モード／サイドロード限定の特権**として成立しているものです。
> 「開発機で動くこと」と「ストアで配布できること」は別問題である点に注意してください。

## 検証結果（UVC アクセス可否マトリクス）

Horizon OS 2.x（Quest, 開発者モード）で実機検証した結果は以下のとおりです。

| 経路 / 権限 | 結果 |
|---|---|
| Camera2 API（標準カメラ枠組み） | ⛔ EXTERNAL カメラとして列挙されない／open 不可 |
| Camera1 API（旧 `android.hardware.Camera`） | ⛔ UVC は列挙されない（同じカメラ HAL 上） |
| V4L2 ノード（`/dev/video*` 直接 open） | ⛔ EACCES／ノード無し（uvcvideo 未バインド） |
| Java USB Host API（native なし、bulk/control のみ） | ⚠️ 映像不可（iso 転送 API が存在しない＝Android 仕様の制限） |
| 生 USB（USB Host API + 自前 usbdevfs ioctl/JNI）+ `USB_CAMERA` 権限あり | ✅ 列挙→open→fd→claim→control→iso URB reap まで通る |
| 生 USB + `USB_CAMERA` 権限なし | ⛔ open が弾かれる |
| 生 USB + `CAMERA` 権限なし・`USB_CAMERA` 権限あり | ✅ 可 |

**唯一の動作経路:** `fd + 自前 native(usbdevfs ioctl) + USB_CAMERA 権限`。これ以外（Camera2 / Camera1 / V4L2 / 純 Java USB）はすべて不可で、UVC ペイロードの自前パースは回避できません。

**補足:** `forceClaim` が `force=false` で成功する＝`uvcvideo` カーネルドライバが未バインドのため、Camera2/HAL はこのデバイスを管理せず、その隙間を生 USB（usbdevfs）で直接叩ける構図です。`android.permission.CAMERA` は不要です。

## 動作の流れ

1. デバイスを列挙し、UVC 候補に接続（`USB_CAMERA` 権限を要求）。**複数台を順次接続できる**
2. カメラごとに fd 単位の独立セッション（[`CameraSession`]）を生成し、**8090 から昇順で専用ポートを割り当て**
3. 接続後、`UvcControlNegotiator` が UVC の probe/commit で選択解像度のフォーマットを確定
4. native（usbdevfs）で **fd ごとに独立した iso ストリーム**を開始し、URB を reap → MJPEG フレームを組み立て
5. 各カメラ専用の `MjpegStreamServer`（HTTP）が PC へ multipart MJPEG を配信
6. 本体プレビューはカメラごとにトグルで表示 ON/OFF（OFF でも配信は継続）。CPU 使用率を画面上部に表示

## 使い方

1. Quest を**開発者モード**にして PC と接続
2. ビルド & インストール
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   .\gradlew.bat installDebug
   ```
3. UVC カメラを Quest に OTG 接続（複数台は USB ハブ経由）
4. アプリを起動し、`USB_CAMERA` 権限を許可
5. デバイス一覧から接続（最大3台）→ 各カメラパネルで**解像度を選択**→ **配信開始**
6. 同一ネットワークの PC のブラウザ/VLC 等で各カメラの URL を開く（ポートはパネルに表示）
   - 1台目: `http://<Quest の IP>:8090/mjpeg`、2台目: `:8091/mjpeg`、3台目: `:8092/mjpeg`
   - スナップショット / ステータス: 各ポートの `/snapshot.jpg` / `/status`

詳細な診断ログは `adb logcat -s UsbDiag UsbDiagNative` で確認できます。

## 必要権限

- `horizonos.permission.USB_CAMERA`（**必須**。UVC アクセスの唯一のゲート）
- `android.permission.CAMERA` は **不要**

## 既知の制約・注意

- **ストア配布は不可**（`USB_CAMERA` がストアで許可されないため）。サイドロード／開発者／研究／kiosk 用途に限定。
- iso 転送は Java USB API に存在しないため、native（usbdevfs ioctl）実装が必須。
- UVC ペイロードヘッダの除去・MJPEG フレーム境界の検出は自前で行う。
- **多台同時配信の最大ボトルネックは USB バス帯域**（全カメラで共有）。高解像度×複数台は帯域飽和しやすいので、解像度ドロップダウンで台数に応じて下げる。
- **本体プレビューの JPEG デコードが CPU 的に最も重い**。負荷時はプレビューを OFF にする（配信は継続）。CPU% 表示で確認できる。

## プロジェクト構成（主要部）

- `usb/UsbRepository.kt` — USB 列挙／権限／接続のコーディネータ。複数 `CameraSession` とポート割当・CPU モニタを束ねる
- `usb/CameraSession.kt` — カメラ 1 台ぶんのキャプチャループ＋専用ポートの配信サーバ＋フォーマット選択
- `usb/CpuMonitor.kt` — `/proc/self/stat` による自プロセス CPU% 算出
- `usb/UvcNative.kt` + `cpp/native-lib.cpp` — usbdevfs ioctl による iso URB の submit/reap（**fd キーのマルチセッション**, JNI）
- `usb/UvcControlNegotiator.kt` — UVC probe/commit のネゴシエーション
- `usb/UvcPayloadParser.kt` — iso パケット列 → MJPEG フレーム組み立て
- `usb/DescriptorParser.kt` — USB ディスクリプタ解析とフォーマット抽出
- `usb/MjpegStreamServer.kt` — HTTP/MJPEG 配信サーバ（カメラごとに 1 インスタンス）
- `MainActivity.kt` / `ui/CameraPanel.kt` — 単一画面 UI（デバイス一覧＋カメラパネルの横並び）

## ドキュメント

- [Horizon OS アプリ開発ドキュメント](https://developers.meta.com/horizon/documentation/android-apps/horizon-os-apps)

## ライセンス

MIT License（[`LICENSE`](LICENSE)）。本プロジェクトは Meta の「Horizon OS Template」
（MIT License）を起点に作成されており、テンプレート由来部分の帰属は [`NOTICE`](NOTICE) に
明記しています。

## 商標について

Meta、Quest、Horizon OS（Meta Horizon OS）は Meta Platforms, Inc. の商標です。
本プロジェクトは Meta Platforms, Inc. とは**無関係であり、同社による公認・後援を受けていません**。
リポジトリ名・ドキュメント中のこれらの名称は、対応プラットフォームを示すための記述的な使用です。
