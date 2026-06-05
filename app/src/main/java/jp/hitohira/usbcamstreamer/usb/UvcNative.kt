package jp.hitohira.usbcamstreamer.usb

/** JNI bridge to the native usbfs/iso layer. Validates the fd before any iso work. */
object UvcNative {
    init {
        System.loadLibrary("uvcnative")
    }

    external fun nativeStatus(fd: Int): String

    external fun isoSmokeTest(
        fd: Int,
        interfaceId: Int,
        altSetting: Int,
        endpointAddress: Int,
        packetSize: Int,
        packetCount: Int,
    ): String

    external fun captureIsoPackets(
        fd: Int,
        interfaceId: Int,
        altSetting: Int,
        endpointAddress: Int,
        packetSize: Int,
        packetsPerUrb: Int,
        maxOutputBytes: Int,
        timeoutMs: Int,
    ): ByteArray?

    external fun startIsoStream(
        fd: Int,
        interfaceId: Int,
        altSetting: Int,
        endpointAddress: Int,
        packetSize: Int,
        packetsPerUrb: Int,
        urbCount: Int,
    ): String

    external fun readIsoStream(
        fd: Int,
        maxOutputBytes: Int,
        timeoutMs: Int,
    ): ByteArray?

    external fun stopIsoStream(fd: Int): String
}
