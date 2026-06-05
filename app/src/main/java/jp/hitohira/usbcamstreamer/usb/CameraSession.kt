package jp.hitohira.usbcamstreamer.usb

import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 接続済み UVC カメラ 1 台ぶんのキャプチャ＋PC配信を司る。
 *
 * fd ごとに独立した native iso ストリーム（[UvcNative] の fd 引数版）と、専用ポートの
 * [MjpegStreamServer] を 1 つずつ持つ。これにより複数カメラの同時配信が成立する。
 */
class CameraSession(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val descriptors: DescriptorParser.Result?,
    summary: DeviceSummary,
    val port: Int,
    private val scope: CoroutineScope,
    private val log: (LogLevel, String) -> Unit,
) {
    val deviceName: String = summary.deviceName
    private val fd: Int = connection.fileDescriptor
    private val server = MjpegStreamServer(port = port, log = log)

    private val selectableFormats: List<DescriptorParser.VideoFormat> = computeSelectableFormats(descriptors)
    private var selectedFormatIndex: Int = defaultFormatIndex()
    private var previewJob: Job? = null

    private val _ui = MutableStateFlow(
        CameraUiState(
            deviceName = deviceName,
            title = summary.productName ?: summary.deviceName,
            vidPid = summary.vidPid,
            fd = fd,
            port = port,
            formats = selectableFormats.mapIndexed { i, f ->
                FormatOption(index = i, label = f.label(), width = f.width, height = f.height)
            },
            selectedFormatIndex = selectedFormatIndex,
            streamUrl = server.streamUrl(),
        ),
    )

    /** UI 購読用の StateFlow。 */
    val ui: StateFlow<CameraUiState> = _ui.asStateFlow()

    fun snapshot(): CameraUiState = _ui.value

    // --- 操作 --------------------------------------------------------------
    /** 配信を開始し、実際に開始できたら true を返す（前面化の判断に使う）。 */
    fun startStreaming(): Boolean {
        if (previewJob?.isActive == true) {
            log(LogLevel.WARN, "[$deviceName] 既に配信中です")
            return true
        }
        val candidate = findBestIsoInEndpoint()
        if (candidate == null) {
            update { it.copy(error = "iso IN endpoint が見つかりません") }
            return false
        }
        val url = runCatching { server.start() }.getOrElse { t ->
            update { it.copy(error = "配信開始失敗: ${t.javaClass.simpleName}: ${t.message}") }
            return false
        }
        update {
            it.copy(streaming = true, streamUrl = url, streamStats = server.statusLine(), error = null)
        }
        previewJob = scope.launch { captureLoop(candidate) }
        return true
    }

    fun stopStreaming() {
        previewJob?.cancel()
        previewJob = null
        runCatching { UvcNative.stopIsoStream(fd) }
        runCatching { server.stop() }
        update {
            it.copy(
                streaming = false,
                streamStats = server.statusLine(),
                previewJpeg = null,
                previewVersion = it.previewVersion + 1,
            )
        }
    }

    /** 解像度（フォーマット）変更。配信中なら新解像度で自動的に再起動する。 */
    fun setFormatIndex(index: Int) {
        if (index !in selectableFormats.indices || index == selectedFormatIndex) {
            if (index in selectableFormats.indices) selectedFormatIndex = index
            return
        }
        val wasStreaming = previewJob?.isActive == true
        if (wasStreaming) stopStreaming()
        selectedFormatIndex = index
        update { it.copy(selectedFormatIndex = index) }
        log(LogLevel.INFO, "[$deviceName] format選択: ${selectableFormats[index].label()}")
        if (wasStreaming) startStreaming()
    }

    fun close() {
        stopStreaming()
        runCatching { connection.close() }
        log(LogLevel.INFO, "[$deviceName] セッションを閉じました (port=$port)")
    }

    // --- キャプチャループ --------------------------------------------------
    private suspend fun CoroutineScope.captureLoop(candidate: IsoInCandidate) {
        UvcControlNegotiator.run(device, connection, descriptors, log, selectedVideoFormat())
        val packetSize = candidate.packetSize.coerceIn(1, 3072)
        val readTimeoutMs = 40
        val minUiIntervalMs = 66L
        // 選択フォーマットで取得経路を分岐: MJPEG はそのまま、YUYV(非圧縮) はアプリ側で JPEG 化する。
        val format = selectedVideoFormat()
        val isYuyv = format?.encoding == "YUYV/Uncomp"
        val frameWidth = format?.width ?: 0
        val frameHeight = format?.height ?: 0
        // Int オーバーフローを避けるためフレームサイズは Long で計算する。
        val frameBytesLong = frameWidth.toLong() * frameHeight.toLong() * 2L
        val frameKb = (frameBytesLong / 1024).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        // YUYV は生データで帯域が大きく CPU も食う。URB を厚めに積んで取りこぼしを減らし、
        // 1 フレームが収まる大きめの read バッファで read 回数を抑える。
        val packetsPerUrb = if (isYuyv) 32 else 8
        val urbCount = if (isYuyv) 12 else 4
        val maxReadBytes = if (isYuyv) {
            (frameBytesLong + 256 * 1024).coerceIn(512L * 1024, 4L * 1024 * 1024).toInt()
        } else {
            512 * 1024
        }
        val mjpegAssembler = if (isYuyv) null else UvcPayloadParser.MjpegAssembler(maxFrames = 8)
        val yuyvAssembler = if (isYuyv) YuyvFrameAssembler(frameWidth, frameHeight) else null
        if (isYuyv) {
            val fourcc = format?.fourcc.orEmpty()
            log(
                LogLevel.INFO,
                "[$deviceName] YUYV取得→アプリ側JPEGエンコード ${frameWidth}x$frameHeight fourcc=${fourcc.ifBlank { "?" }} " +
                    "q=${YuyvJpegEncoder.DEFAULT_QUALITY} packetsPerUrb=$packetsPerUrb urbCount=$urbCount " +
                    "readBuf=${maxReadBytes / 1024}KB frame=${frameKb}KB",
            )
            // YuvImage は YUY2(=YUYV) 並びを前提とする。他 FourCC だと整列済みフレームでも色化けする。
            if (fourcc.isNotBlank() && fourcc != "YUY2" && fourcc != "YUYV") {
                log(
                    LogLevel.WARN,
                    "[$deviceName] 非YUY2フォーマット($fourcc)です。YuvImage(YUY2)では色/配列が乱れる可能性。映像が乱れる場合はこれが原因。",
                )
            }
        }
        var chunks = 0
        var bytesIn = 0L
        var framesIn = 0
        var decoded = 0
        var displayed = 0
        var dropped = 0
        var misses = 0
        // エンコード/解析の所要時間（区間集計）。ボトルネック切り分け用。
        var encodeCount = 0
        var encodeTotalMs = 0L
        var encodeMaxMs = 0L
        var encodeNulls = 0
        var analyzeTotalMs = 0L
        var lastJpegBytes = 0
        val startedAt = System.currentTimeMillis()
        var lastUiAt = 0L
        var lastStatsAt = startedAt

        val startStatus = try {
            UvcNative.startIsoStream(
                fd = fd,
                interfaceId = candidate.interfaceId,
                altSetting = candidate.altSetting,
                endpointAddress = candidate.endpointAddress,
                packetSize = packetSize,
                packetsPerUrb = packetsPerUrb,
                urbCount = urbCount,
            )
        } catch (t: Throwable) {
            update { it.copy(error = "startIsoStream ${t.javaClass.simpleName}: ${t.message}") }
            return
        }
        log(LogLevel.OK, "[$deviceName] live stream: $startStatus")

        try {
            while (isActive) {
                val records = try {
                    UvcNative.readIsoStream(fd = fd, maxOutputBytes = maxReadBytes, timeoutMs = readTimeoutMs)
                } catch (t: Throwable) {
                    update { it.copy(error = "readIsoStream ${t.javaClass.simpleName}: ${t.message}") }
                    break
                }
                if (!isActive) break

                val now = System.currentTimeMillis()
                if (records == null || records.isEmpty()) {
                    misses++
                } else {
                    chunks++
                    bytesIn += records.size
                    // 経路ごとにフレームを取り出し、配信しつつ「最新の JPEG」と whiteLike を確定する。
                    var latestJpeg: ByteArray? = null
                    var latestWhiteLike = false
                    if (yuyvAssembler != null) {
                        for (raw in yuyvAssembler.consumeRecords(records)) {
                            val t0 = System.nanoTime()
                            val jpeg = YuyvJpegEncoder.encode(raw, frameWidth, frameHeight)
                            val ms = (System.nanoTime() - t0) / 1_000_000
                            encodeCount++
                            encodeTotalMs += ms
                            if (ms > encodeMaxMs) encodeMaxMs = ms
                            if (jpeg == null) {
                                encodeNulls++
                                continue
                            }
                            server.publishFrame(jpeg)
                            framesIn++
                            latestJpeg = jpeg
                            lastJpegBytes = jpeg.size
                            // whiteLike は生 Y から算出（JPEG デコード不要＝低 CPU）。
                            latestWhiteLike = isYuyvWhiteLike(raw, frameWidth, frameHeight)
                        }
                    } else if (mjpegAssembler != null) {
                        val completeFrames = mjpegAssembler.consumeRecords(records).mapNotNull { it.frame }
                        completeFrames.forEach { server.publishFrame(it) }
                        framesIn += completeFrames.size
                        latestJpeg = completeFrames.lastOrNull()
                        if (latestJpeg != null) lastJpegBytes = latestJpeg.size
                    }

                    if (latestJpeg == null) {
                        misses++
                    } else {
                        decoded++
                        if (now - lastUiAt >= minUiIntervalMs) {
                            lastUiAt = now
                            displayed++
                            // プレビュー更新は解析の成否に依存させない（解析が失敗しても映像は出す）。
                            val whiteLike = if (isYuyv) {
                                latestWhiteLike
                            } else {
                                val t0 = System.nanoTime()
                                val w = analyzeJpegWhiteLike(latestJpeg)
                                analyzeTotalMs += (System.nanoTime() - t0) / 1_000_000
                                w
                            }
                            val previewStats = previewStatsText(startedAt, now, chunks, bytesIn, framesIn, decoded, displayed, dropped, misses)
                            update {
                                it.copy(
                                    streaming = true,
                                    streamStats = server.statusLine(),
                                    previewStats = previewStats,
                                    whiteLike = whiteLike,
                                    previewJpeg = latestJpeg,
                                    previewVersion = it.previewVersion + 1,
                                    error = null,
                                )
                            }
                        } else {
                            dropped++
                        }
                    }
                }

                // フレームの有無に関わらず 1 秒ごとに診断ログを出す（停滞時も観測できるように）。
                if (now - lastStatsAt >= 1000) {
                    val previewStats = previewStatsText(startedAt, now, chunks, bytesIn, framesIn, decoded, displayed, dropped, misses)
                    val diag = if (yuyvAssembler != null) {
                        val encAvg = if (encodeCount > 0) encodeTotalMs.toDouble() / encodeCount else 0.0
                        " | enc avg=%.1fms max=%dms n=%d null=%d jpeg=%dB | asm[%s]".format(
                            encAvg, encodeMaxMs, encodeCount, encodeNulls, lastJpegBytes, yuyvAssembler.stats.line(),
                        )
                    } else {
                        " | jpeg=%dB analyzeMs=%d".format(lastJpegBytes, analyzeTotalMs)
                    }
                    log(LogLevel.INFO, "[$deviceName] $previewStats / ${server.statusLine()}$diag")
                    lastStatsAt = now
                    encodeCount = 0
                    encodeTotalMs = 0
                    encodeMaxMs = 0
                    encodeNulls = 0
                    analyzeTotalMs = 0
                    yuyvAssembler?.resetStats()
                }
            }
        } finally {
            val stopStatus = runCatching { UvcNative.stopIsoStream(fd) }.getOrElse {
                "stopIsoStream ${it.javaClass.simpleName}: ${it.message}"
            }
            log(LogLevel.INFO, "[$deviceName] live stream stop: $stopStatus")
        }
    }

    private fun update(block: (CameraUiState) -> CameraUiState) {
        _ui.value = block(_ui.value)
    }

    // --- フォーマット選択 --------------------------------------------------
    private fun selectedVideoFormat(): DescriptorParser.VideoFormat? {
        if (selectableFormats.isEmpty()) return null
        return selectableFormats[selectedFormatIndex.coerceIn(0, selectableFormats.lastIndex)]
    }

    private fun defaultFormatIndex(): Int =
        selectableFormats.indexOfFirst { it.encoding == "MJPEG" && it.width == 320 && it.height == 240 }
            .takeIf { it >= 0 }
            ?: selectableFormats.indexOfFirst { it.encoding == "MJPEG" && it.width <= 640 && it.height <= 480 }.takeIf { it >= 0 }
            ?: 0

    // --- iso endpoint 選択 -------------------------------------------------
    private fun findBestIsoInEndpoint(): IsoInCandidate? {
        val candidates = mutableListOf<IsoInCandidate>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != DescriptorParser.CLASS_VIDEO || intf.interfaceSubclass != 2) continue
            if (intf.alternateSetting <= 0) continue
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_IN) {
                    candidates += IsoInCandidate(intf.id, intf.alternateSetting, ep.address, ep.maxPacketSize)
                }
            }
        }
        return candidates.maxByOrNull { it.packetSize }
    }

    // --- 露出(白飛び)判定 --------------------------------------------------
    /** MJPEG 用: JPEG をデコードして輝度サンプリングで whiteLike を判定する。失敗時は false。 */
    private fun analyzeJpegWhiteLike(bytes: ByteArray): Boolean {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        return try {
            val stepX = (bitmap.width / 32).coerceAtLeast(1)
            val stepY = (bitmap.height / 24).coerceAtLeast(1)
            var count = 0
            var sum = 0L
            var min = 255
            var max = 0
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val c = bitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val luma = (r * 299 + g * 587 + b * 114) / 1000
                    sum += luma.toLong()
                    min = minOf(min, luma)
                    max = maxOf(max, luma)
                    count++
                    x += stepX
                }
                y += stepY
            }
            whiteLikeFrom(sum, count, min, max)
        } finally {
            bitmap.recycle()
        }
    }

    /** YUYV 用: 生 Y(YUY2 の偶数バイト) を直接サンプリングして whiteLike を判定する（デコード不要）。 */
    private fun isYuyvWhiteLike(yuy2: ByteArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || yuy2.size < width * height * 2) return false
        val rowBytes = width * 2
        val stepX = (width / 32).coerceAtLeast(1)
        val stepY = (height / 24).coerceAtLeast(1)
        var count = 0
        var sum = 0L
        var min = 255
        var max = 0
        var y = 0
        while (y < height) {
            val rowStart = y * rowBytes
            var x = 0
            while (x < width) {
                val luma = yuy2[rowStart + x * 2].toInt() and 0xFF  // 各ピクセル先頭が Y
                sum += luma.toLong()
                min = minOf(min, luma)
                max = maxOf(max, luma)
                count++
                x += stepX
            }
            y += stepY
        }
        return whiteLikeFrom(sum, count, min, max)
    }

    private fun whiteLikeFrom(sum: Long, count: Int, min: Int, max: Int): Boolean {
        val mean = if (count == 0) 0.0 else sum.toDouble() / count.toDouble()
        return mean > 245.0 && min > 220 && (max - min) < 35
    }

    private fun previewStatsText(
        startedAt: Long,
        now: Long,
        chunks: Int,
        bytesIn: Long,
        framesIn: Int,
        decoded: Int,
        displayed: Int,
        dropped: Int,
        misses: Int,
    ): String {
        val elapsedSec = ((now - startedAt).coerceAtLeast(1)).toDouble() / 1000.0
        return "fps=%.1f usbKB/s=%.1f decoded/s=%.1f dropped=%d misses=%d".format(
            displayed.toDouble() / elapsedSec,
            bytesIn.toDouble() / 1024.0 / elapsedSec,
            decoded.toDouble() / elapsedSec,
            dropped,
            misses,
        )
    }

    private fun DescriptorParser.VideoFormat.label(): String =
        if (defaultFrameInterval100ns > 0) {
            "%dx%d %s @%.0ffps".format(width, height, encoding, 1.0e7 / defaultFrameInterval100ns)
        } else {
            "%dx%d %s".format(width, height, encoding)
        }

    private fun computeSelectableFormats(result: DescriptorParser.Result?): List<DescriptorParser.VideoFormat> =
        result?.videoFormats.orEmpty()
            // 実験的機能: MJPEG に加え YUYV(非圧縮) も選択肢に出す。YUYV はアプリ側で JPEG 化して配信する。
            .filter { it.encoding == "MJPEG" || it.encoding == "YUYV/Uncomp" }
            // fps(interval) 違いを別項目として残す（YUYV の帯域調整用）。
            .distinctBy { "${it.formatIndex}:${it.frameIndex}:${it.width}x${it.height}:${it.defaultFrameInterval100ns}" }
}
