/*
 * Copyright (c) 2026 1hira
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package jp.hitohira.usbcamstreamer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.pm.PackageManager
import com.meta.spatial.uiset.card.SecondaryCard
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import jp.hitohira.usbcamstreamer.usb.UsbRepository
import jp.hitohira.usbcamstreamer.ui.CameraPanel
import jp.hitohira.usbcamstreamer.ui.DeviceListScreen

class MainActivity : ComponentActivity() {
  private lateinit var repo: UsbRepository

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    repo = UsbRepository(this)
    repo.register()

    setContent {
      SpatialTheme {
        CompositionLocalProvider(
            LocalContentColor provides LocalColorScheme.current.primaryAlphaBackground,
        ) {
          CameraStreamApp(repo)
        }
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    repo.unregister()
  }
}

/**
 * マルチカメラ UVC→PC ストリーミングの単一画面。
 * 上部にデバイス一覧（複数接続可）、下部に接続済みカメラのパネルを横並び（最大3台で横スクロール）。
 */
@Composable
fun CameraStreamApp(repo: UsbRepository) {
  val devices by repo.devices.collectAsStateWithLifecycle()
  val cameras by repo.cameras.collectAsStateWithLifecycle()
  val cpu by repo.cpu.collectAsStateWithLifecycle()
  val lastError by repo.lastError.collectAsStateWithLifecycle()
  val connectedNames = remember(cameras) { cameras.map { it.deviceName }.toSet() }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .clip(SpatialTheme.shapes.large)
              .background(brush = LocalColorScheme.current.panel)
              .padding(24.dp),
  ) {
    Text(
        text = "UVC → PC マルチストリーミング",
        style = SpatialTheme.typography.headline1,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "Horizon OS / USB(usbfs) 経由・最大3カメラ同時配信（開発者向けツール）",
        style = SpatialTheme.typography.body1,
        color = LocalColorScheme.current.secondaryAlphaBackground,
    )

    Spacer(modifier = Modifier.height(8.dp))
    CameraPermissionRow()
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "CPU: %.0f%% (1コア基準) / %dコア".format(cpu.processPercent, cpu.cores),
        style = SpatialTheme.typography.body1,
        fontFamily = FontFamily.Monospace,
    )
    Spacer(modifier = Modifier.height(8.dp))

    // デバイス一覧（接続用・コンパクト）。
    Box(modifier = Modifier.height(190.dp).fillMaxWidth()) {
      SecondaryCard(modifier = Modifier.fillMaxSize()) {
        DeviceListScreen(
            devices = devices,
            connectedNames = connectedNames,
            onRefresh = { repo.refresh() },
            onConnect = { repo.requestConnect(it) },
        )
      }
    }
    lastError?.let {
      Text(it, modifier = Modifier.padding(top = 6.dp), color = Color.Red)
    }
    Spacer(modifier = Modifier.height(8.dp))

    // 接続済みカメラのパネル（横並び、3台時は横スクロール）。
    if (cameras.isEmpty()) {
      Text(
          "カメラ未接続。上の一覧から UVC デバイスを接続してください。",
          style = SpatialTheme.typography.body1,
          color = LocalColorScheme.current.secondaryAlphaBackground,
      )
    } else {
      LazyRow(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(cameras, key = { it.deviceName }) { cam ->
          CameraPanel(
              state = cam,
              onSelectFormat = { repo.setFormat(cam.deviceName, it) },
              onStartStream = { repo.startStreaming(cam.deviceName) },
              onStopStream = { repo.stopStreaming(cam.deviceName) },
              onDisconnect = { repo.disconnect(cam.deviceName) },
              modifier = Modifier.fillParentMaxWidth(0.5f).fillMaxHeight(),
          )
        }
      }
    }
  }
}

/** USB_CAMERA 権限の状態表示＋要求。Horizon OS では UVC アクセスはこの権限のみがゲート。 */
@Composable
private fun CameraPermissionRow() {
  val context = LocalContext.current
  val usbCamera = "horizonos.permission.USB_CAMERA"

  fun granted(p: String) =
      ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

  var status by remember { mutableStateOf("") }
  fun refresh() {
    status = "USB_CAMERA=${if (granted(usbCamera)) "許可" else "未許可/未知"}"
  }

  val launcher = rememberLauncherForActivityResult(
      ActivityResultContracts.RequestMultiplePermissions(),
  ) { refresh() }

  LaunchedEffect(Unit) {
    refresh()
    launcher.launch(arrayOf(usbCamera))
  }

  SecondaryCard(modifier = Modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("権限状態: $status", style = SpatialTheme.typography.body1)
      OutlinedButton(onClick = { launcher.launch(arrayOf(usbCamera)) }) {
        Text("権限要求")
      }
    }
  }
}
