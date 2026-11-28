package rh.mcp.phone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object TerminalManager {
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_SHELL = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val ANDROID_SHELL = "/system/bin/sh"
    private const val MAX_OUTPUT_BYTES = 1024 * 1024
    private val ids = AtomicLong(System.currentTimeMillis())
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()

    fun termuxInstalled(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(
            TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0),
        ) else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun termuxPermissionGranted(context: Context): Boolean =
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED

    fun info(context: Context): JSONObject {
        val backend = ToolSettings.backend(context)
        return JSONObject()
            .put("activeBackend", backend)
            .put("availableBackends", org.json.JSONArray().put("android").also {
                if (termuxInstalled(context) && termuxPermissionGranted(context)) it.put("termux")
            })
            .put("termuxInstalled", termuxInstalled(context))
            .put("termuxRunCommandPermission", termuxPermissionGranted(context))
            .put("shell", if (backend == "termux") TERMUX_SHELL else ANDROID_SHELL)
            .put("defaultCwd", if (backend == "termux") TERMUX_HOME else Environment.getExternalStorageDirectory().absolutePath)
            .put("androidPrivilege", "application UID; not root")
    }

    fun setBackend(context: Context, backend: String): JSONObject {
        require(backend == "android" || backend == "termux") { "backend harus android atau termux" }
        if (backend == "termux") {
            require(termuxInstalled(context)) { "Termux tidak terpasang" }
            require(termuxPermissionGranted(context)) { "RUN_COMMAND permission belum diberikan di Termux" }
        }
        val previous = ToolSettings.backend(context)
        ToolSettings.setBackend(context, backend)
        return JSONObject().put("previousBackend", previous).put("backend", backend)
    }

    fun execute(context: Context, args: JSONObject): JSONObject {
        val command = args.optString("command").trim()
        require(command.isNotEmpty()) { "command wajib diisi" }
        require(command.length <= 100_000) { "command melebihi 100000 karakter" }
        val backend = ToolSettings.backend(context)
        val timeout = args.optInt("timeoutMs", 30_000).coerceIn(1_000, 300_000)
        val cwd = args.optString(
            "cwd",
            if (backend == "termux") TERMUX_HOME else Environment.getExternalStorageDirectory().absolutePath,
        )
        require(cwd.length <= 2048) { "cwd terlalu panjang" }
        val shell = if (backend == "termux") TERMUX_SHELL else ANDROID_SHELL
        val started = System.currentTimeMillis()
        val logDb = TerminalLogDb(context)
        val logId = runCatching {
            logDb.insert(TerminalLogEntry(0, started, backend, shell, command, cwd, "", "", -1, 0, false, false, null))
        }.getOrElse { logDb.close(); throw IllegalStateException("gagal mencatat terminal command", it) }
        try {
            val result = when (backend) {
                "android" -> executeAndroid(command, cwd, timeout, started)
                "termux" -> executeTermux(context, command, cwd, timeout, started, logId)
                else -> error("backend tidak dikenal: $backend")
            }
            result.put("backend", backend).put("shell", shell).put("command", command).put("cwd", cwd)
            logDb.finish(logId, result)
            return result
        } catch (error: Exception) {
            val failed = JSONObject().put("backend", backend).put("shell", shell).put("command", command)
                .put("cwd", cwd).put("stdout", "").put("stderr", "")
                .put("exitCode", -1).put("completed", false).put("timedOut", false)
                .put("durationMs", System.currentTimeMillis() - started)
                .put("error", error.message ?: error.javaClass.simpleName)
            logDb.finish(logId, failed)
            throw error
        } finally {
            logDb.close()
        }
    }

    private fun executeAndroid(command: String, cwd: String, timeout: Int, started: Long): JSONObject {
        val directory = File(cwd)
        require(directory.isDirectory && directory.canExecute()) { "cwd tidak dapat diakses: $cwd" }
        val process = ProcessBuilder(ANDROID_SHELL, "-c", command).directory(directory).start()
        val stdout = BoundedOutput(MAX_OUTPUT_BYTES)
        val stderr = BoundedOutput(MAX_OUTPUT_BYTES)
        val outThread = Thread({ process.inputStream.use { it.copyTo(stdout) } }, "terminal-stdout").apply { start() }
        val errThread = Thread({ process.errorStream.use { it.copyTo(stderr) } }, "terminal-stderr").apply { start() }
        val completed = process.waitFor(timeout.toLong(), TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        outThread.join(1000)
        errThread.join(1000)
        return JSONObject().put("stdout", stdout.text()).put("stderr", stderr.text())
            .put("exitCode", if (completed) process.exitValue() else -1)
            .put("completed", completed).put("timedOut", !completed)
            .put("durationMs", System.currentTimeMillis() - started)
            .put("outputTruncated", stdout.truncated || stderr.truncated)
            .apply { if (!completed) put("error", "command timed out; managed process was terminated") }
    }

    private fun executeTermux(
        context: Context,
        command: String,
        cwd: String,
        timeout: Int,
        started: Long,
        id: Long,
    ): JSONObject {
        require(termuxInstalled(context)) { "Termux tidak terpasang" }
        require(termuxPermissionGranted(context)) { "RUN_COMMAND permission belum diberikan" }
        val result = CompletableFuture<JSONObject>()
        pending[id] = result
        val callbackIntent = Intent(context, TerminalResultReceiver::class.java).apply {
            action = "rh.mcp.phone.TERMUX_RESULT.$id"
            putExtra(EXTRA_EXECUTION_ID, id)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        val callback = PendingIntent.getBroadcast(context, id.hashCode(), callbackIntent, flags)
        try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_SHELL)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_LABEL", "Phone To MCP terminal.exec")
                putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "MCP client command")
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
            }
            context.startService(intent)
        } catch (error: Exception) {
            callback.cancel()
            pending.remove(id)
            throw IllegalStateException("gagal mengirim command ke Termux", error)
        }
        return try {
            result.get(timeout.toLong(), TimeUnit.MILLISECONDS).put("durationMs", System.currentTimeMillis() - started)
        } catch (_: java.util.concurrent.TimeoutException) {
            JSONObject().put("stdout", "").put("stderr", "").put("exitCode", -1)
                .put("completed", false).put("timedOut", true)
                .put("durationMs", System.currentTimeMillis() - started)
                .put("executionId", id)
                .put("error", "timeout menunggu Termux; proses mungkin masih berjalan dan tidak dibunuh")
        } finally {
            pending.remove(id)
        }
    }

    internal fun receive(id: Long, result: JSONObject) {
        pending[id]?.complete(result)
    }

    fun executionId(intent: Intent?): Long = intent?.getLongExtra(EXTRA_EXECUTION_ID, -1) ?: -1

    fun parseTermuxResult(intent: Intent?): JSONObject {
        val extras = intent?.extras ?: return JSONObject().put("completed", false).put("error", "Termux result bundle missing")
        var bundle = extras.getBundle("com.termux.app.extra.PLUGIN_RESULT_BUNDLE")
            ?: extras.getBundle("com.termux.execute.plugin_result_bundle")
        if (bundle == null) {
            for (key in extras.keySet()) {
                val value = extras.get(key)
                if (value is android.os.Bundle && value.keySet().any { it.contains("STDOUT", true) || it.contains("EXIT_CODE", true) }) {
                    bundle = value
                    break
                }
            }
        }
        if (bundle == null) return JSONObject().put("completed", false).put("error", "Termux result bundle missing")
        fun findKey(part: String): String? = bundle.keySet().firstOrNull {
            it.contains(part, true) && !it.contains("ORIGINAL_LENGTH", true)
        }
        fun getString(part: String): String = findKey(part)
            ?.let(bundle::get)
            ?.let { it as? String }
            .orEmpty()
        fun getInt(part: String): Int? {
            val key = if (part == "_ERR") {
                bundle.keySet().firstOrNull {
                    it.equals("ERR", true) || it.endsWith(".ERR", true) || it.endsWith("_ERR", true)
                }
            } else findKey(part)
            return key?.let(bundle::get)?.toString()?.toIntOrNull()
        }
        val err = getInt("_ERR") ?: -1
        return JSONObject().put("stdout", getString("STDOUT"))
            .put("stderr", getString("STDERR"))
            .put("exitCode", getInt("EXIT_CODE") ?: getInt("EXITCODE") ?: -1)
            .put("completed", err == -1).put("timedOut", false)
            .put("error", if (err == -1) JSONObject.NULL else getString("ERRMSG").ifBlank { "Termux error $err" })
    }

    private class BoundedOutput(private val maximum: Int) : ByteArrayOutputStream() {
        var truncated = false
            private set
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val remaining = maximum - count
            if (remaining <= 0) { truncated = true; return }
            super.write(buffer, offset, minOf(length, remaining))
            if (length > remaining) truncated = true
        }
        fun text() = toByteArray().toString(Charsets.UTF_8)
    }

    private const val EXTRA_EXECUTION_ID = "rh.mcp.phone.extra.TERMINAL_EXECUTION_ID"
}

class TerminalResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = TerminalManager.executionId(intent)
        if (id <= 0) {
            Log.e("TerminalResult", "Termux callback without execution id")
            return
        }
        TerminalManager.receive(id, TerminalManager.parseTermuxResult(intent))
    }
}
