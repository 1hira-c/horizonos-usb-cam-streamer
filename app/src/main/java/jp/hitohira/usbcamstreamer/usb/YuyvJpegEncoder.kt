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
        if (yuy2.size < width * height * 2) return null
        val image = YuvImage(yuy2, ImageFormat.YUY2, width, height, null)
        val baos = ByteArrayOutputStream()
        val ok = image.compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(1, 100), baos)
        return if (ok) baos.toByteArray() else null
    }
}
