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
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import jp.hitohira.usbcamstreamer.ui.CamerasTab
import jp.hitohira.usbcamstreamer.ui.DashboardScaffold
import jp.hitohira.usbcamstreamer.ui.DashboardTab
import jp.hitohira.usbcamstreamer.ui.PreviewTab
import jp.hitohira.usbcamstreamer.ui.SystemTab
import jp.hitohira.usbcamstreamer.usb.StreamingService
import jp.hitohira.usbcamstreamer.usb.UsbRepository

/**
 * 単一画面の Activity。配信状態を保持するのは [StreamingService] 側で、本 Activity は
 * サービスに bind して [UsbRepository] を取得し、UI 描画と操作を行うだけ（配信は Activity の
 * 寿命に依存しない）。UI は Header + 3 タブ(Cameras / Preview / System) + Footer のダッシュボード。
 */
class MainActivity : ComponentActivity() {

  // バインド成立後にサービスから受け取る repo。Compose はこの State を購読し、
  // 接続前はプレースホルダ、接続後は本 UI を描画する。
  private var repo by mutableStateOf<UsbRepository?>(null)
  private var binder: StreamingService.LocalBinder? = null
  private var bound = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      // in-process の local bind なので通常は必ず LocalBinder だが、安全キャストで堅牢に。
      binder = service as? StreamingService.LocalBinder
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
    // 権限ダイアログ直後など非フォアグラウンドな瞬間に呼ばれると
    // ForegroundServiceStartNotAllowedException で落ちうるため握りつぶす（配信自体は継続）。
    runCatching {
      ContextCompat.startForegroundService(
          this,
          Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_ENSURE_FOREGROUND),
      )
    }
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
 * マルチカメラ UVC→PC ストリーミングのダッシュボード。Cameras タブで接続＋配信トグル、
 * Preview タブでライブ表示＋一括配信、System タブで権限・CPU・ログを表示する。
 */
@Composable
fun CameraStreamApp(
    repo: UsbRepository,
    onStartStream: (String) -> Unit,
) {
  val devices by repo.devices.collectAsStateWithLifecycle()
  val cameras by repo.cameras.collectAsStateWithLifecycle()
  val cpu by repo.cpu.collectAsStateWithLifecycle()
  val logs by repo.logs.collectAsStateWithLifecycle()
  val anyStreaming = remember(cameras) { cameras.any { it.streaming } }

  var selectedTab by remember { mutableStateOf(DashboardTab.Cameras) }

  // トグル ON されたが未接続のデバイス名。接続完了後に自動で配信を開始するための保留集合。
  val pendingStart = remember { mutableStateMapOf<String, Boolean>() }

  // 接続が成立した保留デバイスは、ここで一度だけ配信開始を発火し保留から外す。
  LaunchedEffect(cameras) {
    pendingStart.keys.toList().forEach { name ->
      if (cameras.any { it.deviceName == name }) {
        onStartStream(name)
        pendingStart.remove(name)
      }
    }
  }

  // Cameras タブのトグル: ON=接続＋配信開始 / OFF=配信停止＋切断。
  val onToggleDevice: (String, Boolean) -> Unit = { deviceName, on ->
    val cam = cameras.find { it.deviceName == deviceName }
    if (on) {
      when {
        cam == null -> {
          // 未接続: 接続要求（権限要求含む）→ 接続成立後に LaunchedEffect が配信開始。
          repo.requestConnect(deviceName)
          pendingStart[deviceName] = true
        }
        !cam.streaming -> onStartStream(deviceName)
      }
    } else {
      pendingStart.remove(deviceName)
      repo.stopStreaming(deviceName)
      repo.disconnect(deviceName)
    }
  }

  DashboardScaffold(
      selectedTab = selectedTab,
      onSelectTab = { selectedTab = it },
      note = "Horizon OS / USB(usbfs) 経由・最大3カメラ同時配信（開発者向けツール）",
      serviceRunning = anyStreaming,
  ) { tab ->
    when (tab) {
      DashboardTab.Cameras ->
          CamerasTab(
              devices = devices,
              cameras = cameras,
              pendingNames = pendingStart.keys,
              onRefresh = { repo.refresh() },
              onToggleDevice = onToggleDevice,
              onSelectFormat = { name, idx -> repo.setFormat(name, idx) },
          )
      DashboardTab.Preview ->
          PreviewTab(
              cameras = cameras,
              onStartStream = onStartStream,
              onStopStream = { repo.stopStreaming(it) },
              onDisconnect = { repo.disconnect(it) },
          )
      DashboardTab.System ->
          SystemTab(cpu = cpu, logs = logs)
    }
  }
}
