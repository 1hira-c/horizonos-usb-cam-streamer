# horizonos-usb-cam-streamer — エージェント向けガイド

**Meta Horizon OS（Quest）** 向けの単機能 Android アプリ。**UVC（USB Video Class）カメラ**に
USB Host API ＋ **自前の usbdevfs/usbfs ioctl レイヤ（JNI）** 経由でアクセスし、取得した
**MJPEG フレームを HTTP/MJPEG で PC へストリーミング**する。**最大3台同時配信**、カメラごとの
解像度選択、CPU 使用率のリアルタイム表示に対応。

> ⚠️ 開発者/研究向けツール。**ストア配布は不可** — `horizonos.permission.USB_CAMERA` は
> Horizon OS ストアでは付与されず、この UVC 経路は開発者モード/サイドロードでのみ成立する。
> UVC アクセス可否マトリクスの詳細は `README.md` を参照。

## なぜこの方式なのか（唯一の動作経路）

Horizon OS の UVC は Camera2 / Camera1 / V4L2（`/dev/video*`）/ 純 Java USB（iso 転送 API 無し）
では扱えない。**唯一**動作する経路は `fd + 自前 usbdevfs ioctl + horizonos.permission.USB_CAMERA`。
`android.permission.CAMERA` は不要。iso 転送と UVC ペイロードのパースは native 側で自前実装する
必要があり、これがアーキテクチャ全体を規定する中心的な制約になっている。

## ビルドとデプロイ

前提: JDK 17、Android Studio、Android NDK、開発者モードの Quest。min/target SDK 34、
Horizon OS SDK 69、NDK ABI `arm64-v8a`。

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat clean assembleDebug      # ビルド（Kotlin + CMake/NDK）
.\gradlew.bat testDebugUnitTest        # ユニットテスト
.\gradlew.bat installDebug             # 接続中の Quest へインストール
```

診断ログ: `adb logcat -s UsbDiag UsbDiagNative`

## アーキテクチャ（メインソース: `app/src/main/java/jp/hitohira/usbcamstreamer/`）

| ファイル | 役割 |
|---|---|
| `usb/UsbRepository.kt` | USB 列挙・権限・接続のコーディネータ。複数の `CameraSession`、ポート割当、CPU モニタを束ねる |
| `usb/CameraSession.kt` | カメラ1台＝fd 単位のセッション。キャプチャループ＋専用配信サーバ＋フォーマット選択 |
| `usb/UvcNative.kt` + `cpp/native-lib.cpp` | usbdevfs ioctl による iso URB の submit/reap（**fd キーのマルチセッション**, JNI）。native lib `uvcnative` をロード |
| `usb/UvcControlNegotiator.kt` | UVC probe/commit ネゴシエーションで選択フォーマットを確定 |
| `usb/UvcPayloadParser.kt` | iso パケット列 → MJPEG フレーム組み立て |
| `usb/DescriptorParser.kt` | USB ディスクリプタ解析とフォーマット抽出 |
| `usb/MjpegStreamServer.kt` | HTTP/MJPEG 配信サーバ（カメラごとに1インスタンス） |
| `usb/CpuMonitor.kt` | `/proc/self/stat` による自プロセス CPU% 算出 |
| `usb/CameraModels.kt` | UI/状態のデータクラス（`CameraUiState`, `FormatOption`, `CpuStats`, `IsoInCandidate`） |
| `usb/UsbModels.kt` | USB デバイスのモデル（`DeviceSummary` など） |
| `usb/BlockDiagnostics.kt` | 接続/ブロックの診断 |
| `MainActivity.kt`, `ui/CameraPanel.kt`, `ui/DeviceListScreen.kt` | 単一画面の Compose UI（デバイス一覧＋カメラパネルの横並び） |

### 実行フロー

1. USB デバイスを列挙し、UVC 候補に接続（`USB_CAMERA` 権限を要求）。複数台を順次接続できる。
2. 各カメラに fd 単位の `CameraSession` を生成し、**8090 から昇順で専用ポート**を割り当て（8090, 8091, 8092）。
3. `UvcControlNegotiator` が選択解像度で UVC probe/commit を実行。
4. native（`uvcnative`）が **fd ごとに独立した iso ストリーム**を開始し、URB を reap → MJPEG フレームを組み立て。
5. 各カメラの `MjpegStreamServer` が PC へ multipart MJPEG を配信（`/mjpeg`、加えて `/snapshot.jpg`・`/status`）。
6. 本体プレビューはカメラごとにトグル（OFF でも配信は継続）。CPU% を表示。

## ⚠️ 注意点 / 同期が必要なもの

- **JNI シンボル名は package ＋ クラス名に連動。** `cpp/native-lib.cpp` の6関数は
  `Java_jp_hitohira_usbcamstreamer_usb_UvcNative_<method>` という名前。package や `UvcNative`
  を改名したら**全 JNI シンボルを再生成**すること（JNI のエスケープ規則: `.`→`_`、リテラルの
  `_`→`_1`）。不一致は実行時に `UnsatisfiedLinkError` として初めて表面化する。
- **native lib 名を一致させる。** `System.loadLibrary("uvcnative")` ↔ `CMakeLists.txt` の
  `project(uvcnative)` / `add_library(uvcnative …)`。
- **多台同時配信のボトルネックは共有の USB バス帯域。** 台数が増えたら解像度ドロップダウンで下げる。
- **本体プレビューの JPEG デコードが最も CPU を食う。** 負荷時はプレビューを OFF に（配信は継続）。
  CPU% 表示で確認できる。

## 主な依存

`com.meta.spatial:meta-spatial-sdk-uiset`（UISet テーマ: `SpatialTheme` 等）、Jetpack Compose、
AndroidX。バージョンは `gradle/libs.versions.toml` で管理。

## ライセンス・商標

MIT（`LICENSE`）。本プロジェクトは Meta の Horizon OS Template を起点に作成しており、テンプレ由来
部分の帰属は `NOTICE` に明記。Meta / Quest / Horizon OS は Meta Platforms, Inc. の商標で、本
プロジェクトは同社とは無関係・非公認（名称は対応プラットフォームを示す記述的使用）。
