package jp.hitohira.usbcamstreamer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.hitohira.usbcamstreamer.usb.CameraUiState

/**
 * 接続済みカメラ 1 台ぶんの配信コントロールパネル。
 * 解像度ドロップダウン・配信開始/停止・プレビュー表示トグル・切断・最小ステータスを持つ。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraPanel(
    state: CameraUiState,
    onSelectFormat: (Int) -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 本体プレビュー表示トグル。OFF で Image を描かない＝デコード負荷を回避（配信は継続）。
    var showPreview by remember { mutableStateOf(true) }

    Card(modifier) {
        Column(
            Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(state.title, fontWeight = FontWeight.Bold)
            Text(
                "VID:PID=${state.vidPid}  fd=${state.fd}  port=${state.port}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )

            ResolutionDropdown(state = state, onSelectFormat = onSelectFormat)

            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.streaming) {
                    Button(onClick = onStartStream) { Text("配信開始") }
                } else {
                    Button(onClick = onStopStream) { Text("配信停止") }
                }
                OutlinedButton(onClick = { showPreview = !showPreview }) {
                    Text(if (showPreview) "プレビュー: ON" else "プレビュー: OFF")
                }
                OutlinedButton(onClick = onDisconnect) { Text("切断") }
            }

            Text(
                if (state.streaming) "STREAMING" else "STOPPED",
                color = if (state.streaming) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            if (state.streamUrl.isNotBlank()) {
                Text(
                    "PC で開く: ${state.streamUrl}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (state.streaming) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                )
            }
            if (state.previewStats.isNotBlank()) {
                Text(state.previewStats, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF90CAF9))
            }
            if (state.streamStats.isNotBlank()) {
                Text(state.streamStats, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF90CAF9))
            }
            if (state.whiteLike) {
                Text("⚠ フレームがほぼ白＝露出/フォーマット不一致の可能性", fontSize = 11.sp, color = Color(0xFFFF9800))
            }
            state.error?.let {
                Text(it, fontSize = 11.sp, color = Color(0xFFF44336))
            }

            val previewBytes = state.previewJpeg
            if (showPreview && previewBytes != null) {
                val bitmap = remember(previewBytes, state.previewVersion) {
                    BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "UVC live preview",
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(60.dp)) {
                        Text("JPEG decode 待ち", color = Color(0xFF9E9E9E), modifier = Modifier.padding(8.dp))
                    }
                }
            } else if (!showPreview) {
                Text("プレビュー OFF（配信は継続中）", fontSize = 11.sp, color = Color(0xFF9E9E9E))
            }
        }
    }
}

@Composable
private fun ResolutionDropdown(state: CameraUiState, onSelectFormat: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = state.formats.getOrNull(state.selectedFormatIndex)?.label ?: "解像度なし"
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = state.formats.isNotEmpty(),
        ) {
            Text("解像度: $current")
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
