# horizonos-usb-cam-streamer — Copilot 指示

**Meta Horizon OS（Quest）** 向けの単機能アプリ。**UVC カメラ**に USB Host API ＋ **自前の
usbdevfs ioctl レイヤ（JNI）** 経由でアクセスし、**MJPEG フレームを HTTP で PC へストリーミング**する。
最大3台同時、カメラごとの解像度選択、CPU 使用率のリアルタイム表示。

**アーキテクチャ・ビルド/デプロイ・実行フロー・注意点の詳細は `AGENTS.md` を参照。**
ここでコードを生成・編集する際の必須事項:

- 唯一動作する UVC 経路は `fd + 自前 usbdevfs ioctl + horizonos.permission.USB_CAMERA`。
  Camera2 / Camera1 / V4L2 / 純 Java USB はいずれも不可。`android.permission.CAMERA` は使わない。
- Kotlin は `app/src/main/java/jp/hitohira/usbcamstreamer/`、native は `app/src/main/cpp/`。
- **`cpp/native-lib.cpp` の JNI 関数名は package ＋ `UvcNative` クラスをエンコードしている**
  （`Java_jp_hitohira_usbcamstreamer_usb_UvcNative_*`）。package/クラスを改名したら6つの
  シンボルを再生成（エスケープ: `.`→`_`、`_`→`_1`）。不一致は実行時のみ `UnsatisfiedLinkError`
  で表面化する。
- native lib 名 `uvcnative` を `System.loadLibrary` と `CMakeLists.txt` で一致させる。
- カメラごとの配信サーバ（`MjpegStreamServer`）は 8090 から昇順のポート。
- 多台配信のボトルネックは共有 USB バス帯域。プレビューの JPEG デコードが最も CPU を食う。
- ビルド: `JAVA_HOME` を Android Studio の jbr に設定 → `.\gradlew.bat installDebug`。
  診断は `adb logcat -s UsbDiag UsbDiagNative`。
- 開発者/研究向けツール — **ストア配布不可**（`USB_CAMERA` はサイドロード限定）。
- ライセンス: MIT（`LICENSE`）。テンプレ由来の帰属・商標表記は `NOTICE` に記載。
