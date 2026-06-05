package jp.hitohira.usbcamstreamer.usb

import android.graphics.Bitmap
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
        val packetsPerUrb = 8
        val urbCount = 4
        val readTimeoutMs = 40
        val minUiIntervalMs = 66L
        val assembler = UvcPayloadParser.MjpegAssembler(maxFrames = 8)
        var chunks = 0
        var bytesIn = 0L
        var framesIn = 0
        var decoded = 0
        var displayed = 0
        var dropped = 0
        var misses = 0
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
                    UvcNative.readIsoStream(fd = fd, maxOutputBytes = 512 * 1024, timeoutMs = readTimeoutMs)
                } catch (t: Throwable) {
                    update { it.copy(error = "readIsoStream ${t.javaClass.simpleName}: ${t.message}") }
                    break
                }
                if (!isActive) break
                if (records == null || records.isEmpty()) {
                    misses++
                    continue
                }
                chunks++
                bytesIn += records.size
                val completeFrames = assembler.consumeRecords(records)
                if (completeFrames.isEmpty()) misses++
                completeFrames.forEach { result -> result.frame?.let { server.publishFrame(it) } }

                val selected = chooseLatestFrame(completeFrames)
                val frame = selected?.first?.frame
                val stats = selected?.second
                if (frame == null || stats == null) continue

                framesIn += completeFrames.count { it.frame != null }
                decoded++
                val now = System.currentTimeMillis()
                if (now - lastUiAt < minUiIntervalMs) {
                    dropped++
                    continue
                }
                lastUiAt = now
                displayed++
                val previewStats = previewStatsText(startedAt, now, chunks, bytesIn, framesIn, decoded, displayed, dropped, misses)
                if (now - lastStatsAt >= 1000) {
                    lastStatsAt = now
                    log(LogLevel.INFO, "[$deviceName] $previewStats / ${server.statusLine()}")
                }
                update {
                    it.copy(
                        streaming = true,
                        streamStats = server.statusLine(),
                        previewStats = previewStats,
                        whiteLike = stats.whiteLike,
                        previewJpeg = frame,
                        previewVersion = it.previewVersion + 1,
                        error = null,
                    )
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

    // --- JPEG 解析 / 統計 --------------------------------------------------
    private data class ImageStats(val width: Int, val height: Int, val whiteLike: Boolean)

    private fun chooseLatestFrame(results: List<UvcPayloadParser.Result>): Pair<UvcPayloadParser.Result, ImageStats>? {
        val result = results.asReversed().firstOrNull { it.frame != null } ?: return null
        val frame = result.frame ?: return null
        val stats = analyzeJpeg(frame) ?: return null
        return result to stats
    }

    private fun analyzeJpeg(bytes: ByteArray): ImageStats? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            analyzeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun analyzeBitmap(bitmap: Bitmap): ImageStats {
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
        val mean = if (count == 0) 0.0 else sum.toDouble() / count.toDouble()
        val whiteLike = mean > 245.0 && min > 220 && (max - min) < 35
        return ImageStats(bitmap.width, bitmap.height, whiteLike)
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
            .filter { it.encoding == "MJPEG" }
            .distinctBy { "${it.formatIndex}:${it.frameIndex}:${it.width}x${it.height}" }
}
