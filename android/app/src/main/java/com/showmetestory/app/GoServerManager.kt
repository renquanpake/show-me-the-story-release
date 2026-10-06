package com.showmetestory.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class ServerState { STARTING, READY }

/**
 * Owns the bundled Go server: extracts it from assets into app-private
 * storage, marks it executable, spawns it as a child process, and polls the
 * loopback port until the server accepts connections.
 *
 * The binary is a fully static AArch64 ELF (CGO_ENABLED=0, no PT_DYNAMIC,
 * no PT_INTERP), so it needs no libc or interpreter on the device.
 */
class GoServerManager(
    private val context: Context,
    private val port: Int = DEFAULT_PORT
) {

    private var process: Process? = null
    private var probeThread: Thread? = null
    @Volatile
    private var stopped = false

    fun start(onState: (ServerState) -> Unit, onFailure: (String) -> Unit) {
        val bin = extractBinary()
            ?: run {
                onFailure("无法从内置资源释放服务端程序：assets/bin/$BINARY_NAME 缺失或损坏")
                return
            }

        if (!bin.setExecutable(true, false)) {
            onFailure("无法设置可执行权限：${bin.absolutePath}")
            return
        }

        val dataDir = context.filesDir.absolutePath
        val logFile = File(dataDir, "server.out")

        val proc = try {
            ProcessBuilder(listOf(bin.absolutePath, dataDir))
                .directory(File(dataDir))
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .apply { environment()["PORT"] = port.toString() }
                .start()
        } catch (e: Exception) {
            onFailure("服务端进程启动失败：${e.javaClass.simpleName}: ${e.message}\n${tail(logFile)}")
            return
        }
        process = proc
        onState(ServerState.STARTING)

        probeThread = Thread({
            val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
            var up = false
            while (!stopped && System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) break
                if (portOpen()) { up = true; break }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            if (stopped) return@Thread
            when {
                up -> onState(ServerState.READY)
                !proc.isAlive ->
                    onFailure("服务端进程已退出（exit=${proc.exitValue()}）\n${tail(logFile)}")
                else -> onFailure("服务端 ${PROBE_TIMEOUT_MS / 1000} 秒内未就绪，请查看 server.out")
            }
        }, "smts-probe").also { it.start() }
    }

    fun stop() {
        stopped = true
        probeThread?.let { try { it.join(JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {} }
        probeThread = null
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                try { p.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                if (p.isAlive) p.destroyForcibly()
            }
        }
        process = null
    }

    private fun portOpen(): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Copies assets/bin/<BINARY> to filesDir/bin, reusing a size-matched cached copy. */
    private fun extractBinary(): File? {
        val dir = File(context.filesDir, "bin")
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, BINARY_NAME)
        val tmp = File(dir, "$BINARY_NAME.tmp")

        val size = try {
            context.assets.length("bin/$BINARY_NAME")
        } catch (_: Exception) {
            return null
        }

        if (target.exists() && target.length() == size) return target

        val ok = try {
            context.assets.open("bin/$BINARY_NAME").use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            tmp.setExecutable(true, false)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (_: Exception) {
            false
        }

        if (!ok || target.length() != size) {
            runCatching { tmp.delete() }
            return null
        }
        return target
    }

    private fun tail(file: File): String {
        if (!file.exists()) return "(无服务端日志)"
        return try {
            file.readText().takeLast(MAX_TAIL_CHARS)
        } catch (_: Exception) {
            "(日志不可读)"
        }
    }

    companion object {
        const val DEFAULT_PORT = 48090
        const val HOST = "127.0.0.1"
        const val URL = "http://$HOST:$DEFAULT_PORT/"
        private const val BINARY_NAME = "show-me-the-story"
        private const val PROBE_TIMEOUT_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 1_000
        private const val POLL_INTERVAL_MS = 400L
        private const val STOP_WAIT_SECONDS = 2L
        private const val JOIN_TIMEOUT_MS = 300L
        private const val MAX_TAIL_CHARS = 1200
    }
}
