package app.kujira.android.net.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Tiny one-shot HTTP server on `127.0.0.1` used as the OAuth redirect target.
 *
 * Keycloak redirects the browser to `http://localhost:20573/oauth/callback`;
 * on Android the only way to receive that is to listen on a loopback port in
 * the app itself (the same trick the desktop bridge uses, with node's http
 * server). Hand-written with [ServerSocket] so no extra dependency is needed.
 *
 * Not a general-purpose server: it accepts one callback, replies with a tiny
 * self-contained HTML page, then the caller stops it.
 */
class LoopbackServer(
    private val port: Int = DEFAULT_PORT,
    private val callbackPath: String = DEFAULT_CALLBACK_PATH,
    private val logger: (String) -> Unit = {}
) {

    data class Callback(
        val code: String? = null,
        val state: String? = null,
        val error: String? = null,
        val errorDescription: String? = null
    )

    private var serverSocket: ServerSocket? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Binds the port. Throws [McpException] when the port is taken. */
    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(InetAddress.getByName(HOST), port))
        } catch (failure: IOException) {
            runCatching { socket.close() }
            throw McpException("无法在 $HOST:$port 监听授权回调（端口被占用？请关闭占用该端口的应用后重试）", failure)
        }
        socket.soTimeout = ACCEPT_POLL_MS
        serverSocket = socket
        logger("授权回调服务器已启动：http://$HOST:$port$callbackPath")
    }

    /**
     * Blocks (suspending) until the browser hits the callback, an OAuth error
     * arrives, the timeout expires or the coroutine is cancelled.
     */
    suspend fun awaitCallback(timeoutMs: Long = DEFAULT_AUTH_TIMEOUT_MS): Callback = withContext(Dispatchers.IO) {
        val socket = serverSocket ?: throw McpException("授权回调服务器未启动")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isActive) {
            val accepted: Socket = try {
                socket.accept()
            } catch (timeout: SocketTimeoutException) {
                if (System.currentTimeMillis() >= deadline) {
                    throw McpException("授权超时（${timeoutMs / 60_000} 分钟）：没有收到浏览器的回调，请重新登录")
                }
                continue
            } catch (closed: SocketException) {
                throw CancellationException("授权回调监听已停止")
            }

            accepted.use { client ->
                client.soTimeout = CLIENT_READ_TIMEOUT_MS
                val requestLine = readRequestLine(client.getInputStream())
                val output = client.getOutputStream()
                if (requestLine == null || !requestLine.startsWith("GET ")) {
                    writeResponse(output, 400, page("请求无效", "请回到 Kujira 重新发起登录。"))
                    return@use
                }
                val target = requestLine.removePrefix("GET ").substringBefore(' ')
                val path = target.substringBefore('?')
                if (!path.equals(callbackPath, ignoreCase = true)) {
                    writeResponse(output, 404, page("未找到", "这个地址不是授权回调。"))
                    return@use
                }
                val query = parseQuery(target.substringAfter('?', ""))
                val callback = Callback(
                    code = query["code"],
                    state = query["state"],
                    error = query["error"],
                    errorDescription = query["error_description"]
                )
                val success = callback.error == null && !callback.code.isNullOrBlank()
                if (success) {
                    writeResponse(output, 200, successPage())
                } else {
                    val detail = callback.errorDescription ?: callback.error ?: "未知错误"
                    writeResponse(output, 400, page("授权失败", escape(detail)))
                }
                return@withContext callback
            }
        }
        throw CancellationException("授权已取消")
    }

    /** Closes the listening socket. Safe to call multiple times. */
    fun stop() {
        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private fun readRequestLine(input: InputStream): String? {
        val reader = input.bufferedReader(Charsets.US_ASCII)
        val line = reader.readLine()?.take(MAX_REQUEST_LINE) ?: return null
        // Drain headers; the browser sends a handful and we never read the body.
        var guard = 0
        while (guard < MAX_HEADER_LINES) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            guard++
        }
        return line
    }

    private fun writeResponse(output: OutputStream, status: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            output.write(head.toByteArray(Charsets.UTF_8))
            output.write(body)
            output.flush()
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val key = decode(pair.substringBefore('=')) ?: return@mapNotNull null
            val value = if (pair.contains('=')) decode(pair.substringAfter('=')) ?: "" else ""
            key to value
        }.toMap()
    }

    private fun decode(raw: String): String? =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()

    // ------------------------------------------------------------------
    // pages
    // ------------------------------------------------------------------

    private fun page(title: String, message: String): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$title · Kujira</title>
        <style>
        :root { color-scheme: dark; }
        body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
               background:#0D0D0F; color:#E8E8ED;
               font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC",sans-serif; }
        .card { max-width:340px; padding:36px 28px; text-align:center; }
        .hint { color:#9A9BA5; font-size:14px; line-height:1.6; margin-top:12px; }
        h1 { font-size:22px; font-weight:600; margin:0 0 8px; }
        .dot { width:44px; height:44px; border-radius:22px; margin:0 auto 20px;
               background:#E07A5F; opacity:.9; }
        </style></head>
        <body><div class="card"><div class="dot"></div>
        <h1>${escape(title)}</h1><p class="hint">${escape(message)}</p>
        </div></body></html>
    """.trimIndent()

    private fun successPage(): String = page(
        "授权完成",
        "Kujira 已收到授权，正在为你连接 SentinelX。可以关闭这个页面并返回 App。"
    )

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        const val DEFAULT_PORT = 20573
        const val DEFAULT_CALLBACK_PATH = "/oauth/callback"
        const val DEFAULT_AUTH_TIMEOUT_MS = 15 * 60 * 1000L

        private const val HOST = "127.0.0.1"
        private const val ACCEPT_POLL_MS = 1_000
        private const val CLIENT_READ_TIMEOUT_MS = 10_000
        private const val MAX_HEADER_LINES = 64
        private const val MAX_REQUEST_LINE = 8_192
    }
}
