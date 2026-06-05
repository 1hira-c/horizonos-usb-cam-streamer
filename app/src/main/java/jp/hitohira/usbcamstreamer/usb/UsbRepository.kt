package jp.hitohira.usbcamstreamer.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * USB Host API のラッパ兼マルチカメラのコーディネータ。
 * 列挙 / 権限要求 / open を担い、接続済みカメラを fd ごとの [CameraSession] として束ねる。
 * 状態は [StateFlow] で公開し、Compose 側が observe して描く。
 *
 * 使い方:
 *  - Activity の onCreate で [register]、onDestroy で [unregister]。
 *  - [refresh] で一覧更新、[requestConnect] で権限要求→open（複数台を順次接続できる）。
 */
class UsbRepository(context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<DeviceSummary>>(emptyList())
    val devices: StateFlow<List<DeviceSummary>> = _devices.asStateFlow()

    /** 接続済みカメラの UI 状態（カメラ単位）。 */
    private val _cameras = MutableStateFlow<List<CameraUiState>>(emptyList())
    val cameras: StateFlow<List<CameraUiState>> = _cameras.asStateFlow()

    /** CPU 使用率（自プロセス）。 */
    private val _cpu = MutableStateFlow(CpuStats())
    val cpu: StateFlow<CpuStats> = _cpu.asStateFlow()

    /** 直近の接続エラー（デバイス一覧の下に表示）。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _diag = MutableStateFlow<List<DiagStep>>(emptyList())
    val diag: StateFlow<List<DiagStep>> = _diag.asStateFlow()

    private val emit = BlockDiagnostics.Emit { name, status, detail -> recordDiag(name, status, detail) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // deviceName -> セッション / その UI 集約用コレクタ Job。
    private val sessions = LinkedHashMap<String, CameraSession>()
    private val collectors = LinkedHashMap<String, Job>()

    private val cpuMonitor = CpuMonitor()
    private val seq = AtomicLong(0)

    // --- ログ ---------------------------------------------------------------
    fun log(level: LogLevel, text: String) {
        val line = LogLine(seq.incrementAndGet(), level, text)
        _logs.value = (_logs.value + line).takeLast(500)
        val pr = when (level) {
            LogLevel.INFO -> Log.INFO
            LogLevel.OK -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(pr, TAG, text)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /** 診断ステップを記録（logcat へ。UI 非表示でも接続健全性のトレースに使う）。 */
    fun recordDiag(name: String, status: DiagStatus, detail: String) {
        val step = DiagStep(seq.incrementAndGet(), name, status, detail)
        _diag.value = (_diag.value + step).takeLast(200)
        val pr = when (status) {
            DiagStatus.PASS, DiagStatus.INFO -> Log.INFO
            DiagStatus.WARN -> Log.WARN
            DiagStatus.FAIL -> Log.ERROR
        }
        Log.println(pr, TAG, "[$status] $name : $detail")
    }

    // --- BroadcastReceiver 登録 --------------------------------------------
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.usbDevice()
                    if (granted && device != null) {
                        log(LogLevel.OK, "権限付与: ${device.deviceName}")
                        openDevice(device)
                    } else {
                        log(LogLevel.ERROR, "権限が拒否されました")
                        _lastError.value = "USB 権限が拒否されました"
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log(LogLevel.INFO, "デバイス接続を検知")
                    refresh()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice()
                    log(LogLevel.WARN, "デバイス切断: ${device?.deviceName}")
                    if (device != null && sessions.containsKey(device.deviceName)) {
                        disconnect(device.deviceName)
                    }
                    refresh()
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
        startCpuMonitor()
    }

    fun unregister() {
        runCatching { appContext.unregisterReceiver(receiver) }
        disconnectAll()
        scope.cancel()
    }

    // --- 列挙 ---------------------------------------------------------------
    fun refresh() {
        val list = usbManager.deviceList.values.map { it.toSummary() }
            .sortedBy { it.deviceName }
        _devices.value = list
        log(LogLevel.INFO, "デバイス列挙: ${list.size} 件")
    }

    /** 現在接続済みの deviceName 集合（UI の「接続済み」表示用）。 */
    fun connectedNames(): Set<String> = sessions.keys.toSet()

    // --- 権限要求 → open ----------------------------------------------------
    fun requestConnect(deviceName: String) {
        if (sessions.containsKey(deviceName)) {
            log(LogLevel.INFO, "$deviceName は既に接続済み")
            return
        }
        _diag.value = emptyList()
        BlockDiagnostics.captureEnvironment(emit)

        val device = usbManager.deviceList[deviceName]
        if (device == null) {
            recordDiag("列挙(getDeviceList)", DiagStatus.FAIL, "$deviceName が一覧に無い（システムレベルで遮断/USB host無効の疑い）")
            _lastError.value = "デバイスが見つかりません: $deviceName"
            return
        }
        recordDiag("列挙(getDeviceList)", DiagStatus.PASS, "$deviceName を検出（class=${device.deviceClass} ifaces=${device.interfaceCount}）")

        if (usbManager.hasPermission(device)) {
            recordDiag("権限(hasPermission)", DiagStatus.PASS, "既に権限あり")
            openDevice(device)
            return
        }
        recordDiag("権限(hasPermission)", DiagStatus.INFO, "未許可 → requestPermission を発行")
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
            else 0
        val pi = PendingIntent.getBroadcast(
            appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags,
        )
        log(LogLevel.INFO, "権限要求: $deviceName")
        try {
            usbManager.requestPermission(device, pi)
        } catch (t: Throwable) {
            recordDiag("権限(requestPermission)", DiagStatus.FAIL, "${t.javaClass.simpleName}: ${t.message}")
            _lastError.value = "requestPermission 例外: ${t.message}"
        }
    }

    private fun openDevice(device: UsbDevice) {
        if (sessions.containsKey(device.deviceName)) return
        val conn = try {
            usbManager.openDevice(device)
        } catch (t: Throwable) {
            recordDiag("openDevice", DiagStatus.FAIL, "${t.javaClass.simpleName}: ${t.message}（ここで遮断＝映像経路以前にブロック）")
            _lastError.value = "openDevice 例外: ${t.message}"
            return
        }
        if (conn == null) {
            recordDiag("openDevice", DiagStatus.FAIL, "戻り値が null（権限はあるが open を拒否＝遮断の可能性）")
            _lastError.value = "openDevice に失敗しました"
            return
        }
        recordDiag("openDevice", DiagStatus.PASS, "接続を取得（生 USB が開いた）")
        log(LogLevel.OK, "open 成功: ${device.deviceName} fd=${conn.fileDescriptor}")

        // 生 USB がどこまで使えるかを診断（fd/raw/endpoints/forceClaim/control, logcat）。
        BlockDiagnostics.runOnConnection(device, conn, emit)

        val parsed = conn.rawDescriptors?.let { DescriptorParser.parse(it) }
        if (parsed != null) {
            log(LogLevel.OK, "ディスクリプタ解析: ノード${parsed.nodes.size} VS=${parsed.videoStreamingInterfaces} 対応=${parsed.formats}")
        } else {
            log(LogLevel.WARN, "rawDescriptors が取得できませんでした")
        }

        val port = allocatePort()
        val session = CameraSession(
            device = device,
            connection = conn,
            descriptors = parsed,
            summary = device.toSummary(),
            port = port,
            scope = scope,
            log = ::log,
        )
        sessions[device.deviceName] = session
        collectors[device.deviceName] = scope.launch { session.ui.collect { publishCameras() } }
        _lastError.value = null
        publishCameras()
        log(LogLevel.OK, "カメラ追加: ${device.deviceName} port=$port（接続数=${sessions.size}）")
    }

    // --- カメラ別アクション -------------------------------------------------
    fun startStreaming(deviceName: String) {
        sessions[deviceName]?.startStreaming()
    }

    fun stopStreaming(deviceName: String) {
        sessions[deviceName]?.stopStreaming()
    }

    fun setFormat(deviceName: String, index: Int) {
        sessions[deviceName]?.setFormatIndex(index)
    }

    fun disconnect(deviceName: String) {
        collectors.remove(deviceName)?.cancel()
        sessions.remove(deviceName)?.close()
        publishCameras()
    }

    private fun disconnectAll() {
        collectors.values.forEach { it.cancel() }
        collectors.clear()
        sessions.values.forEach { it.close() }
        sessions.clear()
        publishCameras()
    }

    private fun publishCameras() {
        _cameras.value = sessions.values.map { it.snapshot() }
    }

    /** 8090 から昇順で未使用ポートを 1 つ割り当てる。 */
    private fun allocatePort(): Int {
        val used = sessions.values.map { it.port }.toSet()
        return (BASE_PORT..BASE_PORT + 64).first { it !in used }
    }

    // --- CPU モニタ ---------------------------------------------------------
    private fun startCpuMonitor() {
        scope.launch {
            while (isActive) {
                _cpu.value = cpuMonitor.sample(System.currentTimeMillis())
                delay(1000)
            }
        }
    }

    // --- ヘルパ -------------------------------------------------------------
    private fun UsbDevice.toSummary(): DeviceSummary {
        val likelyUvc = deviceClass == DescriptorParser.CLASS_MISC ||
            deviceClass == DescriptorParser.CLASS_VIDEO ||
            (0 until interfaceCount).any { getInterface(it).interfaceClass == DescriptorParser.CLASS_VIDEO }
        return DeviceSummary(
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
            productName = productName,
            manufacturerName = manufacturerName,
            deviceClass = deviceClass,
            interfaceCount = interfaceCount,
            isLikelyUvc = likelyUvc,
        )
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        const val TAG = "UsbDiag"
        private const val ACTION_USB_PERMISSION = "jp.hitohira.usbcamstreamer.USB_PERMISSION"
        private const val BASE_PORT = 8090
    }
}
