package jp.hitohira.usbcamstreamer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.spatial.uiset.card.SecondaryCard
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import jp.hitohira.usbcamstreamer.usb.CameraUiState
import jp.hitohira.usbcamstreamer.usb.DeviceSummary

/**
 * Cameras タブ。検出した USB デバイスを一覧表示し、各行のトグルで接続＋配信を制御する。
 * 行レイアウト（左→右）: アイコン / 名前・状態 / 解像度・fps セレクタ / トグル。
 */
@Composable
fun CamerasTab(
    devices: List<DeviceSummary>,
    cameras: List<CameraUiState>,
    pendingNames: Set<String>,
    onRefresh: () -> Unit,
    onToggleDevice: (String, Boolean) -> Unit,
    onSelectFormat: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val camByName = remember(cameras) { cameras.associateBy { it.deviceName } }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("USB デバイス (${devices.size})", style = SpatialTheme.typography.body1Strong)
            AppOutlinedButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("再列挙")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (devices.isEmpty()) {
            Text(
                "デバイスがありません。OTG で USB デバイス(UVCカメラ等)を接続してください。",
                style = SpatialTheme.typography.body1,
                color = LocalColorScheme.current.secondaryAlphaBackground,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.deviceName }) { d ->
                    DeviceRow(
                        device = d,
                        camera = camByName[d.deviceName],
                        pending = d.deviceName in pendingNames,
                        onToggle = { on -> onToggleDevice(d.deviceName, on) },
                        onSelectFormat = { idx -> onSelectFormat(d.deviceName, idx) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DeviceSummary,
    camera: CameraUiState?,
    pending: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelectFormat: (Int) -> Unit,
) {
    val connected = camera != null
    val streaming = camera?.streaming == true
    val checked = streaming || pending

    val (statusText, statusColor) = when {
        streaming -> "配信中" to StreamingColor
        connected -> "接続済み" to LocalColorScheme.current.primaryAlphaBackground
        pending -> "接続中…" to StoppedColor
        else -> "利用可能" to LocalColorScheme.current.secondaryAlphaBackground
    }

    SecondaryCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (streaming) StreamingColor.copy(alpha = 0.2f) else StoppedColor.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = if (streaming) StreamingColor else LocalColorScheme.current.secondaryAlphaBackground,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    device.productName ?: device.deviceName,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    "VID:PID=${device.vidPid}" + if (device.isLikelyUvc) "  ・UVC候補" else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = LocalColorScheme.current.secondaryAlphaBackground,
                )
                Text(statusText, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Bold)
            }

            // 解像度・フレームレート選択（トグルの左）。接続後のみ有効。
            if (camera != null) {
                ResolutionDropdown(state = camera, onSelectFormat = onSelectFormat)
            } else {
                AppOutlinedButton(onClick = {}, enabled = false) { Text("接続後に選択可") }
            }

            Switch(checked = checked, onCheckedChange = onToggle, colors = appSwitchColors())
        }
    }
}
