package jp.hitohira.usbcamstreamer.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcPayloadParserTest {
    @Test
    fun parseFirstMjpegFrame_stripsUvcHeadersAndStopsAtEoi() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0x11, 0x22, 0x33,
            0xFF.toByte(), 0xD9.toByte(),
        )
        val records = records(
            packet(flags = 0x01, payload = jpeg.copyOfRange(0, 3)),
            packet(flags = 0x01, payload = jpeg.copyOfRange(3, 5)),
            packet(flags = 0x03, payload = jpeg.copyOfRange(5, jpeg.size)),
        )

        val result = UvcPayloadParser.parseFirstMjpegFrame(records)

        assertEquals(3, result.packetRecords)
        assertTrue(result.eofSeen)
        assertTrue(result.soiSeen)
        assertTrue(result.eoiSeen)
        assertNotNull(result.frame)
        assertArrayEquals(jpeg, result.frame)
    }

    @Test
    fun parseFirstMjpegFrame_skipsPacketsBeforeSoi() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x44, 0x55, 0xFF.toByte(), 0xD9.toByte())
        val records = records(
            packet(flags = 0x00, payload = byteArrayOf(0x01, 0x02, 0x03)),
            packet(flags = 0x01, payload = jpeg.copyOfRange(0, 4)),
            packet(flags = 0x03, payload = jpeg.copyOfRange(4, jpeg.size)),
        )

        val result = UvcPayloadParser.parseFirstMjpegFrame(records)

        assertEquals(3, result.packetRecords)
        assertEquals(jpeg.size, result.frameBytes)
        assertArrayEquals(jpeg, result.frame)
    }

    @Test
    fun parseMjpegFrames_returnsMultipleFrames() {
        val jpeg1 = jpeg(0x10, 0x11)
        val jpeg2 = jpeg(0x20, 0x21)
        val records = records(
            packet(flags = 0x01, payload = jpeg1.copyOfRange(0, 3)),
            packet(flags = 0x03, payload = jpeg1.copyOfRange(3, jpeg1.size)),
            packet(flags = 0x00, payload = jpeg2.copyOfRange(0, 4)),
            packet(flags = 0x02, payload = jpeg2.copyOfRange(4, jpeg2.size)),
        )

        val results = UvcPayloadParser.parseMjpegFrames(records, maxFrames = 4)

        assertEquals(2, results.size)
        assertArrayEquals(jpeg1, results[0].frame)
        assertArrayEquals(jpeg2, results[1].frame)
    }

    @Test
    fun streamingAssembler_keepsPartialFrameAcrossChunks() {
        val jpeg = jpeg(0x31, 0x32, 0x33)
        val assembler = UvcPayloadParser.MjpegAssembler(maxFrames = 4)

        val first = assembler.consumeRecords(
            records(packet(flags = 0x01, payload = jpeg.copyOfRange(0, 4))),
        )
        val second = assembler.consumeRecords(
            records(packet(flags = 0x03, payload = jpeg.copyOfRange(4, jpeg.size))),
        )

        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
        assertArrayEquals(jpeg, second[0].frame)
    }

    @Test
    fun parseMjpegFrames_emitsPartialWhenFidChangesWithoutEof() {
        val firstHalf = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x41, 0x42)
        val jpeg = jpeg(0x51, 0x52)
        val records = records(
            packet(flags = 0x01, payload = firstHalf),
            packet(flags = 0x00, payload = jpeg),
        )

        val results = UvcPayloadParser.parseMjpegFrames(records, maxFrames = 4)

        assertEquals(2, results.size)
        assertEquals(null, results[0].frame)
        assertArrayEquals(jpeg, results[1].frame)
    }

    @Test
    fun parseMjpegFrames_countsErrorPackets() {
        val jpeg = jpeg(0x61, 0x62)
        val records = records(
            packet(flags = 0x41, payload = jpeg),
        )

        val result = UvcPayloadParser.parseFirstMjpegFrame(records)

        assertEquals(1, result.errorPackets)
        assertArrayEquals(jpeg, result.frame)
    }

    private fun packet(flags: Int, payload: ByteArray): ByteArray =
        byteArrayOf(2, flags.toByte()) + payload

    private fun jpeg(vararg body: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            body.map { it.toByte() }.toByteArray() +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun records(vararg packets: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        for (packet in packets) {
            out += (packet.size and 0xFF).toByte()
            out += ((packet.size ushr 8) and 0xFF).toByte()
            for (b in packet) out += b
        }
        return out.toByteArray()
    }
}
