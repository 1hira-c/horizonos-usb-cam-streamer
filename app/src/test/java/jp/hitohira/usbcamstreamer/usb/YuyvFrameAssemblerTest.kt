package jp.hitohira.usbcamstreamer.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuyvFrameAssemblerTest {
    // width=4,height=1 -> expectedBytes = 8, maxAccumulate = 12
    private val width = 4
    private val height = 1
    private val expected = width * height * 2

    @Test
    fun emitsFullFrameOnEof() {
        val asm = YuyvFrameAssembler(width, height)
        val data = bytes(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17)
        val out = asm.consumeRecords(
            records(
                packet(flags = FID, payload = data.copyOfRange(0, 4)),
                packet(flags = FID or EOF, payload = data.copyOfRange(4, 8)),
            ),
        )
        assertEquals(1, out.size)
        assertArrayEquals(data, out[0])
        assertEquals(1, asm.stats.emitted)
        assertEquals(1, asm.stats.eofEmits)
    }

    @Test
    fun dropsShortFrame() {
        val asm = YuyvFrameAssembler(width, height)
        val out = asm.consumeRecords(
            records(packet(flags = FID or EOF, payload = bytes(0x01, 0x02, 0x03))),
        )
        assertTrue(out.isEmpty())
        assertEquals(0, asm.stats.emitted)
        assertEquals(1, asm.stats.shortDropped)
    }

    @Test
    fun truncatesOverlongFrameToExpected() {
        val asm = YuyvFrameAssembler(width, height)
        val data = bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val out = asm.consumeRecords(
            records(packet(flags = FID or EOF, payload = data)),
        )
        assertEquals(1, out.size)
        assertArrayEquals(data.copyOfRange(0, expected), out[0])
        assertEquals(1, asm.stats.truncated)
        assertEquals(1, asm.stats.emitted)
    }

    @Test
    fun fidToggleDelimitsFrames() {
        val asm = YuyvFrameAssembler(width, height)
        val frameA = bytes(0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27)
        val frameB = bytes(0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37)
        val out = asm.consumeRecords(
            records(
                packet(flags = FID, payload = frameA),        // collect A (fid=1), no EOF
                packet(flags = 0x00, payload = frameB),       // fid toggles -> emit A, then collect B
                packet(flags = EOF, payload = bytes()),       // EOF on B -> emit B
            ),
        )
        assertEquals(2, out.size)
        assertArrayEquals(frameA, out[0])
        assertArrayEquals(frameB, out[1])
        assertEquals(2, asm.stats.emitted)
        assertEquals(1, asm.stats.fidToggles)
    }

    @Test
    fun errPacketResetsCurrentFrame() {
        val asm = YuyvFrameAssembler(width, height)
        val good = bytes(0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47)
        val out = asm.consumeRecords(
            records(
                packet(flags = FID, payload = bytes(0xAA, 0xBB, 0xCC, 0xDD)),  // partial, collecting
                packet(flags = ERR or FID, payload = bytes(0xEE, 0xFF)),       // ERR -> reset partial
                packet(flags = 0x00, payload = good.copyOfRange(0, 4)),        // fresh frame
                packet(flags = EOF, payload = good.copyOfRange(4, 8)),
            ),
        )
        assertEquals(1, out.size)
        assertArrayEquals(good, out[0])
        assertEquals(1, asm.stats.errPackets)
        assertEquals(1, asm.stats.emitted)
    }

    @Test
    fun keepsPartialFrameAcrossConsumeCalls() {
        val asm = YuyvFrameAssembler(width, height)
        val data = bytes(1, 2, 3, 4, 5, 6, 7, 8)
        val first = asm.consumeRecords(records(packet(flags = FID, payload = data.copyOfRange(0, 4))))
        val second = asm.consumeRecords(records(packet(flags = FID or EOF, payload = data.copyOfRange(4, 8))))
        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
        assertArrayEquals(data, second[0])
    }

    @Test
    fun overflowResetsWhenBoundaryNeverArrives() {
        val asm = YuyvFrameAssembler(width, height)
        // maxAccumulate = 12; two 8-byte payloads with same FID and no EOF exceed it.
        val out = asm.consumeRecords(
            records(
                packet(flags = FID, payload = bytes(1, 2, 3, 4, 5, 6, 7, 8)),
                packet(flags = FID, payload = bytes(9, 10, 11, 12, 13, 14, 15, 16)),
            ),
        )
        assertTrue(out.isEmpty())
        assertEquals(1, asm.stats.overflowReset)
        assertEquals(0, asm.stats.emitted)
    }

    private fun packet(flags: Int, payload: ByteArray): ByteArray =
        byteArrayOf(2, flags.toByte()) + payload

    private fun bytes(vararg b: Int): ByteArray = b.map { it.toByte() }.toByteArray()

    private fun records(vararg packets: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        for (p in packets) {
            out += (p.size and 0xFF).toByte()
            out += ((p.size ushr 8) and 0xFF).toByte()
            for (x in p) out += x
        }
        return out.toByteArray()
    }

    private companion object {
        const val FID = 0x01
        const val EOF = 0x02
        const val ERR = 0x40
    }
}
