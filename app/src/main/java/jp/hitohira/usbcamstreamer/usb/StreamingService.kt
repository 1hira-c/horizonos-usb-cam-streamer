/*
 * Copyright (c) 2026 1hira
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package jp.hitohira.usbcamstreamer.usb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import jp.hitohira.usbcamstreamer.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * UVC キャプチャ→MJPEG 配信を Activity の寿命から切り離して継続させるフォアグラウンドサービス。
 *
 * 設計:
 *  - [UsbRepository] の単一インスタンスを本サービスが所有する（生成・[UsbRepository.register]・
 *    [UsbRepository.unregister] はすべてここで行う）。Activity は [LocalBinder] 経由で repo を
 *    取得し、状態購読と操作を行うだけ。
 *  - 配信が 1 台でも始まったら（Activity から [ACTION_ENSURE_FOREGROUND] を受けて）前面化し、
 *    別アプリ使用中もプロセスを生存させる。全配信が止まったら前面化を解除して [stopSelf]。
 *  - bind 中はサービスが生存し続けるため、配信していない間は「通常の bound サービス」として
 *    Activity の UI 用に存在し、Activity が unbind すると破棄される。
 */
class StreamingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: UsbRepository
    private var isForeground = false

    inner class LocalBinder : Binder() {
        fun getRepo(): UsbRepository = repo

        /** アプリ内「全停止」用。通知の「停止」（[ACTION_STOP_ALL]）と同一の経路を通す。 */
        fun stopAll() = stopAllAndExit()
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        repo = UsbRepository(this)
        repo.register()
        createChannel()

        // 配信中台数を監視し、0 になったら前面化を解除して自己終了する。
        scope.launch {
            repo.cameras.collect { cameras ->
                val streaming = cameras.filter { it.streaming }
                if (streaming.isNotEmpty()) {
                    if (isForeground) updateNotification(streaming)
                } else if (isForeground) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENSURE_FOREGROUND -> enterForeground()
            ACTION_STOP_ALL -> stopAllAndExit()
        }
        // START_NOT_STICKY: プロセス死亡時に null intent で再生成されても fd は失われており
        // 配信は再開できない。意図せぬゾンビ常駐を避けるため再起動しない。
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 「配信中のみ常駐」方針: タスクをスワイプ削除したら配信を止めてサービス終了。
        stopAllAndExit()
        super.onTaskRemoved(rootIntent)
    }

    /** 全配信を止め、前面化を解除してサービスを終了する（全停止の唯一の経路）。 */
    private fun stopAllAndExit() {
        repo.stopAllStreaming()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { repo.unregister() }
        super.onDestroy()
    }

    private fun enterForeground() {
        if (isForeground) {
            // 既に前面化済みなら通知だけ更新（2台目の開始など）。
            updateNotification(repo.cameras.value.filter { it.streaming })
            return
        }
        val streaming = repo.cameras.value.filter { it.streaming }
        // connectedDevice 型はデバイス未接続/権限失効のタイミングで startForeground が
        // 例外を投げうる。投げてもプロセスを巻き込まないよう保護し、失敗時は撤収する。
        val result = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(streaming),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
        if (result.isSuccess) {
            isForeground = true
        } else {
            // 握りつぶさずログに残す（FGS 起動制限/権限不足の切り分け用）。
            val t = result.exceptionOrNull()
            repo.log(LogLevel.ERROR, "startForeground 失敗: ${t?.javaClass?.simpleName}: ${t?.message}")
            repo.stopAllStreaming()
            stopSelf()
        }
    }

    private fun updateNotification(streaming: List<CameraUiState>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(streaming))
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
    }

    private fun buildNotification(streaming: List<CameraUiState>): android.app.Notification {
        val ports = streaming.map { it.port }.sorted().joinToString(", ")
        val text = if (streaming.isEmpty()) {
            "配信準備中…"
        } else {
            "配信中: ${streaming.size}台 (ポート $ports)"
        }

        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UVC → PC 配信中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "UVC ストリーミング",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "別アプリ使用中も配信を継続するための常駐通知" }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_ENSURE_FOREGROUND = "jp.hitohira.usbcamstreamer.ENSURE_FOREGROUND"
        const val ACTION_STOP_ALL = "jp.hitohira.usbcamstreamer.STOP_ALL"
        private const val CHANNEL_ID = "uvc_streaming"
        private const val NOTIF_ID = 1
    }
}
