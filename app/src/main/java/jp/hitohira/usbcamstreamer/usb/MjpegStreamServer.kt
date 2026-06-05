package jp.hitohira.usbcamstreamer.usb

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class MjpegStreamServer(
    private val port: Int = DEFAULT_PORT,
    private val log: (LogLevel, String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val frameLock = Object()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val frameSeq = AtomicLong(0)
    private val framesPublished = AtomicLong(0)
    private val bytesPublished = AtomicLong(0)
    private val clientsAccepted = AtomicLong(0)
    private val clientsDropped = AtomicLong(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var latestFrame: ByteArray? = null

    @Volatile
    private var latestFrameAtMs: Long = 0

    fun start(): String {
        if (running.getAndSet(true)) {
            return streamUrl()
        }
        val socket = ServerSocket(port).apply {
            reuseAddress = true
        }
        serverSocket = socket
        thread(name = "mjpeg-accept", isDaemon = true) {
            acceptLoop(socket)
        }
        log(LogLevel.OK, "PC MJPEG配信開始: ${streamUrl()} snapshot=${snapshotUrl()} status=${statusUrl()}")
        return streamUrl()
    }

    fun stop(): String {
        if (!running.getAndSet(false)) {
            return "PC MJPEG配信は停止中"
        }
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
        clients.forEach { socket -> runCatching { socket.close() } }
        clients.clear()
        val status = statusLine()
        log(LogLevel.INFO, "PC MJPEG配信停止: $status")
        return status
    }

    fun publishFrame(bytes: ByteArray) {
        if (!running.get()) return
        latestFrame = bytes
        latestFrameAtMs = System.currentTimeMillis()
        framesPublished.incrementAndGet()
        bytesPublished.addAndGet(bytes.size.toLong())
        frameSeq.incrementAndGet()
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
    }

    fun isRunning(): Boolean = running.get()

    fun streamUrl(): String = "http://${hostAddress()}:$port/mjpeg"

    fun snapshotUrl(): String = "http://${hostAddress()}:$port/snapshot.jpg"

    fun statusUrl(): String = "http://${hostAddress()}:$port/status"

    fun statusLine(): String {
        val ageMs = latestFrameAtMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: -1
        return "url=${streamUrl()} clients=${clients.size} frames=${framesPublished.get()} bytes=${bytesPublished.get()} latestAgeMs=$ageMs accepted=${clientsAccepted.get()} dropped=${clientsDropped.get()}"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (t: Throwable) {
                log(LogLevel.WARN, "PC MJPEG accept失敗: ${t.javaClass.simpleName}: ${t.message}")
                continue
            }
            clientsAccepted.incrementAndGet()
            thread(name = "mjpeg-client", isDaemon = true) {
                runCatching { handleClient(client) }
                    .onFailure { t ->
                        clientsDropped.incrementAndGet()
                        Log.d(UsbRepository.TAG, "PC MJPEG client未捕捉終了: ${t.javaClass.simpleName}: ${t.message}")
                        runCatching { client.close() }
                    }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 3000
            val path = readPath(socket)
            when (path) {
                "/mjpeg" -> streamMjpeg(socket)
                "/snapshot.jpg" -> sendSnapshot(socket)
                "/status" -> sendText(socket, statusLine())
                else -> sendText(socket, "USB Access Lab MJPEG\n/mjpeg\n/snapshot.jpg\n/status\n")
            }
        } catch (t: Throwable) {
            clientsDropped.incrementAndGet()
            Log.d(UsbRepository.TAG, "PC MJPEG client終了: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun readPath(socket: Socket): String {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val first = reader.readLine()
            ?: throw SocketException("empty HTTP request")
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        return first.split(" ").getOrNull(1) ?: "/"
    }

    private fun streamMjpeg(socket: Socket) {
        clients += socket
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
        )
        out.flush()

        var lastSeq = -1L
        while (running.get() && !socket.isClosed) {
            val frame = waitForFrameAfter(lastSeq) ?: break
            lastSeq = frameSeq.get()
            out.write("--$BOUNDARY\r\n".toByteArray(Charsets.US_ASCII))
            out.write("Content-Type: image/jpeg\r\n".toByteArray(Charsets.US_ASCII))
            out.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.write(frame)
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    private fun sendSnapshot(socket: Socket) {
        val frame = latestFrame
        if (frame == null) {
            sendText(socket, "no frame yet", code = "404 Not Found")
            return
        }
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.size}\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
        )
        out.write(frame)
        out.flush()
    }

    private fun sendText(socket: Socket, text: String, code: String = "200 OK") {
        val body = text.toByteArray(Charsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(
            (
                "HTTP/1.1 $code\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
        )
        out.write(body)
        out.flush()
    }

    private fun waitForFrameAfter(lastSeq: Long): ByteArray? {
        synchronized(frameLock) {
            while (running.get() && frameSeq.get() == lastSeq) {
                frameLock.wait(1000)
            }
        }
        return if (running.get()) latestFrame else null
    }

    private fun hostAddress(): String {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
    }

    companion object {
        const val DEFAULT_PORT = 8090
        private const val BOUNDARY = "usb-access-lab"
    }
}
