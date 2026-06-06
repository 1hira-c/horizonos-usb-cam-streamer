package jp.hitohira.usbcamstreamer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.spatial.uiset.card.SecondaryCard
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import jp.hitohira.usbcamstreamer.usb.CameraUiState

/**
 * Preview タブ。接続済みカメラを 2 列グリッドでライブ表示する。各タイルは
 * フレームレート等の統計を常時表示し、切断もタイルから直接行える。
 */
@Composable
fun PreviewTab(
    cameras: List<CameraUiState>,
    onStartStream: (String) -> Unit,
    onStopStream: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cameras.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "カメラ未接続。Cameras タブで UVC デバイスを接続してください。",
                style = SpatialTheme.typography.body1,
                color = LocalColorScheme.current.secondaryAlphaBackground,
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cameras, key = { it.deviceName }) { cam ->
                PreviewTile(
                    cam = cam,
                    onStartStream = { onStartStream(cam.deviceName) },
                    onStopStream = { onStopStream(cam.deviceName) },
                    onDisconnect = { onDisconnect(cam.deviceName) },
                )
            }
        }
    }
}

@Composable
private fun PreviewTile(
    cam: CameraUiState,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onDisconnect: () -> Unit,
) {
    SecondaryCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
            ) {
                CameraPreviewImage(state = cam, modifier = Modifier.fillMaxSize(), show = true)
                // デバイス名ラベル（左上）。
                Text(
                    cam.title,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                // 配信中インジケータ（右上）。
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (cam.streaming) StreamingColor else StoppedColor),
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                if (cam.streaming) "STREAMING" else "STOPPED",
                color = if (cam.streaming) StreamingColor else StoppedColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cam.streaming) {
                    AppOutlinedButton(onClick = onStopStream) { Text("配信停止", fontSize = 12.sp) }
                } else {
                    AppButton(onClick = onStartStream) { Text("配信開始", fontSize = 12.sp) }
                }
                AppOutlinedButton(onClick = onDisconnect) { Text("切断", fontSize = 12.sp) }
            }

            // フレームレート等の統計を常時表示（URL・preview/stream stats・警告・エラー）。
            Spacer(Modifier.height(4.dp))
            CameraStatusText(state = cam, modifier = Modifier.fillMaxWidth())
        }
    }
}
