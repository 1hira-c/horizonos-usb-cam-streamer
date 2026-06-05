package jp.hitohira.usbcamstreamer.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Phase 2 first step: exercise UVC VideoStreaming PROBE/COMMIT over EP0.
 *
 * This does not start isochronous streaming. It only checks whether the camera accepts UVC
 * streaming negotiation from this app, which is the required control-plane step before native
 * libusb/USBDEVFS iso transfers.
 */
object UvcControlNegotiator {
    private const val TIMEOUT_MS = 1000

    private const val REQ_SET_CUR = 0x01
    private const val REQ_GET_CUR = 0x81
    private const val REQ_GET_DEF = 0x87

    private const val REQ_TYPE_CLASS_INTERFACE_IN = 0xA1
    private const val REQ_TYPE_CLASS_INTERFACE_OUT = 0x21

    private const val VS_PROBE_CONTROL = 0x01
    private const val VS_COMMIT_CONTROL = 0x02

    private const val PROBE_COMMIT_LEN = 26

    fun run(
        device: UsbDevice,
        conn: UsbDeviceConnection,
        descriptors: DescriptorParser.Result?,
        log: (LogLevel, String) -> Unit,
        requestedFormat: DescriptorParser.VideoFormat? = null,
    ) {
        val vsInterface = descriptors?.videoStreamingInterfaces?.firstOrNull()
            ?: findVideoStreamingInterface(device)
        if (vsInterface == null) {
            log(LogLevel.ERROR, "UVC probe/commit: VideoStreaming interface が見つかりません")
            return
        }

        val format = requestedFormat ?: chooseFormat(descriptors)
        if (format == null) {
            log(LogLevel.ERROR, "UVC probe/commit: VS frame format が descriptor から見つかりません")
            return
        }

        val intf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == vsInterface && it.alternateSetting == 0 }
        val claimed = intf?.let { conn.claimInterface(it, true) } ?: false
        val claimedInterface = if (claimed) intf else null
        log(
            if (claimed) LogLevel.OK else LogLevel.WARN,
            "UVC probe/commit: VS interface #$vsInterface alt0 claim=${if (claimed) "OK" else "未取得"}",
        )

        try {
            log(
                LogLevel.INFO,
                "UVC probe/commit: ${format.encoding} ${format.width}x${format.height} " +
                    "format=${format.formatIndex} frame=${format.frameIndex}",
            )

            val probe = ByteArray(PROBE_COMMIT_LEN)
            val getDef = getControl(conn, VS_PROBE_CONTROL, vsInterface, REQ_GET_DEF, probe, log)
            if (getDef < 0) {
                log(LogLevel.WARN, "GET_DEF(PROBE) 失敗。空の probe payload から試行します")
            }

            fillProbePayload(probe, format)
            val setProbe = setControl(conn, VS_PROBE_CONTROL, vsInterface, probe, log, "SET_CUR(PROBE)")
            if (setProbe < 0) return

            val currentProbe = ByteArray(PROBE_COMMIT_LEN)
            val getProbe = getControl(conn, VS_PROBE_CONTROL, vsInterface, REQ_GET_CUR, currentProbe, log)
            if (getProbe < 0) return

            val commitPayload = if (getProbe > 0) currentProbe else probe
            val setCommit = setControl(conn, VS_COMMIT_CONTROL, vsInterface, commitPayload, log, "SET_CUR(COMMIT)")
            if (setCommit < 0) return

            log(LogLevel.OK, "UVC probe/commit 完了: control plane は UVC として応答")
        } finally {
            claimedInterface?.let { conn.releaseInterface(it) }
        }
    }

    private fun chooseFormat(result: DescriptorParser.Result?): DescriptorParser.VideoFormat? {
        val formats = result?.videoFormats.orEmpty()
        return formats.firstOrNull { it.encoding == "MJPEG" && it.width <= 640 && it.height <= 480 }
            ?: formats.firstOrNull { it.encoding == "MJPEG" }
            ?: formats.firstOrNull()
    }

    private fun findVideoStreamingInterface(device: UsbDevice): Int? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == DescriptorParser.CLASS_VIDEO && intf.interfaceSubclass == 2) {
                return intf.id
            }
        }
        return null
    }

    private fun getControl(
        conn: UsbDeviceConnection,
        selector: Int,
        interfaceNumber: Int,
        request: Int,
        buffer: ByteArray,
        log: (LogLevel, String) -> Unit,
    ): Int {
        val name = when (request) {
            REQ_GET_CUR -> "GET_CUR"
            REQ_GET_DEF -> "GET_DEF"
            else -> "GET(0x%02x)".format(request)
        }
        val n = conn.controlTransfer(
            REQ_TYPE_CLASS_INTERFACE_IN,
            request,
            selector shl 8,
            interfaceNumber,
            buffer,
            buffer.size,
            TIMEOUT_MS,
        )
        if (n >= 0) {
            log(LogLevel.OK, "$name(${selectorName(selector)}) → ${n}B [${buffer.toHex(n)}]")
        } else {
            log(LogLevel.ERROR, "$name(${selectorName(selector)}) 失敗 ret=$n")
        }
        return n
    }

    private fun setControl(
        conn: UsbDeviceConnection,
        selector: Int,
        interfaceNumber: Int,
        payload: ByteArray,
        log: (LogLevel, String) -> Unit,
        name: String,
    ): Int {
        val n = conn.controlTransfer(
            REQ_TYPE_CLASS_INTERFACE_OUT,
            REQ_SET_CUR,
            selector shl 8,
            interfaceNumber,
            payload,
            payload.size,
            TIMEOUT_MS,
        )
        if (n >= 0) {
            log(LogLevel.OK, "$name → ${n}B [${payload.toHex(n)}]")
        } else {
            log(LogLevel.ERROR, "$name 失敗 ret=$n")
        }
        return n
    }

    private fun fillProbePayload(payload: ByteArray, format: DescriptorParser.VideoFormat) {
        putU16(payload, 0, 0x0001)
        payload[2] = format.formatIndex.toByte()
        payload[3] = format.frameIndex.toByte()
        putU32(payload, 4, format.defaultFrameInterval100ns.takeIf { it > 0 } ?: 333333)
        putU16(payload, 8, 0)
        putU16(payload, 10, 0)
        putU16(payload, 12, 0)
        putU16(payload, 14, 0)
        putU16(payload, 16, 0)
        putU32(payload, 18, format.maxFrameSize.takeIf { it > 0 } ?: format.width * format.height * 2)
        putU32(payload, 22, 0)
    }

    private fun putU16(buffer: ByteArray, offset: Int, value: Int) {
        if (offset + 1 >= buffer.size) return
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(buffer: ByteArray, offset: Int, value: Int) {
        if (offset + 3 >= buffer.size) return
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun selectorName(selector: Int): String = when (selector) {
        VS_PROBE_CONTROL -> "PROBE"
        VS_COMMIT_CONTROL -> "COMMIT"
        else -> "selector=0x%02x".format(selector)
    }
}
