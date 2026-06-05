package jp.hitohira.usbcamstreamer.usb

import java.io.ByteArrayOutputStream

/**
 * Parses native ISO capture records.
 *
 * Native record format:
 *   repeated little-endian uint16 packetLength + packet bytes
 *
 * Each non-empty packet is expected to start with a UVC payload header. The parser strips that
 * header, follows FID/EOF, and returns complete MJPEG frames it can identify.
 */
object UvcPayloadParser {
    private const val UVC_FLAG_FID = 0x01
    private const val UVC_FLAG_EOF = 0x02
    private const val UVC_FLAG_ERR = 0x40

    data class Result(
        val frameIndex: Int,
        val packetRecords: Int,
        val payloadBytes: Int,
        val frameBytes: Int,
        val eofSeen: Boolean,
        val soiSeen: Boolean,
        val eoiSeen: Boolean,
        val errorPackets: Int,
        val frame: ByteArray?,
    )

    fun parseFirstMjpegFrame(records: ByteArray): Result {
        return parseMjpegFrames(records, maxFrames = 1).firstOrNull() ?: emptyResult()
    }

    fun parseMjpegFrames(records: ByteArray, maxFrames: Int = 8): List<Result> {
        val assembler = MjpegAssembler(maxFrames)
        val results = mutableListOf<Result>()
        results += assembler.consumeRecords(records)
        if (results.size < maxFrames) {
            results += assembler.finish()
        }
        return results.take(maxFrames)
    }

    class MjpegAssembler(private val maxFrames: Int = 8) {
        val results = mutableListOf<Result>()
        private var packets = 0
        private var payloadBytes = 0
        private var eofSeen = false
        private var soiSeen = false
        private var errorPackets = 0
        private var collecting = false
        private var currentFid: Int? = null
        private var lastFrameByte = -1
        private var frameIndex = 0
        private var emittedResults = 0
        private val frame = ByteArrayOutputStream()

        fun consumeRecords(records: ByteArray): List<Result> {
            forEachPacket(records) { packetStart, len ->
                consumePacket(records, packetStart, len)
                results.size < maxFrames
            }
            return drainCompleteResults()
        }

        fun finish(): List<Result> {
            if (emittedResults == 0 || collecting || frame.size() > 0) {
                val bytes = frame.toByteArray()
                val eoi = findMarker(bytes, 0, bytes.size, 0xFF, 0xD9)
                val eoiSeenNow = eoi >= 0
                emitResult(
                    resultFor(
                        frameIndex = frameIndex,
                        packets = packets,
                        payloadBytes = payloadBytes,
                        eofSeen = eofSeen,
                        soiSeen = soiSeen,
                        eoiSeen = eoiSeenNow,
                        errorPackets = errorPackets,
                        frameBytes = bytes.size,
                        frame = if (soiSeen && eoiSeenNow) bytes.copyOfRange(0, eoi + 2) else null,
                    ),
                )
            }
            return drainCompleteResults()
        }

        private fun drainCompleteResults(): List<Result> {
            val drained = results.toList()
            results.clear()
            return drained
        }

        private fun consumePacket(records: ByteArray, packetStart: Int, len: Int) {
            if (len == 0 || results.size >= maxFrames) {
                packets++
                return
            }

            packets++
            val headerLen = records[packetStart].toInt() and 0xFF
            if (headerLen < 2 || headerLen > len) {
                errorPackets++
                return
            }
            val flags = records[packetStart + 1].toInt() and 0xFF
            if (flags and UVC_FLAG_ERR != 0) errorPackets++
            val fid = flags and UVC_FLAG_FID
            val payloadStart = packetStart + headerLen
            val payloadLen = len - headerLen
            if (payloadLen <= 0) {
                eofSeen = eofSeen || (flags and UVC_FLAG_EOF != 0)
                return
            }

            payloadBytes += payloadLen
            if (collecting && currentFid != null && fid != currentFid && frame.size() > 0) {
                emitPartialFrame()
                if (results.size >= maxFrames) return
            }

            appendPayload(records, payloadStart, payloadLen, fid, flags)

            eofSeen = eofSeen || (flags and UVC_FLAG_EOF != 0)
            if (flags and UVC_FLAG_EOF != 0 && collecting && results.size < maxFrames) {
                emitFrameAtEof()
            }
        }

        private fun appendPayload(
            records: ByteArray,
            payloadStart: Int,
            payloadLen: Int,
            fid: Int,
            flags: Int,
        ) {
            if (!collecting) {
                val soi = findMarker(records, payloadStart, payloadLen, 0xFF, 0xD8)
                if (soi < 0) return
                collecting = true
                soiSeen = true
                currentFid = fid
                appendUntilEoi(records, soi, payloadStart + payloadLen - soi, priorByte = -1, flags = flags)
                return
            }

            appendUntilEoi(records, payloadStart, payloadLen, priorByte = lastFrameByte, flags = flags)
        }

