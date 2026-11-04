package app.kujira.android.net.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * JSON-RPC 2.0 for MCP.
 *
 * The protocol layer is deliberately hand-written instead of generated from
 * @Serializable DTOs: MCP servers evolve quickly and often send fields we do
 * not model. Everything unknown is ignored, everything we need is read from
 * the JsonObject tree explicitly.
 */

/** JSON codec shared by the whole MCP layer. */
object McpJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Parses [text] as JSON; null when it is not valid JSON. */
    fun parse(text: String): JsonElement? {
        if (text.isBlank()) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    /** Parses [text] as a JSON object; null when it is not an object. */
    fun parseObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return parse(text) as? JsonObject
    }

    /** Reads a string field, treating JSON null as absent. */
    fun string(element: JsonElement?, key: String): String? =
        ((element as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    /** Reads a boolean field, treating JSON null as absent. */
    fun boolean(element: JsonElement?, key: String): Boolean? =
        ((element as? JsonObject)?.get(key) as? JsonPrimitive)?.let { primitive ->
            if (primitive.isString) primitive.contentOrNull?.toBooleanStrictOrNull()
            else primitive.contentOrNull?.toBooleanStrictOrNull()
        }

    /** Reads an int field, treating JSON null as absent. */
    fun int(element: JsonElement?, key: String): Int? =
        ((element as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

    /** Reads a nested object field. */
    fun obj(element: JsonElement?, key: String): JsonObject? =
        (element as? JsonObject)?.get(key) as? JsonObject

    /** Reads an array field. */
    fun array(element: JsonElement?, key: String): JsonArray? =
        (element as? JsonObject)?.get(key) as? JsonArray
}

/** The `error` member of a JSON-RPC response. */
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    /** A single human-readable line suitable for logs and error cards. */
    fun messageFor(method: String? = null): String {
        val prefix = if (method.isNullOrBlank()) "MCP 调用失败" else "MCP $method 调用失败"
        val detail = (data as? JsonPrimitive)?.contentOrNull
            ?: (data as? JsonObject)?.let { obj ->
                (obj["detail"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
            }
        val suffix = if (!detail.isNullOrBlank() && detail != message) " — ${detail.take(300)}" else ""
        return "$prefix（$code）：$message$suffix"
    }

    fun asException(method: String? = null): McpException = McpException(messageFor(method))
}

/** A parsed JSON-RPC response frame. Notifications decode to an empty frame. */
data class JsonRpcResponse(
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
) {
    /** True when this frame carries a request id, result or error (i.e. not a server notification). */
    val isResponse: Boolean
        get() = id != null || result != null || error != null

    fun resultObject(): JsonObject? = result as? JsonObject

    /** Returns the result object or throws the frame's error. */
    fun requireResultObject(method: String): JsonObject {
        error?.let { throw it.asException(method) }
        return result as? JsonObject
            ?: throw McpException("MCP $method 返回了意外的结果：${result?.toString()?.take(300) ?: "null"}")
    }
}

object JsonRpc {

    const val VERSION = "2.0"

    /** JSON-RPC "Method not found" — a ping may use it to detect liveness. */
    const val METHOD_NOT_FOUND = -32601

    /** JSON-RPC internal error, used when a server only reports a string. */
    const val INTERNAL_ERROR = -32603

    /** Encodes a request frame (with id). */
    fun request(id: Long, method: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", JsonPrimitive(id))
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    /** Encodes a notification frame (no id, never answered). */
    fun notification(method: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    /** Encodes a JSON-RPC error frame; useful for tests and fakes. */
    fun error(id: Long, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", JsonPrimitive(id))
            put(
                "error",
                buildJsonObject {
                    put("code", JsonPrimitive(code))
                    put("message", message)
                }
            )
        }.toString()

    /**
     * Decodes one frame. Throws [McpException] when the payload is not JSON at
     * all; an empty result (notification) is not an error.
     */
    fun decode(text: String): JsonRpcResponse {
        val payload = text.trim()
        if (payload.isEmpty()) throw McpException("MCP 服务器返回了空响应")
        val element = McpJson.parse(payload)
            ?: throw McpException("MCP 服务器返回了非 JSON 内容：${payload.take(180)}")
        val obj = element as? JsonObject
            ?: throw McpException("MCP 服务器返回了意外的 JSON：${payload.take(180)}")
        return fromObject(obj)
    }

    /** Decodes one frame from a JSON object. */
    fun fromObject(obj: JsonObject): JsonRpcResponse = JsonRpcResponse(
        id = obj["id"]?.takeIf { it !is JsonNull },
        result = obj["result"]?.takeIf { it !is JsonNull },
        error = parseError(obj["error"])
    )

    /** Decodes a single frame or a JSON-RPC batch. Malformed frames are skipped. */
    fun decodeBatch(text: String): List<JsonRpcResponse> {
        val element = McpJson.parse(text.trim()) ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonObject)?.let(::fromObject) }
            is JsonObject -> listOf(fromObject(element))
            else -> emptyList()
        }
    }

    /** Lenient decode used while scanning SSE frames; null when the frame is not usable. */
    fun decodeOrNull(text: String): JsonRpcResponse? = runCatching { decode(text) }.getOrNull()

    /** Composes a JSON-RPC error object from an arbitrary `error` member. */
    fun parseError(element: JsonElement?): JsonRpcError? {
        if (element == null || element is JsonNull) return null
        if (element is JsonPrimitive) {
            return JsonRpcError(INTERNAL_ERROR, element.contentOrNull ?: "未知错误")
        }
        val obj = element as? JsonObject ?: return null
        val code = (obj["code"] as? JsonPrimitive)?.intOrNull ?: INTERNAL_ERROR
        val message = (obj["message"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["detail"] as? JsonPrimitive)?.contentOrNull
            ?: "未知错误"
        return JsonRpcError(code, message, obj["data"]?.takeIf { it !is JsonNull })
    }

    /**
     * Compares request ids across number/string spellings. Some servers echo an
     * id as a JSON string even when it was sent as a number.
     */
    fun sameId(a: JsonElement?, b: JsonElement?): Boolean {
        if (a == null || b == null) return false
        if (a is JsonNull || b is JsonNull) return false
        if (a is JsonPrimitive && b is JsonPrimitive) return a.content == b.content
        return a == b
    }

    /** True when [error] reports "method not found". */
    fun isMethodNotFound(error: JsonRpcError?): Boolean = error?.code == METHOD_NOT_FOUND
}
