package jp.hitohira.usbcamstreamer.usb

/**
 * 非圧縮（YUYV / Uncompressed）UVC フレームの組み立て。
 *
 * MJPEG と違い SOI/EOI マーカが無いため、フレーム境界は UVC ペイロードヘッダの
 * FID トグル / EOF ビットで判定する。native レコード形式は [UvcPayloadParser] と同じ
 * （little-endian uint16 packetLength + packet bytes の繰り返し）。
 *
 * 1 フレーム = `width * height * 2` バイト（YUY2 並び: Y0 Cb Y1 Cr）。640x480 のように
 * 1 フレームが native の read 上限を超える場合は複数 read にまたがるため、本インスタンスは
 * キャプチャループ開始前に 1 つだけ生成し、`consumeRecords` を跨いで蓄積する想定。
 *
 * 蓄積先は GC を避けるため固定長 [ByteArray] を 1 本だけ事前確保し、サイズはポインタで管理する。
 * ボトルネック切り分け用に [Stats] を内蔵し、emit/drop の内訳を観測できるようにしている。
 */
class YuyvFrameAssembler(width: Int, height: Int) {
    private val expectedBytes: Int
    // 境界検出に失敗して延々と溜め込むのを防ぐ上限。超えたら破棄してやり直す。
    private val maxAccumulate: Int
    private val buffer: ByteArray
    private var bufferSize = 0
    private var collecting = false
    private var currentFid: Int? = null

    init {
        // 壊れた/極端な descriptor で Int オーバーフローしないよう Long で計算し、健全域へクランプする。
        val bytes = width.toLong() * height.toLong() * 2L
        expectedBytes = if (bytes in 1L..MAX_FRAME_BYTES) bytes.toInt() else MAX_FRAME_BYTES.toInt()
        maxAccumulate = expectedBytes + expectedBytes / 2
        buffer = ByteArray(maxAccumulate)
    }

    /** 観測用カウンタ。CameraSession が定期ログ出力したあと [resetStats] で区間集計する。 */
    class Stats {
        var emitted = 0          // 正常に確定したフレーム数
        var shortDropped = 0     // expectedBytes 未満で破棄（パケット取りこぼし疑い）
        var truncated = 0        // expectedBytes 超過で先頭採用（境界ズレ疑い）
        var overflowReset = 0    // 上限超過で強制破棄（FID/EOF 未検出疑い）
        var fidToggles = 0       // FID 変化での境界確定回数
        var eofEmits = 0         // EOF ビットでの境界確定回数
        var emptyPayloadPackets = 0  // ペイロード無しパケット（UVC では正常にもありうる）
        var errPackets = 0       // ERR ビット付きパケット
        var headerErrors = 0     // ヘッダ長が不正なパケット
        var minAssembled = Int.MAX_VALUE
        var maxAssembled = 0
        var expected = 0

        fun line(): String =
            "emit=$emitted short=$shortDropped trunc=$truncated overflow=$overflowReset " +
                "fidCut=$fidToggles eofCut=$eofEmits empty=$emptyPayloadPackets err=$errPackets hdrErr=$headerErrors " +
                "asmMin=${if (minAssembled == Int.MAX_VALUE) 0 else minAssembled} asmMax=$maxAssembled exp=$expected"
    }

    val stats = Stats().also { it.expected = expectedBytes }

    fun resetStats() {
        stats.emitted = 0
        stats.shortDropped = 0
        stats.truncated = 0
        stats.overflowReset = 0
        stats.fidToggles = 0
        stats.eofEmits = 0
        stats.emptyPayloadPackets = 0
        stats.errPackets = 0
        stats.headerErrors = 0
        stats.minAssembled = Int.MAX_VALUE
        stats.maxAssembled = 0
    }

    /** 完成した生 YUYV フレーム（厳密に [expectedBytes] 長）の一覧を返す。 */
    fun consumeRecords(records: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        forEachPacket(records) { packetStart, len ->
            consumePacket(records, packetStart, len, out)
        }
        return out
    }

    private fun consumePacket(records: ByteArray, packetStart: Int, len: Int, out: MutableList<ByteArray>) {
        if (len == 0) return
        val headerLen = records[packetStart].toInt() and 0xFF
        if (headerLen < 2 || headerLen > len) {
            stats.headerErrors++
            // 不正パケットは現在蓄積中フレームを汚染しうる。途中なら破棄してやり直す。
            if (collecting) resetFrame()
            return
        }

        val flags = records[packetStart + 1].toInt() and 0xFF
        if (flags and UVC_FLAG_ERR != 0) {
            stats.errPackets++
            // ERR パケットでデータ欠落/破損。途中フレームはズレるので破棄する。
            if (collecting) resetFrame()
            return
        }
        val fid = flags and UVC_FLAG_FID
        val eof = flags and UVC_FLAG_EOF != 0

        // FID トグルは「前フレーム終端」を意味する。蓄積中なら確定させてから次を始める。
        if (collecting && currentFid != null && fid != currentFid) {
            stats.fidToggles++
            emit(out)
        }

        val payloadStart = packetStart + headerLen
        val payloadLen = len - headerLen
        if (payloadLen > 0) {
            if (!collecting) {
                collecting = true
                currentFid = fid
            }
            if (bufferSize + payloadLen > maxAccumulate) {
                // 境界が来ないまま膨らんだ＝FID/EOF 検出が崩れている。破棄してやり直す。
                stats.overflowReset++
                resetFrame()
                return
            }
            System.arraycopy(records, payloadStart, buffer, bufferSize, payloadLen)
            bufferSize += payloadLen
        } else {
            stats.emptyPayloadPackets++
            if (!collecting) {
                // ペイロード無しヘッダのみ。EOF だけ拾えるよう FID は覚えておく。
                currentFid = fid
            }
        }

        if (eof && collecting) {
            stats.eofEmits++
            emit(out)
        }
    }

    private fun emit(out: MutableList<ByteArray>) {
        val size = bufferSize
        if (size < stats.minAssembled) stats.minAssembled = size
        if (size > stats.maxAssembled) stats.maxAssembled = size

        when {
            size < expectedBytes -> stats.shortDropped++          // 取りこぼし → 破棄
            else -> {
                if (size > expectedBytes) stats.truncated++       // 境界ズレ疑い → 先頭採用
                out += buffer.copyOfRange(0, expectedBytes)
                stats.emitted++
            }
        }
        resetFrame()
    }

    private fun resetFrame() {
        bufferSize = 0
        collecting = false
        currentFid = null
    }

    private inline fun forEachPacket(records: ByteArray, consume: (packetStart: Int, len: Int) -> Unit) {
        var offset = 0
        while (offset + 2 <= records.size) {
            val len = (records[offset].toInt() and 0xFF) or
                ((records[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            if (offset + len > records.size) break
            consume(offset, len)
            offset += len
        }
    }

    companion object {
        private const val UVC_FLAG_FID = 0x01
        private const val UVC_FLAG_EOF = 0x02
        private const val UVC_FLAG_ERR = 0x40

        // 防御的上限（壊れた descriptor 対策）。実フレームは VGA=600KB 程度、4K YUYV でも ~16.6MB。
        private const val MAX_FRAME_BYTES = 32L * 1024 * 1024
    }
}
