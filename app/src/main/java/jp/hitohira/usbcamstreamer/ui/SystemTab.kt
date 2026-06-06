package jp.hitohira.usbcamstreamer.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meta.spatial.uiset.card.SecondaryCard
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import jp.hitohira.usbcamstreamer.usb.CpuStats
import jp.hitohira.usbcamstreamer.usb.LogLevel
import jp.hitohira.usbcamstreamer.usb.LogLine

/**
 * System タブ。アクセス権限（USB_CAMERA）の状態と再リクエスト、CPU 使用率、
 * システムイベントログ（[UsbRepository.logs]）を表示する。
 */
@Composable
fun SystemTab(
    cpu: CpuStats,
    logs: List<LogLine>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionCard()

        SecondaryCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "CPU: %.0f%% (1コア基準) / %dコア".format(cpu.processPercent, cpu.cores),
                    fontFamily = FontFamily.Monospace,
                    style = SpatialTheme.typography.body1,
                )
            }
        }

        Text(
            "システムイベントログ",
            style = SpatialTheme.typography.body1Strong,
            color = LocalColorScheme.current.secondaryAlphaBackground,
        )
        SecondaryCard(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (logs.isEmpty()) {
                Text(
                    "ログはまだありません。",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = LocalColorScheme.current.secondaryAlphaBackground,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(8.dp),
                    reverseLayout = true,
                ) {
                    items(logs.asReversed(), key = { it.seq }) { line ->
                        Text(
                            line.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = colorFor(line.level),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun colorFor(level: LogLevel): Color = when (level) {
    LogLevel.OK -> StreamingColor
    LogLevel.WARN -> Color(0xFFFF9800)
    LogLevel.ERROR -> Color(0xFFF44336)
    LogLevel.INFO -> LocalColorScheme.current.secondaryAlphaBackground
}

/** USB_CAMERA 権限の状態表示＋再リクエスト。Horizon OS では UVC アクセスはこの権限のみがゲート。 */
@Composable
private fun PermissionCard() {
    val context = LocalContext.current
    val usbCamera = "horizonos.permission.USB_CAMERA"

    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var grantedState by remember { mutableStateOf(false) }
    fun refresh() { grantedState = granted(usbCamera) }

    // USB_CAMERA に加え、Android 13+ では常駐通知のため POST_NOTIFICATIONS も要求する。
    val requested = remember {
        buildList {
            add(usbCamera)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("android.permission.POST_NOTIFICATIONS")
            }
        }.toTypedArray()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

    LaunchedEffect(Unit) {
        refresh()
        launcher.launch(requested)
    }

    SecondaryCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("アクセス権限", style = SpatialTheme.typography.body1Strong)
                    Text(
                        "USB_CAMERA（UVC アクセスのゲート）",
                        fontSize = 11.sp,
                        color = LocalColorScheme.current.secondaryAlphaBackground,
                    )
                }
                StatusBadge(granted = grantedState)
            }
            Spacer(Modifier.height(10.dp))
            AppButton(onClick = { launcher.launch(requested) }, modifier = Modifier.fillMaxWidth()) {
                Text("権限を再リクエスト")
            }
        }
    }
}

@Composable
private fun StatusBadge(granted: Boolean) {
    val color = if (granted) StreamingColor else Color(0xFFFF9800)
    Text(
        if (granted) "認証済み" else "未認証",
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
