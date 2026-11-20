package rh.mcp.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class PhoneMcpService : Service() {
    @Volatile private var server: McpServer? = null
    @Volatile private var tunnelProcess: Process? = null
    @Volatile private var serviceStopping = false
    private var speech: SpeechController? = null
    private var watchdog: Thread? = null

    override fun onCreate() {
        super.onCreate()
        serviceStopping = false
        createNotificationChannel()
        startForegroundCompat(buildNotification("Local MCP server starting"))
        RuntimeLog.event(this, "MCP service started")
        speech = SpeechController(this)
        startMcpServer()
        startWatchdog()
        if (ToolSettings.tunnelEnabled(this)) startTunnelInternal()
        ServiceRecoveryReceiver.cancel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TUNNEL -> {
                ToolSettings.setTunnelEnabled(this, true)
                startTunnelInternal()
            }
            ACTION_STOP_TUNNEL -> {
                ToolSettings.setTunnelEnabled(this, false)
                stopTunnelInternal()
            }
        }
        if (!serverRunning()) startMcpServer()
        return if (ToolSettings.serverEnabled(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ToolSettings.serverEnabled(this) && !serviceStopping) ServiceRecoveryReceiver.schedule(this, 3_000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceStopping = true
        watchdog?.interrupt()
        watchdog = null
        server?.stop()
        server = null
        stopTunnelInternal()
        speech?.close()
        speech = null
        if (!ToolSettings.serverEnabled(this)) CrashDiagnostics.markNormalStop(this, "server disabled")
        else RuntimeLog.event(this, "service destroyed while enabled; recovery scheduled")
        if (ToolSettings.serverEnabled(this)) ServiceRecoveryReceiver.schedule(this, 3_000)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("server_running", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMcpServer() {
        runCatching {
            server?.stop()
            server = McpServer(this, ::callTool).also { it.start() }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("server_running", true).apply()
            RuntimeLog.event(this, "MCP server listening at 127.0.0.1:${McpServer.PORT}")
            updateNotification("MCP server running on port ${McpServer.PORT}")
        }.onFailure {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("server_running", false).apply()
            RuntimeLog.event(this, "MCP server failed to start", it)
            updateNotification("MCP server failed: ${it.message}")
        }
    }

    private fun startWatchdog() {
        watchdog?.interrupt()
        watchdog = Thread({
            while (!serviceStopping) {
                try {
                    Thread.sleep(15_000)
                    if (ToolSettings.serverEnabled(this) && !serverRunning()) startMcpServer()
                    if (ToolSettings.tunnelEnabled(this) && tunnelProcess?.isAlive != true) startTunnelInternal()
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "mcp-watchdog").apply { isDaemon = true; start() }
    }

    private fun serverRunning() = server?.isRunning() == true

    private fun callTool(name: String, args: JSONObject): JSONObject {
        val definition = ToolRegistry.get(name)
            ?: return toolError("tool tidak ditemukan: $name")
        if (!ToolSettings.isEnabled(this, definition)) return toolError("tool dinonaktifkan: $name")
        return try {
            val result = when (name) {
                "device.info" -> DeviceInfo.get(this, args.optString("section", "all"))
                "time.now" -> JSONObject().put("iso", java.time.Instant.now().toString())
                    .put("epochMillis", System.currentTimeMillis())
                "file.read", "file.write", "file.append", "file.patch", "file.list", "file.stat",
                "file.mkdir", "file.copy", "file.move", "file.delete", "zip.create", "zip.extract" ->
                    FileOps.execute(this, name, args)
                "json.batch" -> JsonBatchManager(this).execute(args)
                "terminal.info" -> TerminalManager.info(this)
                "terminal.set_backend" -> TerminalManager.setBackend(this, args.getString("backend"))
                "terminal.exec" -> TerminalManager.execute(this, args)
                "notification.send" -> sendNotification(args)
                "notification.read" -> PhoneNotificationListener.read(args.optInt("limit", 100))
                "tts.speak" -> speech?.speak(
                    args.getString("text"),
                    args.optString("language"),
                    args.optString("queue", "flush"),
                ) ?: throw IllegalStateException("TTS service belum siap")
                "tts.stop" -> speech?.stop() ?: JSONObject().put("stopped", false)
                "download.url" -> DownloadTools.enqueue(this, args)
                "server.status" -> serverStatus()
                "mcp.tools_enabled" -> JSONObject().put("tools", JSONArray().also { tools ->
                    ToolRegistry.enabled(this).forEach { tools.put(it.name) }
                })
                else -> throw IllegalArgumentException("tool belum diimplementasikan: $name")
            }
            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString())))
                .put("structuredContent", result).put("isError", false)
        } catch (error: Exception) {
            RuntimeLog.event(this, "tool failed: $name (${error.javaClass.simpleName})")
            toolError(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun sendNotification(args: JSONObject): JSONObject {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("POST_NOTIFICATIONS belum diizinkan")
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = args.optInt("id", System.currentTimeMillis().toInt())
        val notification = NotificationCompat.Builder(this, USER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(args.getString("title"))
            .setContentText(args.getString("message"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(args.getString("message")))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
        return JSONObject().put("sent", true).put("id", id)
    }

    private fun serverStatus(): JSONObject {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return JSONObject()
        .put("running", serverRunning())
        .put("host", "127.0.0.1")
        .put("port", McpServer.PORT)
        .put("endpoint", "http://127.0.0.1:${McpServer.PORT}/mcp")
        .put("authenticationEnabled", ToolSettings.authEnabled(this))
        .put("tunnelEnabled", ToolSettings.tunnelEnabled(this))
        .put("tunnelRunning", tunnelProcess?.isAlive == true)
        .put("publicUrl", prefs.getString(KEY_PUBLIC_URL, null) ?: JSONObject.NULL)
        .put("tunnelError", prefs.getString(KEY_TUNNEL_ERROR, null) ?: JSONObject.NULL)
    }

    private fun toolError(message: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
        .put("isError", true)

    private fun startTunnelInternal() {
        synchronized(this) {
            if (tunnelProcess?.isAlive == true) return
            val binary = java.io.File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
            if (!binary.isFile || !binary.canExecute()) {
                saveTunnelState(null, "cloudflared arm64 binary missing or not executable")
                return
            }
            runCatching {
                saveTunnelState(null, null)
                val process = ProcessBuilder(
                    binary.absolutePath,
                    "tunnel",
                    "--url",
                    "http://127.0.0.1:${McpServer.PORT}",
                ).redirectErrorStream(true).start()
                tunnelProcess = process
                Thread({
                    val pattern = Pattern.compile("https://[a-zA-Z0-9.-]+\\.trycloudflare\\.com")
                    try {
                        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
                            lines.forEach { line ->
                                val match = pattern.matcher(line)
                                if (match.find()) {
                                    val url = "${match.group()}/mcp"
                                    saveTunnelState(url, null)
                                    updateNotification("Cloudflare Tunnel active")
                                }
                            }
                        }
                    } catch (error: Exception) {
                        RuntimeLog.event(this, "cloudflared output reader failed", error)
                    } finally {
                        synchronized(this) { if (tunnelProcess === process) tunnelProcess = null }
                        if (!serviceStopping && ToolSettings.tunnelEnabled(this)) {
                            saveTunnelState(null, "cloudflared exited; retry scheduled")
                        }
                    }
                }, "cloudflared-output").apply { isDaemon = true; start() }
                Thread({
                    try {
                        Thread.sleep(30_000)
                        if (tunnelProcess === process && getSharedPreferences(PREFS, MODE_PRIVATE)
                                .getString(KEY_PUBLIC_URL, null) == null
                        ) saveTunnelState(null, "timed out waiting for Cloudflare quick tunnel URL")
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }, "cloudflared-timeout").apply { isDaemon = true; start() }
            }.onFailure {
                tunnelProcess = null
                saveTunnelState(null, "failed to start cloudflared: ${it.message}")
                RuntimeLog.event(this, "cloudflared failed to start", it)
            }
        }
    }

    private fun stopTunnelInternal() {
        synchronized(this) {
            tunnelProcess?.let { process ->
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            tunnelProcess = null
            if (!ToolSettings.tunnelEnabled(this)) saveTunnelState(null, null)
        }
    }

    private fun saveTunnelState(url: String?, error: String?) {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        if (url == null) editor.remove(KEY_PUBLIC_URL) else editor.putString(KEY_PUBLIC_URL, url)
        if (error == null) editor.remove(KEY_TUNNEL_ERROR) else editor.putString(KEY_TUNNEL_ERROR, error)
        editor.apply()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MCP server", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(USER_CHANNEL_ID, "Phone To MCP notifications", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START_TUNNEL = "rh.mcp.phone.action.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "rh.mcp.phone.action.STOP_TUNNEL"
        private const val NOTIFICATION_ID = 9009
        private const val CHANNEL_ID = "phone_mcp_server"
        private const val USER_CHANNEL_ID = "phone_mcp_user"
        private const val PREFS = "server"
        private const val KEY_PUBLIC_URL = "public_url"
        private const val KEY_TUNNEL_ERROR = "tunnel_error"

        fun start(context: Context) {
            ToolSettings.setServerEnabled(context, true)
            val intent = Intent(context, PhoneMcpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            ToolSettings.setServerEnabled(context, false)
            ToolSettings.setTunnelEnabled(context, false)
            context.stopService(Intent(context, PhoneMcpService::class.java))
        }

        fun startTunnel(context: Context) {
            ToolSettings.setTunnelEnabled(context, true)
            if (!ToolSettings.serverEnabled(context)) start(context)
            val intent = Intent(context, PhoneMcpService::class.java).setAction(ACTION_START_TUNNEL)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stopTunnel(context: Context) {
            ToolSettings.setTunnelEnabled(context, false)
            context.startService(Intent(context, PhoneMcpService::class.java).setAction(ACTION_STOP_TUNNEL))
        }
    }
}
