package jp.hitohira.usbcamstreamer.usb

/**
 * `UsbDeviceConnection.rawDescriptors` をパースして UI 表示用のツリーに変換する。
 *
 * rawDescriptors は「デバイスディスクリプタ + アクティブなコンフィグディスクリプタ
 * (内包する interface / endpoint / クラス固有ディスクリプタを含む)」が
 * 連続したバイト列で返る。各ディスクリプタは先頭が [bLength, bDescriptorType] の TLV 形式。
 *
 * ここで UVC を「特別扱い」せず、まず汎用の USB ディスクリプタとして解釈し、
 * そのうえで Video クラス (0x0E) のインターフェースやクラス固有ディスクリプタを
 * 識別してラベル付けする。これが本実験の主旨（汎用USBデバイスとして見る）に沿う。
 */
object DescriptorParser {

    // 標準ディスクリプタタイプ
    private const val DT_DEVICE = 0x01
    private const val DT_CONFIG = 0x02
    private const val DT_STRING = 0x03
    private const val DT_INTERFACE = 0x04
    private const val DT_ENDPOINT = 0x05
    private const val DT_DEVICE_QUALIFIER = 0x06
    private const val DT_INTERFACE_ASSOCIATION = 0x0B
    private const val DT_HID = 0x21
    private const val DT_CS_INTERFACE = 0x24   // class-specific interface (UVC VC/VS が使う)
    private const val DT_CS_ENDPOINT = 0x25

    // USB クラスコード
    const val CLASS_VIDEO = 0x0E
    const val CLASS_MISC = 0xEF             // IAD を使う複合デバイス (UVC は通常こちら)

    // Video サブクラス
    private const val SC_VIDEO_CONTROL = 0x01
    private const val SC_VIDEO_STREAMING = 0x02

    // VideoStreaming インターフェースの CS_INTERFACE サブタイプ
    private const val VS_INPUT_HEADER = 0x01
    private const val VS_FORMAT_UNCOMPRESSED = 0x04
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FORMAT_MJPEG = 0x06
    private const val VS_FRAME_MJPEG = 0x07

    /** ツリー(実体はフラットな深さ付きリスト)の 1 ノード。 */
    data class Node(
        val depth: Int,
        val title: String,
        val detail: String,
        val raw: ByteArray,
    )

    /** 解析結果。UI とロジックの両方で使う要約も持たせる。 */
    data class Result(
        val nodes: List<Node>,
        /** VideoStreaming インターフェース of bInterfaceNumber 一覧（forceClaim 対象候補）。 */
        val videoStreamingInterfaces: List<Int>,
        /** VideoControl インターフェース of bInterfaceNumber 一覧。 */
        val videoControlInterfaces: List<Int>,
        /** VS で見つかった対応フォーマット概要（"MJPEG 1280x720" 等）。 */
        val formats: List<String>,
        /** Phase 2 の probe/commit に使う format/frame index つきの候補。 */
        val videoFormats: List<VideoFormat>,
    )

    data class VideoFormat(
        val encoding: String,
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val defaultFrameInterval100ns: Int,
        val maxFrameSize: Int,
        /** 非圧縮フォーマットの GUID 先頭 FourCC（例 "YUY2"/"UYVY"/"NV12"）。MJPEG は ""。 */
        val fourcc: String = "",
    )

