package jp.hitohira.usbcamstreamer.usb

import android.hardware.usb.UsbDevice

/**
 * UI に表示するためのデバイス概要。[UsbDevice] そのものは Parcelable だが、
 * Compose の state として扱いやすいよう必要な値を取り出しておく。
 */
data class DeviceSummary(
    val deviceName: String,          // 例: /dev/bus/usb/001/002
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val manufacturerName: String?,
    val deviceClass: Int,
    val interfaceCount: Int,
    val isLikelyUvc: Boolean,
) {
    val vidPid: String get() = "%04x:%04x".format(vendorId, productId)
}

/** 接続状態。UI 側はこれを見て画面を切り替える。 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class PermissionRequested(val deviceName: String) : ConnectionState
    data class Connected(
        val summary: DeviceSummary,
        val fileDescriptor: Int,     // Phase 2 で libusb に渡す native fd
    ) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/** ログ 1 行。種別で色分けする。 */
data class LogLine(
    val seq: Long,
    val level: LogLevel,
    val text: String,
)

enum class LogLevel { INFO, OK, WARN, ERROR }

/** 遮断診断の 1 ステップ。どの段階で塞がれたかを切り分けるための構造化結果。 */
data class DiagStep(
    val seq: Long,
    val name: String,
    val status: DiagStatus,
    val detail: String,
)

enum class DiagStatus { PASS, FAIL, WARN, INFO }

/** 16進ダンプ用のユーティリティ。 */
fun ByteArray.toHex(maxBytes: Int = Int.MAX_VALUE): String {
    val n = minOf(size, maxBytes)
    val sb = StringBuilder(n * 3)
    for (i in 0 until n) {
        sb.append("%02x".format(this[i].toInt() and 0xFF))
        if (i != n - 1) sb.append(' ')
    }
    if (size > n) sb.append(" …(+${size - n}B)")
    return sb.toString()
}
