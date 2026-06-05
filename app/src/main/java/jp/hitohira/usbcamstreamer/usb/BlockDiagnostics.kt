package jp.hitohira.usbcamstreamer.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.Build

/**
 * 「Horizon OS が UVC アクセスをどの層で塞いでいるか」を切り分けるための診断ロジック。
 *
 * 各段を try/catch で包んで成否と例外メッセージを [emit] に流す。これにより
 *   列挙 → 権限 → openDevice → getFileDescriptor → rawDescriptors → forceClaim → control転送
 * のどこで失敗するかが一目で分かる。結果はアプリ内 UI と logcat(TAG=UsbDiag) の両方に出す。
 *
 * 切り分けの読み方:
 *  - 列挙にすら出ない        → システムレベルでブロック / USB host 無効。ソフト単独では不可。
 *  - open まで成功するが iso 不可 → 映像"フレームワーク"のみ遮断。libusb 自前ドライバ(Phase 2)で回避の望み。
 *  - control/bulk まで通る     → 生 USB は開いている。iso だけが鬼門 → Phase 2 へ。
 */
object BlockDiagnostics {

    /** (name, status, detail) を受け取るコールバック。 */
    fun interface Emit {
        operator fun invoke(name: String, status: DiagStatus, detail: String)
    }

    /** 端末・OS 環境を記録（どの Horizon OS ビルドで試したかを必ず残す）。 */
    fun captureEnvironment(emit: Emit) {
        emit("環境/Android", DiagStatus.INFO, "SDK_INT=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        emit("環境/機種", DiagStatus.INFO, "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        emit("環境/ビルド", DiagStatus.INFO, "display=${Build.DISPLAY} id=${Build.ID}")
        // Meta/Horizon OS 固有のバージョンプロパティを取れるだけ拾う（取れなければ N/A）。
        val props = listOf(
            "ro.build.version.incremental",
            "ro.vros.build.version",          // Horizon OS のバージョン系プロパティ候補
            "ro.product.vros.version",
            "ro.oculus.build.version",
            "ro.product.brand",
        )
        for (p in props) {
            val v = systemProperty(p)
            if (v.isNotBlank()) emit("環境/$p", DiagStatus.INFO, v)
        }
        if (Build.FINGERPRINT.isNotBlank()) emit("環境/fingerprint", DiagStatus.INFO, Build.FINGERPRINT)
    }

    /**
     * open 済みの接続に対して、生 USB がどこまで使えるかを診断する。
     * （列挙/権限/open の成否は [UsbRepository] 側で別途記録する。）
     */
    fun runOnConnection(device: UsbDevice, conn: UsbDeviceConnection, emit: Emit) {
        // 1) ファイルディスクリプタ（Phase 2 で libusb に渡す値）
        runStep(emit, "getFileDescriptor") {
            val fd = conn.fileDescriptor
            if (fd >= 0) DiagStatus.PASS to "fd=$fd（Phase 2 の libusb_wrap_sys_device に渡せる）"
            else DiagStatus.FAIL to "fd=$fd（無効）"
        }

        // 2) rawDescriptors（クラス判定の根拠＝ここが取れれば素のディスクリプタは読める）
        runStep(emit, "rawDescriptors") {
            val raw = conn.rawDescriptors
            if (raw != null && raw.isNotEmpty()) {
                val parsed = DescriptorParser.parse(raw)
                val video = parsed.videoStreamingInterfaces.isNotEmpty() || parsed.videoControlInterfaces.isNotEmpty()
                DiagStatus.PASS to "${raw.size}B / VS=${parsed.videoStreamingInterfaces} VC=${parsed.videoControlInterfaces} video=$video formats=${parsed.formats}"
            } else {
                DiagStatus.FAIL to "rawDescriptors が null/空"
            }
        }

        // 3) エンドポイント内訳（iso が主役なら Java API では映像不可＝Phase 2 必須）
        runStep(emit, "endpoints") {
            var ctrl = 0; var iso = 0; var bulk = 0; var intr = 0
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                for (e in 0 until intf.endpointCount) when (intf.getEndpoint(e).type) {
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> ctrl++
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> iso++
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> bulk++
                    UsbConstants.USB_ENDPOINT_XFER_INT -> intr++
                }
            }
            val st = if (iso > 0) DiagStatus.WARN else DiagStatus.INFO
            st to "control=$ctrl iso=$iso bulk=$bulk interrupt=$intr" +
                if (iso > 0) "（iso あり→映像は Phase 2/libusb 経路）" else ""
        }

        // 4) forceClaim（カーネルドライバ奪取＝生 USB を排他取得できるか）
        runStep(emit, "forceClaim") {
            if (device.interfaceCount == 0) return@runStep DiagStatus.WARN to "インターフェース無し"
            val intf = device.getInterface(0)
            val soft = conn.claimInterface(intf, false)
            if (soft) {
                conn.releaseInterface(intf)
                DiagStatus.PASS to "if#${intf.id}: claim(force=false) 成功（カーネル未掌握）"
            } else {
                val forced = conn.claimInterface(intf, true)
                if (forced) {
                    conn.releaseInterface(intf)
                    DiagStatus.PASS to "if#${intf.id}: claim(force=true) 成功＝カーネルから奪取できた"
                } else {
                    DiagStatus.FAIL to "if#${intf.id}: force=true でも claim 失敗（ここで遮断の可能性）"
                }
            }
        }

        // 5) 標準コントロール転送（生 USB の制御パイプが生きているか）
        runStep(emit, "controlTransfer(GET_DESCRIPTOR DEVICE)") {
            val buf = ByteArray(18)
            val n = conn.controlTransfer(0x80, 0x06, 0x01 shl 8, 0, buf, buf.size, 1000)
            if (n >= 0) DiagStatus.PASS to "${n}B [${buf.toHex(n)}]"
            else DiagStatus.FAIL to "ret=$n（制御転送が弾かれた）"
        }
    }

    // --- helpers -----------------------------------------------------------
    private inline fun runStep(emit: Emit, name: String, block: () -> Pair<DiagStatus, String>) {
        val (status, detail) = try {
            block()
        } catch (t: Throwable) {
            DiagStatus.FAIL to "${t.javaClass.simpleName}: ${t.message}"
        }
        emit(name, status, detail)
    }

    /** android.os.SystemProperties.get をリフレクションで叩く（公開 API ではないため失敗は握る）。 */
    private fun systemProperty(key: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }
}
