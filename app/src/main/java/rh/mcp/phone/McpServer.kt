package rh.mcp.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class McpServer(
    context: Context,
    private val handler: (String, JSONObject) -> JSONObject,
) {
    private val appContext = context.applicationContext
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workerIds = AtomicInteger()
    private val workers: ExecutorService = Executors.newFixedThreadPool(12, ThreadFactory { task ->
        Thread(task, "mcp-worker-${workerIds.incrementAndGet()}").apply { isDaemon = true }
    })

    @Synchronized
    fun start() {
        if (isRunning()) return
        val socket = ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running = true
        acceptThread = Thread({
            while (running) {
                try {
                    val client = socket.accept()
                    workers.execute { handle(client) }
                } catch (error: IOException) {
                    if (running) RuntimeLog.event(appContext, "MCP accept failed", error)
                } catch (error: RuntimeException) {
                    RuntimeLog.event(appContext, "MCP worker dispatch failed", error)
                }
            }
        }, "mcp-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        workers.shutdownNow()
    }

    fun isRunning(): Boolean =
        running && acceptThread?.isAlive == true && serverSocket?.isClosed == false

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 60_000
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readLine(input, 8192) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    respond(client, 400, """{"error":"invalid HTTP request"}""")
                    return
                }
                val method = parts[0].uppercase(Locale.US)
                val path = parts[1]
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input, 8192) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
                if (path == "/health") {
                    respond(client, 200, """{"ok":true,"service":"Phone To MCP"}""")
                    return
                }
                if (path != "/mcp" && !path.startsWith("/mcp?")) {
                    respond(client, 404, """{"error":"not found"}""")
                    return
                }
                if (method == "OPTIONS") {
                    respond(client, 204, "")
                    return
                }
                if (method != "POST") {
                    respond(client, 405, """{"error":"POST required"}""")
                    return
                }
                if (ToolSettings.authEnabled(appContext) &&
                    !authorized(headers["authorization"])
                ) {
                    respond(client, 401, """{"error":"unauthorized"}""")
                    return
                }
                val contentLength = headers["content-length"]?.toLongOrNull() ?: -1
                if (contentLength < 0 || contentLength > MAX_REQUEST_BYTES) {
                    respond(client, 413, """{"error":"invalid or oversized content length"}""")
                    return
                }
                val body = readBody(input, contentLength.toInt())
                if (body.isBlank()) {
                    respond(client, 400, """{"error":"empty body"}""")
                    return
                }
                val request = JSONObject(body)
                val response = dispatch(request)
                if (response == null) respond(client, 202, "")
                else respond(client, 200, response.toString())
            } catch (error: Exception) {
                RuntimeLog.event(appContext, "MCP request failed: ${error.javaClass.simpleName}", error)
                runCatching {
                    respond(client, 400, errorResponse(null, -32700, error.message ?: "invalid request").toString())
                }
            }
        }
    }

    private fun dispatch(request: JSONObject): JSONObject? {
        if (request.optString("jsonrpc") != "2.0") {
            return errorResponse(request.opt("id"), -32600, "jsonrpc must be 2.0")
        }
        val method = request.optString("method")
        if (method.isBlank()) return errorResponse(request.opt("id"), -32600, "method is required")
        val hasId = request.has("id")
        if (!hasId && method !in setOf("notifications/initialized", "notifications/cancelled")) {
            return null
        }
        val response = JSONObject().put("jsonrpc", "2.0")
        if (hasId) response.put("id", request.opt("id"))
        try {
            val params = request.optJSONObject("params") ?: JSONObject()
            val result = when (method) {
                "initialize" -> {
                    val version = params.optString("protocolVersion", "2025-11-25")
                    JSONObject()
                        .put("protocolVersion", if (version == "2025-11-25") version else "2025-11-25")
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", "Phone To MCP").put("version", "1.0.0"))
                        .put("instructions", "Android device tools are limited by application access settings.")
                }
                "notifications/initialized", "notifications/cancelled" -> JSONObject()
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", JSONArray().also { tools ->
                    ToolRegistry.enabled(appContext).forEach { tools.put(it.toJson()) }
                })
                "tools/call" -> {
                    val name = params.optString("name")
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    handler(name, arguments)
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return errorResponse(request.opt("id"), -32601, "method not found: $method")
            }
            if (!hasId) return null
            response.put("result", result)
            return response
        } catch (error: Exception) {
            RuntimeLog.event(appContext, "MCP method failed: $method", error)
            return errorResponse(request.opt("id"), -32603, error.message ?: "internal error")
        }
    }

    private fun authorized(header: String?): Boolean {
        val expected = ToolSettings.token(appContext)
        if (header == null || !header.startsWith("Bearer ", ignoreCase = true)) return false
        return header.substringAfter(' ').trim().constantTimeEquals(expected)
    }

    private fun errorResponse(id: Any?, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun readLine(input: BufferedInputStream, maxBytes: Int): String? {
        val output = ByteArrayOutputStream()
        var readAny = false
        while (true) {
            val value = input.read()
            if (value < 0) return if (readAny) output.toString(StandardCharsets.ISO_8859_1.name()) else null
            readAny = true
            if (value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
            if (output.size() > maxBytes) throw IOException("HTTP line too long")
        }
        return output.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readBody(input: BufferedInputStream, size: Int): String {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) throw IOException("truncated HTTP body")
            offset += count
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        val reason = when (status) {
            200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"
            401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            413 -> "Payload Too Large"; else -> "Error"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type\r\n" +
            "Access-Control-Allow-Methods: POST, OPTIONS\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(StandardCharsets.US_ASCII)); write(bytes); flush() }
    }

    companion object {
        const val PORT = 9009
        private const val MAX_REQUEST_BYTES = 24 * 1024 * 1024L
    }
}

private fun String.constantTimeEquals(other: String): Boolean {
    if (length != other.length) return false
    var difference = 0
    indices.forEach { difference = difference or (this[it].code xor other[it].code) }
    return difference == 0
}