    fun parse(raw: ByteArray): Result {
        val nodes = mutableListOf<Node>()
        val vsIfaces = mutableListOf<Int>()
        val vcIfaces = mutableListOf<Int>()
        val formats = mutableListOf<String>()
        val videoFormats = mutableListOf<VideoFormat>()

        var i = 0
        // 直近のインターフェースのクラス/サブクラスを覚えておき、CS_INTERFACE の解釈に使う。
        var curIfaceClass = -1
        var curIfaceSubclass = -1
        var curVideoFormatIndex = 0
        var curVideoFormatEncoding = ""
        var curVideoFormatFourcc = ""
        var depthForChildren = 1

        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            val slice = raw.copyOfRange(i, i + len)

            when (type) {
                DT_DEVICE -> {
                    val vid = u16(slice, 8)
                    val pid = u16(slice, 10)
                    nodes += Node(
                        0, "Device Descriptor",
                        "class=${u8(slice, 4)} VID=%04x PID=%04x maxPkt0=${u8(slice, 7)}".format(vid, pid),
                        slice,
                    )
                }
                DT_CONFIG -> {
                    nodes += Node(
                        0, "Configuration",
                        "#${u8(slice, 5)} ifaces=${u8(slice, 4)} attr=0x%02x maxPower=${u8(slice, 8) * 2}mA"
                            .format(u8(slice, 7)),
                        slice,
                    )
                    depthForChildren = 1
                }
                DT_INTERFACE_ASSOCIATION -> {
                    nodes += Node(
                        1, "Interface Association (IAD)",
                        "first=${u8(slice, 2)} count=${u8(slice, 3)} class=${u8(slice, 4)}/${u8(slice, 5)}",
                        slice,
                    )
                }
                DT_INTERFACE -> {
                    curIfaceClass = u8(slice, 5)
                    curIfaceSubclass = u8(slice, 6)
                    curVideoFormatIndex = 0
                    curVideoFormatEncoding = ""
                    curVideoFormatFourcc = ""
                    val ifNum = u8(slice, 2)
                    val alt = u8(slice, 3)
                    val nEp = u8(slice, 4)
                    val label = classLabel(curIfaceClass, curIfaceSubclass)
                    nodes += Node(
                        1, "Interface #$ifNum (alt $alt)",
                        "class=$curIfaceClass/$curIfaceSubclass [$label] endpoints=$nEp",
                        slice,
                    )
                    depthForChildren = 2
                    if (curIfaceClass == CLASS_VIDEO && alt == 0) {
                        when (curIfaceSubclass) {
                            SC_VIDEO_CONTROL -> if (ifNum !in vcIfaces) vcIfaces += ifNum
                            SC_VIDEO_STREAMING -> if (ifNum !in vsIfaces) vsIfaces += ifNum
                        }
                    }
                }
                DT_ENDPOINT -> {
                    val addr = u8(slice, 2)
                    val attr = u8(slice, 3)
                    val mps = u16(slice, 4)
                    val dir = if (addr and 0x80 != 0) "IN" else "OUT"
                    val xfer = when (attr and 0x03) {
                        0 -> "Control"; 1 -> "Isochronous"; 2 -> "Bulk"; else -> "Interrupt"
                    }
                    nodes += Node(
                        depthForChildren, "Endpoint 0x%02x ($dir)".format(addr),
                        "$xfer maxPkt=$mps interval=${u8(slice, 6)}",
                        slice,
                    )
                }
                DT_CS_INTERFACE -> {
                    val sub = u8(slice, 2)
                    val decoded = decodeCsInterface(
                        curIfaceClass = curIfaceClass,
                        curIfaceSubclass = curIfaceSubclass,
                        subtype = sub,
                        slice = slice,
                        formats = formats,
                        curVideoFormatIndex = curVideoFormatIndex,
                        curVideoFormatEncoding = curVideoFormatEncoding,
                        curVideoFormatFourcc = curVideoFormatFourcc,
                        videoFormats = videoFormats,
                    )
                    curVideoFormatIndex = decoded.videoFormatIndex
                    curVideoFormatEncoding = decoded.videoFormatEncoding
                    curVideoFormatFourcc = decoded.videoFormatFourcc
                    val (title, detail) = decoded.title to decoded.detail
                    nodes += Node(depthForChildren, title, detail, slice)
                }
                DT_CS_ENDPOINT -> {
                    nodes += Node(depthForChildren, "CS Endpoint", "subtype=${u8(slice, 2)} len=$len", slice)
                }
                DT_HID -> nodes += Node(depthForChildren, "HID Descriptor", "len=$len", slice)
                DT_DEVICE_QUALIFIER -> nodes += Node(0, "Device Qualifier", "len=$len", slice)
                DT_STRING -> { /* rawDescriptors には通常含まれない。スキップ。 */ }
                else -> nodes += Node(
                    depthForChildren, "Unknown Descriptor",
                    "type=0x%02x len=$len bytes=[${slice.toHex(16)}]".format(type),
                    slice,
                )
            }
            i += len
        }