        private fun appendUntilEoi(
            records: ByteArray,
            start: Int,
            length: Int,
            priorByte: Int,
            flags: Int,
        ) {
            val eoiEnd = findEoiEnd(records, start, length, priorByte)
            if (eoiEnd >= 0) {
                frame.write(records, start, eoiEnd - start)
                val complete = frame.toByteArray()
                emitResult(
                    resultFor(
                        frameIndex = frameIndex++,
                        packets = packets,
                        payloadBytes = payloadBytes,
                        eofSeen = eofSeen || (flags and UVC_FLAG_EOF != 0),
                        soiSeen = true,
                        eoiSeen = true,
                        errorPackets = errorPackets,
                        frameBytes = complete.size,
                        frame = complete,
                    ),
                )
                resetFrame()
                return
            }

            frame.write(records, start, length)
            lastFrameByte = records[start + length - 1].toInt() and 0xFF
        }

        private fun emitPartialFrame() {
            emitResult(
                resultFor(
                    frameIndex = frameIndex++,
                    packets = packets,
                    payloadBytes = payloadBytes,
                    eofSeen = eofSeen,
                    soiSeen = soiSeen,
                    eoiSeen = false,
                    errorPackets = errorPackets,
                    frameBytes = frame.size(),
                    frame = null,
                ),
            )
            resetFrame()
        }

        private fun emitFrameAtEof() {
            val bytes = frame.toByteArray()
            val soi = findMarker(bytes, 0, bytes.size, 0xFF, 0xD8)
            val eoi = findMarker(bytes, 0, bytes.size, 0xFF, 0xD9)
            val complete = if (soi >= 0 && eoi > soi) bytes.copyOfRange(soi, eoi + 2) else null
            emitResult(
                resultFor(
                    frameIndex = frameIndex++,
                    packets = packets,
                    payloadBytes = payloadBytes,
                    eofSeen = true,
                    soiSeen = soiSeen || soi >= 0,
                    eoiSeen = eoi >= 0,
                    errorPackets = errorPackets,
                    frameBytes = complete?.size ?: bytes.size,
                    frame = complete,
                ),
            )
            resetFrame()
        }

        private fun emitResult(result: Result) {
            results += result
            emittedResults++
        }

        private fun resetFrame() {
            frame.reset()
            collecting = false
            currentFid = null
            lastFrameByte = -1
        }
    }

    private fun forEachPacket(records: ByteArray, consume: (packetStart: Int, len: Int) -> Boolean) {
        var offset = 0
        while (offset + 2 <= records.size) {
            val len = (records[offset].toInt() and 0xFF) or
                ((records[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            if (offset + len > records.size) break
            if (!consume(offset, len)) break
            offset += len
        }
    }

    private fun emptyResult(): Result = Result(
        frameIndex = 0,
        packetRecords = 0,
        payloadBytes = 0,
        frameBytes = 0,
        eofSeen = false,
        soiSeen = false,
        eoiSeen = false,
        errorPackets = 0,
        frame = null,
    )

    private fun resultFor(
        frameIndex: Int,
        packets: Int,
        payloadBytes: Int,
        eofSeen: Boolean,
        soiSeen: Boolean,
        eoiSeen: Boolean,
        errorPackets: Int,
        frameBytes: Int,
        frame: ByteArray?,
    ): Result = Result(
        frameIndex = frameIndex,
        packetRecords = packets,
        payloadBytes = payloadBytes,
        frameBytes = frameBytes,
        eofSeen = eofSeen,
        soiSeen = soiSeen,
        eoiSeen = eoiSeen,
        errorPackets = errorPackets,
        frame = frame,
    )

    private fun findMarker(
        bytes: ByteArray,
        start: Int,
        length: Int,
        first: Int,
        second: Int,
    ): Int {
        val end = (start + length - 1).coerceAtMost(bytes.lastIndex)
        var i = start.coerceAtLeast(0)
        while (i < end) {
            if ((bytes[i].toInt() and 0xFF) == first && (bytes[i + 1].toInt() and 0xFF) == second) {
                return i
            }
            i++
        }
        return -1
    }

    private fun findEoiEnd(
        bytes: ByteArray,
        start: Int,
        length: Int,
        priorByte: Int,
    ): Int {
        if (length <= 0) return -1
        if (priorByte == 0xFF && (bytes[start].toInt() and 0xFF) == 0xD9) {
            return start + 1
        }
        val eoi = findMarker(bytes, start, length, 0xFF, 0xD9)
        return if (eoi >= 0) eoi + 2 else -1
    }
}
