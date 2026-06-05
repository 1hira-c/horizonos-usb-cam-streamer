/*
 * Copyright (c) 2026 1hira
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package jp.hitohira.usbcamstreamer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import jp.hitohira.usbcamstreamer.usb.StreamingService
import jp.hitohira.usbcamstreamer.usb.UsbRepository
import jp.hitohira.usbcamstreamer.ui.CameraPanel
import jp.hitohira.usbcamstreamer.ui.DeviceListScreen

/**
 * 単一画面の Activity。配信状態を保持するのは [StreamingService] 側で、本 Activity は
 * サービスに bind して [UsbRepository] を取得し、UI 描画と操作を行うだけ（配信は Activity の
 * 寿命に依存しない）。
 */
class MainActivity : ComponentActivity() {

  // バインド成立後にサービスから受け取る repo。Compose はこの State を購読し、
  // 接続前はプレースホルダ、接続後は本 UI を描画する。
  private var repo by mutableStateOf<UsbRepository?>(null)
  private var binder: StreamingService.LocalBinder? = null
  private var bound = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      binder = service as StreamingService.LocalBinder
      repo = binder?.getRepo()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      binder = null
      repo = null
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      SpatialTheme {
        CompositionLocalProvider(
            LocalContentColor provides LocalColorScheme.current.primaryAlphaBackground,
        ) {
          val current = repo
          if (current == null) {
            ConnectingScreen()
          } else {
            CameraStreamApp(
                repo = current,
                onStartStream = { startStreaming(current, it) },
                onStopAll = { binder?.stopAll() },
            )
          }
        }
      }
    }

    // onCreate〜onDestroy でバインドを維持する。こうすると未配信のまま一時的に
    // バックグラウンドへ回っても（Activity が破棄されない限り）サービスは bound として
    // 生存し、open 済みカメラのセッションが失われない。配信中は FGS でさらに延命される。
    bound = bindService(
        Intent(this, StreamingService::class.java), connection, Context.BIND_AUTO_CREATE,
    )
  }

  override fun onDestroy() {
    super.onDestroy()
    if (bound) {
      runCatching { unbindService(connection) }
      bound = false
    }
    binder = null
    repo = null
  }

  /** 配信を開始し、実際に開始できたときだけサービスを前面化する（失敗時は前面化しない）。 */
  private fun startStreaming(repo: UsbRepository, deviceName: String) {
    if (!repo.startStreaming(deviceName)) return
    ContextCompat.startForegroundService(
        this,
        Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_ENSURE_FOREGROUND),
    )
  }
}

/** サービスへの bind 完了待ち。 */
@Composable
private fun ConnectingScreen() {
  Box(
      modifier = Modifier.fillMaxSize().background(brush = LocalColorScheme.current.panel),
      contentAlignment = Alignment.Center,
  ) {
    Text("サービスに接続中…", style = SpatialTheme.typography.body1)
  }
}

/**
 * マルチカメラ UVC→PC ストリーミングの単一画面。
 * 上部にデバイス一覧（複数接続可）、下部に接続済みカメラのパネルを横並び（最大3台で横スクロール）。
 */
@Composable
fun CameraStreamApp(
    repo: UsbRepository,
    onStartStream: (String) -> Unit,
    onStopAll: () -> Unit,
) {
  val devices by repo.devices.collectAsStateWithLifecycle()
  val cameras by repo.cameras.collectAsStateWithLifecycle()
  val cpu by repo.cpu.collectAsStateWithLifecycle()
  val lastError by repo.lastError.collectAsStateWithLifecycle()
  val connectedNames = remember(cameras) { cameras.map { it.deviceName }.toSet() }
  val anyStreaming = remember(cameras) { cameras.any { it.streaming } }

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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = "CPU: %.0f%% (1コア基準) / %dコア".format(cpu.processPercent, cpu.cores),
          style = SpatialTheme.typography.body1,
          fontFamily = FontFamily.Monospace,
      )
      if (anyStreaming) {
        OutlinedButton(onClick = onStopAll) {
          Text("全停止")
        }
      }
    }
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
              onStartStream = { onStartStream(cam.deviceName) },
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("権限状態: $status", style = SpatialTheme.typography.body1)
      OutlinedButton(onClick = { launcher.launch(requested) }) {
        Text("権限要求")
      }
    }
  }
}
