package app.kujira.android.net.mcp

import app.kujira.android.data.model.ToolDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP client over the Streamable HTTP transport.
 *
 * Lifecycle: [connect] (initialize + notifications/initialized), then
 * [listTools] / [callTool] / [ping]. One instance is one session; reconnect by
 * creating a new client.
 */
class McpHttpClient(
    endpoint: String,
    private val pluginId: String = "",
    private val pluginName: String = "MCP",
    auth: McpAuthTokenProvider? = null,
    extraHeaders: Map<String, String> = emptyMap(),
    okHttpClient: OkHttpClient = OkHttpClient(),
    private val logger: (String) -> Unit = {}
) : McpClient {

    private val transport = StreamableHttpTransport(
        endpoint = endpoint,
        okHttpClient = okHttpClient,
        extraHeaders = extraHeaders,
        auth = auth,
        logger = logger
    )

    private val ids = AtomicLong(1L)

    @Volatile
    private var serverInfo: JsonObject? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    val sessionId: String? get() = transport.sessionId
    val endpointUrl: String get() = transport.endpointUrl

    override suspend fun connect(): McpConnection {
        serverInfo = null
        isConnected = false

        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", CLIENT_NAME)
                    put("version", CLIENT_VERSION)
                }
            )
        }

        val result = rpc("initialize", params, INIT_TIMEOUT_MS).requireResultObject("initialize")
        val info = result["serverInfo"] as? JsonObject
        serverInfo = info
        val protocolVersion = McpJson.string(result, "protocolVersion") ?: PROTOCOL_VERSION

        // MCP requires the initialized notification once the handshake succeeded.
        // Some servers answer 202 with an empty body, which the transport accepts.
        transport.post(
            payload = JsonRpc.notification("notifications/initialized"),
            expectedId = null,
            timeoutMs = INIT_TIMEOUT_MS
        )

        isConnected = true
        logger("MCP 已连接：${McpJson.string(info, "name") ?: pluginName} / $protocolVersion")

        return McpConnection(
            protocolVersion = protocolVersion,
            serverName = McpJson.string(info, "name"),
            serverVersion = McpJson.string(info, "version"),
            instructions = McpJson.string(result, "instructions"),
            sessionId = transport.sessionId
        )
    }

    override suspend fun listTools(): List<ToolDescriptor> {
        val tools = mutableListOf<ToolDescriptor>()
        var cursor: String? = null
        var pages = 0

        do {
            val params = buildJsonObject {
                val current = cursor
                if (current != null) put("cursor", current)
            }
            val result = rpc("tools/list", params, LIST_TIMEOUT_MS).requireResultObject("tools/list")
            val array = result["tools"] as? JsonArray ?: JsonArray(emptyList())
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val name = McpJson.string(obj, "name")?.takeIf { it.isNotBlank() } ?: continue
                val description = McpJson.string(obj, "description")
                    ?: McpJson.string(obj, "title")
                    ?: ""
                val inputSchema = (obj["inputSchema"] ?: obj["input_schema"])?.toString() ?: DEFAULT_INPUT_SCHEMA
                tools += ToolDescriptor(
                    pluginId = pluginId,
                    pluginName = pluginName,
                    name = name,
                    description = description.trim(),
                    inputSchemaJson = inputSchema,
                    serverName = McpJson.string(serverInfo, "name")
                )
            }
            cursor = McpJson.string(result, "nextCursor")?.takeIf { it.isNotBlank() }
            pages++
        } while (cursor != null && pages < MAX_TOOL_PAGES)

        logger("MCP 工具列表：${tools.size} 个（$pluginName）")
        return tools
    }

    override suspend fun callTool(name: String, argumentsJson: String): McpToolResult {
        val arguments = parseArguments(argumentsJson)
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = rpc("tools/call", params, CALL_TIMEOUT_MS).requireResultObject("tools/call")
        val toolResult = McpContent.toToolResult(result)
        logger("MCP 工具 $name 返回（isError=${toolResult.isError}，${toolResult.output.length} 字符）")
        return toolResult
    }

    override suspend fun ping(): Boolean = try {
        rpc("ping", null, PING_TIMEOUT_MS)
        true
    } catch (auth: McpAuthRequiredException) {
        throw auth
    } catch (failure: McpException) {
        // "Method not found" still proves the server is alive and speaking JSON-RPC.
        failure.message?.contains(JsonRpc.METHOD_NOT_FOUND.toString()) == true
    } catch (failure: Exception) {
        false
    }

    override fun close() {
        isConnected = false
        transport.close()
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private suspend fun rpc(method: String, params: JsonObject?, timeoutMs: Long): JsonRpcResponse {
        val id = ids.getAndIncrement()
        val payload = JsonRpc.request(id, method, params)
        val response = transport.post(payload, JsonPrimitive(id), timeoutMs)
            ?: throw McpException("MCP 服务器没有返回 $method 的响应")
        response.error?.let { throw it.asException(method) }
        return response
    }

    private fun parseArguments(argumentsJson: String): JsonElement {
        val trimmed = argumentsJson.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return McpJson.parse(trimmed)
            ?: throw McpException("工具参数不是合法 JSON：${trimmed.take(180)}")
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val CLIENT_NAME = "Kujira"
        const val CLIENT_VERSION = "1.0.0"
        const val DEFAULT_INPUT_SCHEMA = """{"type":"object","properties":{}}"""
        const val MAX_TOOL_PAGES = 10

        const val INIT_TIMEOUT_MS = 30_000L
        const val LIST_TIMEOUT_MS = 60_000L
        const val CALL_TIMEOUT_MS = 180_000L
        const val PING_TIMEOUT_MS = 15_000L
    }
}
