package jp.hitohira.usbcamstreamer.usb

/** 接続済みカメラ 1 台ぶんの UI 状態。[CameraSession] が更新し、UI が購読する。 */
data class CameraUiState(
    val deviceName: String,
    val title: String,
    val vidPid: String,
    val fd: Int,
    val port: Int,
    val formats: List<FormatOption> = emptyList(),
    val selectedFormatIndex: Int = 0,
    val streaming: Boolean = false,
    val streamUrl: String = "",
    val streamStats: String = "",
    val previewStats: String = "",
    val previewJpeg: ByteArray? = null,
    val previewVersion: Long = 0,
    val error: String? = null,
)

/** 解像度ドロップダウンの 1 項目。[index] は選択可能 MJPEG フォーマット一覧内の添字。 */
data class FormatOption(
    val index: Int,
    val label: String,
    val width: Int,
    val height: Int,
)

/** CPU 使用率（自プロセス, 1 コア基準の % と総コア数）。 */
data class CpuStats(
    val processPercent: Double = 0.0,
    val cores: Int = Runtime.getRuntime().availableProcessors(),
)

data class IsoInCandidate(
    val interfaceId: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    val packetSize: Int,
)