        return Result(nodes, vsIfaces.toList(), vcIfaces.toList(), formats.toList(), videoFormats.toList())
    }

    private data class DecodedCsInterface(
        val title: String,
        val detail: String,
        val videoFormatIndex: Int,
        val videoFormatEncoding: String,
        val videoFormatFourcc: String,
    )

    private fun decodeCsInterface(
        curIfaceClass: Int,
        curIfaceSubclass: Int,
        subtype: Int,
        slice: ByteArray,
        formats: MutableList<String>,
        curVideoFormatIndex: Int,
        curVideoFormatEncoding: String,
        curVideoFormatFourcc: String,
        videoFormats: MutableList<VideoFormat>,
    ): DecodedCsInterface {
        if (curIfaceClass != CLASS_VIDEO) {
            return DecodedCsInterface("CS Interface", "subtype=$subtype len=${slice.size}", curVideoFormatIndex, curVideoFormatEncoding, curVideoFormatFourcc)
        }
        // VideoStreaming のフォーマット/フレームを読み取り、formats に積む。
        if (curIfaceSubclass == SC_VIDEO_STREAMING) {
            when (subtype) {
                VS_INPUT_HEADER ->
                    return DecodedCsInterface(
                        "VS Input Header",
                        "formats=${u8(slice, 3)} endpoint=0x%02x".format(u8(slice, 6)),
                        curVideoFormatIndex,
                        curVideoFormatEncoding,
                        curVideoFormatFourcc,
                    )
                VS_FORMAT_MJPEG -> {
                    val index = u8(slice, 3)
                    return DecodedCsInterface(
                        "VS Format (MJPEG)",
                        "formatIndex=$index frames=${u8(slice, 4)}",
                        index,
                        "MJPEG",
                        "",
                    )
                }
                VS_FORMAT_UNCOMPRESSED -> {
                    val index = u8(slice, 3)
                    // guidFormat は offset 5 から 16 バイト。先頭 4 バイトが FourCC。
                    val fourcc = readFourcc(slice, 5)
                    // YuvImage は YUY2(=YUYV) しか正しく扱えない。他形式(UYVY/NV12 等)は別 encoding にして
                    // 選択肢(computeSelectableFormats の encoding=="YUYV/Uncomp")から自然に除外する。
                    val encoding = if (fourcc == "YUY2" || fourcc == "YUYV") "YUYV/Uncomp" else "Uncomp/${fourcc.ifBlank { "?" }}"
                    return DecodedCsInterface(
                        "VS Format (Uncompressed)",
                        "formatIndex=$index frames=${u8(slice, 4)} fourcc=$fourcc encoding=$encoding",
                        index,
                        encoding,
                        fourcc,
                    )
                }
                VS_FRAME_MJPEG, VS_FRAME_UNCOMPRESSED -> {
                    val isUncompressed = subtype == VS_FRAME_UNCOMPRESSED
                    val w = u16(slice, 5)
                    val h = u16(slice, 7)
                    val kind = if (isUncompressed) "YUYV/Uncomp" else "MJPEG"
                    val encoding = curVideoFormatEncoding.ifBlank { kind }
                    val formatIndex = curVideoFormatIndex.coerceAtLeast(1)
                    val frameIndex = u8(slice, 3)
                    val defaultInterval = u32(slice, 21)
                    val maxFrameSize = u32(slice, 17)
                    val fourccSuffix = if (curVideoFormatFourcc.isNotBlank()) " ${curVideoFormatFourcc}" else ""
                    val intervals = parseFrameIntervals(slice, defaultInterval)
                    // YUYV は帯域に応じて fps を選べるよう全インターバルを候補化。MJPEG は従来どおり既定のみ。
                    val emitIntervals = (if (isUncompressed) intervals else listOf(defaultInterval.takeIf { it > 0 } ?: 333333))
                        .distinct()
                    for (iv in emitIntervals) {
                        videoFormats += VideoFormat(
                            encoding = encoding,
                            formatIndex = formatIndex,
                            frameIndex = frameIndex,
                            width = w,
                            height = h,
                            defaultFrameInterval100ns = iv,
                            maxFrameSize = maxFrameSize,
                            fourcc = curVideoFormatFourcc,
                        )
                    }
                    val fpsSuffix = if (isUncompressed && emitIntervals.size > 1) " (${emitIntervals.size}fps候補)" else ""
                    formats += "$encoding$fourccSuffix ${w}x$h$fpsSuffix"
                    return DecodedCsInterface(
                        "VS Frame ($kind)",
                        "frameIndex=$frameIndex ${w}x$h intervals=${emitIntervals.size} default=${defaultInterval}x100ns maxFrame=$maxFrameSize",
                        curVideoFormatIndex,
                        curVideoFormatEncoding,
                        curVideoFormatFourcc,
                    )
                }
            }
        }
        return DecodedCsInterface(
            "VC/VS CS Interface",
            "subclass=$curIfaceSubclass subtype=$subtype len=${slice.size}",
            curVideoFormatIndex,
            curVideoFormatEncoding,
            curVideoFormatFourcc,
        )
    }

    /**
     * VS_FRAME ディスクリプタの利用可能フレームインターバル(100ns 単位)を返す。
     * bFrameIntervalType==0 は連続(min/max/step)、>0 は離散リスト。
     * UVC 1.x の VS_FRAME レイアウト: bFrameIntervalType=offset25, インターバル列=offset26 から 4B ずつ。
     */
    private fun parseFrameIntervals(slice: ByteArray, defaultInterval: Int): List<Int> {
        val intervalType = u8(slice, 25)
        val result = mutableListOf<Int>()
        if (intervalType == 0) {
            // 連続: 代表値として min / default / max を採用（細かいステップ全列挙は避ける）。
            val minI = u32(slice, 26)
            val maxI = u32(slice, 30)
            listOf(minI, defaultInterval, maxI).forEach { if (it > 0) result += it }
        } else {
            for (n in 0 until intervalType) {
                val iv = u32(slice, 26 + n * 4)
                if (iv > 0) result += iv
            }
        }
        if (result.isEmpty()) result += (defaultInterval.takeIf { it > 0 } ?: 333333)
        // 小さいインターバル=高fps を先頭に（既定の並びに近づける）。
        return result.distinct().sorted()
    }

    /** ASCII 印字可能な 4 バイトを FourCC 文字列にする（不可視は '?'）。末尾空白は除去。 */
    private fun readFourcc(b: ByteArray, off: Int): String {
        if (off + 4 > b.size) return ""
        val sb = StringBuilder(4)
        for (i in 0 until 4) {
            val c = b[off + i].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '?')
        }
        return sb.toString().trim()
    }

    private fun classLabel(cls: Int, sub: Int): String = when (cls) {
        0x00 -> "(per-interface)"
        0x01 -> "Audio"
        0x03 -> "HID"
        0x08 -> "Mass Storage"
        0x09 -> "Hub"
        CLASS_VIDEO -> when (sub) {
            SC_VIDEO_CONTROL -> "Video Control"
            SC_VIDEO_STREAMING -> "Video Streaming"
            else -> "Video"
        }
        0x0A -> "CDC Data"
        0xFF -> "Vendor"
        else -> "class 0x%02x".format(cls)
    }

    private fun u8(b: ByteArray, idx: Int): Int =
        if (idx < b.size) b[idx].toInt() and 0xFF else 0

    private fun u16(b: ByteArray, idx: Int): Int =
        if (idx + 1 < b.size) (b[idx].toInt() and 0xFF) or ((b[idx + 1].toInt() and 0xFF) shl 8) else 0

    private fun u32(b: ByteArray, idx: Int): Int =
        if (idx + 3 < b.size) {
            (b[idx].toInt() and 0xFF) or
                ((b[idx + 1].toInt() and 0xFF) shl 8) or
                ((b[idx + 2].toInt() and 0xFF) shl 16) or
                ((b[idx + 3].toInt() and 0xFF) shl 24)
        } else {
            0
        }
}
