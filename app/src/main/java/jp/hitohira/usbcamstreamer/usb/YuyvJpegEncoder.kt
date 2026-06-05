package jp.hitohira.usbcamstreamer.usb

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/**
 * 生 YUYV(YUY2) フレームを JPEG にエンコードする。
 *
 * Android の [YuvImage].compressToJpeg は Skia 経由で libjpeg-turbo(arm64 NEON) を呼ぶため、
 * 独自ネイティブ実装と CPU コストはほぼ同等。かつ中間 Bitmap を作らず YUY2 を直接エンコードする。
 * UVC の Uncompressed YUY2 は [ImageFormat.YUY2]（Y0 Cb Y1 Cr 並び）とバイト順が一致する。
 */
object YuyvJpegEncoder {
    /** CPU 抑制を優先した既定の JPEG 品質。実機計測で調整可能。 */
    const val DEFAULT_QUALITY = 75

    /**
     * @return エンコード済み JPEG バイト列。入力長が不足する場合は null。
     */
    fun encode(yuy2: ByteArray, width: Int, height: Int, quality: Int = DEFAULT_QUALITY): ByteArray? {
        if (width <= 0 || height <= 0) return null
        // Int オーバーフローを避けるため期待サイズは Long で計算してから検証する。
        val expected = width.toLong() * height.toLong() * 2L
        if (expected <= 0L || expected > Int.MAX_VALUE.toLong() || yuy2.size.toLong() < expected) return null
        val image = YuvImage(yuy2, ImageFormat.YUY2, width, height, null)
        // 圧縮後サイズの目安(YUY2 の約 1/8)で初期確保し、再確保コピーを抑える。
        val initialCapacity = (width * height / 4).coerceIn(1024, 4 * 1024 * 1024)
        val baos = ByteArrayOutputStream(initialCapacity)
        val ok = image.compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(1, 100), baos)
        return if (ok) baos.toByteArray() else null
    }
}
