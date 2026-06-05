package jp.hitohira.usbcamstreamer.usb

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * 自プロセスの CPU 使用率を `/proc/self/stat` から算出する。
 *
 * utime(14)+stime(15)〔clock ticks〕の増分を経過実時間で割り、1 コア基準の % を返す。
 * 自プロセスの /proc は SELinux でも読めるため native 不要。
 */
class CpuMonitor {
    private val clockTicksPerSec: Long =
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
    private val cores: Int = Runtime.getRuntime().availableProcessors()

    private var lastCpuTicks: Long = -1
    private var lastWallMs: Long = -1

    /** 直近サンプルからの増分で CPU% を更新して返す。初回は 0%。 */
    fun sample(nowMs: Long): CpuStats {
        val ticks = readProcessCpuTicks() ?: return CpuStats(0.0, cores)
        val prevTicks = lastCpuTicks
        val prevWall = lastWallMs
        lastCpuTicks = ticks
        lastWallMs = nowMs

        if (prevTicks < 0 || prevWall < 0 || nowMs <= prevWall) {
            return CpuStats(0.0, cores)
        }
        val cpuSeconds = (ticks - prevTicks).toDouble() / clockTicksPerSec
        val wallSeconds = (nowMs - prevWall).toDouble() / 1000.0
        val percent = if (wallSeconds > 0) (cpuSeconds / wallSeconds) * 100.0 else 0.0
        return CpuStats(percent.coerceIn(0.0, cores * 100.0), cores)
    }

    /** `/proc/self/stat` の utime+stime（clock ticks）。失敗時 null。 */
    private fun readProcessCpuTicks(): Long? = runCatching {
        val stat = File("/proc/self/stat").readText()
        // comm フィールド（括弧/空白を含みうる）の末尾 ')' 以降を対象にする。
        val rest = stat.substring(stat.lastIndexOf(')') + 1).trim()
        val fields = rest.split(Regex("\\s+"))
        // ')' の次が field 3(state)。utime=14, stime=15 は rest 基準で添字 11, 12。
        val utime = fields[11].toLong()
        val stime = fields[12].toLong()
        utime + stime
    }.getOrNull()
}
