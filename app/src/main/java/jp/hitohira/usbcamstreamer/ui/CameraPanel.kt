package jp.hitohira.usbcamstreamer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.hitohira.usbcamstreamer.usb.CameraUiState

/** 配信中=緑 / 停止=グレー。各所のステータス色で共通利用する。 */
val StreamingColor = Color(0xFF4CAF50)
val StoppedColor = Color(0xFF9E9E9E)
private val StatsColor = Color(0xFF90CAF9)
private val ErrorColor = Color(0xFFF44336)

/** ダーク基調の Spatial パネル上でも視認できるアクセス色（コントラスト確保用）。 */
val AccentColor = Color(0xFF3E90FF)
private val DisabledBorder = Color(0xFF555B66)
private val DisabledContent = Color(0xFF8A8F99)

/** 塗りつぶしボタン（主要操作）。アクセント背景＋白文字で高コントラスト。 */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentColor,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF3A3F47),
            disabledContentColor = DisabledContent,
        ),
        content = content,
    )
}

/** 枠線ボタン（副操作）。アクセント色の枠線＋文字でダーク背景に映えるように。 */
@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        border = BorderStroke(1.5.dp, if (enabled) AccentColor else DisabledBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = AccentColor,
            disabledContentColor = DisabledContent,
        ),
        content = content,
    )
}

/** スイッチ共通色。ON=緑トラック / OFF=視認できる濃グレー＋明るいツマミ。 */
@Composable
fun appSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = StreamingColor,
    checkedBorderColor = StreamingColor,
    uncheckedThumbColor = Color(0xFFE6E6E6),
    uncheckedTrackColor = Color(0xFF5A606B),
    uncheckedBorderColor = Color(0xFF8A8F99),
)

/**
 * 解像度・フレームレート選択ドロップダウン。[FormatOption.label] は "WxH 形式 @fps" を表す。
 * フォーマットは接続後にのみ得られるため、空のときは無効化される。
 */
@Composable
fun ResolutionDropdown(
    state: CameraUiState,
    onSelectFormat: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = state.formats.getOrNull(state.selectedFormatIndex)?.label ?: "接続後に選択可"
    Box(modifier) {
        AppOutlinedButton(
            onClick = { expanded = true },
            enabled = state.formats.isNotEmpty(),
        ) {
            Text("$current", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.formats.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.label) },
                    onClick = {
                        onSelectFormat(f.index)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * ライブプレビュー画像。[CameraUiState.previewJpeg] をデコードして表示し、無いときは
 * videocam_off プレースホルダを描く。デコード負荷回避のため [show]=false では描画しない。
 */
@Composable
fun CameraPreviewImage(
    state: CameraUiState,
    modifier: Modifier = Modifier,
    show: Boolean = true,
) {
    val previewBytes = state.previewJpeg
    val bitmap = remember(previewBytes, state.previewVersion, show) {
        if (show && previewBytes != null) {
            BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
        } else {
            null
        }
    }
    Box(modifier.background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "UVC live preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                Icons.Filled.VideocamOff,
                contentDescription = null,
                tint = StoppedColor,
                modifier = Modifier.height(40.dp),
            )
        }
    }
}

/** 配信ステータス・URL・統計・警告・エラーの最小表示。Cameras/Preview 双方から利用。 */
@Composable
fun CameraStatusText(state: CameraUiState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (state.streamUrl.isNotBlank()) {
            Text(
                "PC で開く: ${state.streamUrl}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (state.streaming) StreamingColor else StoppedColor,
            )
        }
        if (state.previewStats.isNotBlank()) {
            Text(state.previewStats, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = StatsColor)
        }
        if (state.streamStats.isNotBlank()) {
            Text(state.streamStats, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = StatsColor)
        }
        state.error?.let {
            Text(it, fontSize = 11.sp, color = ErrorColor)
        }
    }
}
