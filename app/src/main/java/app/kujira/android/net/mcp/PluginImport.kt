package app.kujira.android.net.mcp

import app.kujira.android.data.model.McpAuthMode
import app.kujira.android.data.model.PluginConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Imports plugins from a Claude Desktop style `mcpServers` JSON blob.
 *
 * Only Streamable HTTP servers can be imported; stdio entries (`command` /
 * `args`) are reported as skipped because the Android client has no local
 * process transport. `Authorization` headers are preserved verbatim, which
 * makes such a plugin work immediately (the token stays in the plugin's
 * headers config; the edit screen offers moving it into the encrypted store).
 */
object PluginImport {

    data class Skipped(val name: String, val reason: String)

    data class Result(
        val plugins: List<PluginConfig> = emptyList(),
        val skipped: List<Skipped> = emptyList(),
        val warnings: List<String> = emptyList(),
        val error: String? = null
    )

    /** Parses the pasted text. Never throws; problems come back in [Result.error]. */
    fun parse(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result(error = "请先粘贴 mcpServers JSON")
        val element = McpJson.parse(trimmed) ?: return Result(error = "不是合法的 JSON")
        return parseElement(element)
    }

    /** Parses an already-decoded JSON tree (object root or the `mcpServers` object itself). */
    fun parseElement(element: JsonElement): Result {
        val root = element as? JsonObject
            ?: return Result(error = "顶层必须是 JSON 对象")
        val servers = (root["mcpServers"] as? JsonObject) ?: root
        if (servers.isEmpty()) return Result(error = "没有找到任何服务器配置")

        val plugins = mutableListOf<PluginConfig>()
        val skipped = mutableListOf<Skipped>()
        val warnings = mutableListOf<String>()

        for ((name, value) in servers) {
            val spec = value as? JsonObject
            if (spec == null) {
                skipped += Skipped(name, "配置不是 JSON 对象")
                continue
            }
            val url = findUrl(spec)
            if (url == null) {
                skipped += Skipped(name, skipReason(spec))
                continue
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                skipped += Skipped(name, "url 不是 http(s) 地址")
                continue
            }
            val headers = findHeaders(spec)
            val hasAuthorization = headers.keys.any { it.equals("authorization", ignoreCase = true) }
            if (hasAuthorization) {
                warnings += "「$name」的 Authorization 头已原样保留；如需加密保存，请在编辑页改用 Bearer 认证并单独填写 token。"
            }
            plugins += PluginConfig(
                name = name.ifBlank { "导入的插件" },
                emoji = "🔌",
                url = url,
                authMode = McpAuthMode.NONE,
                headers = headers
            )
        }

        return Result(
            plugins = plugins,
            skipped = skipped,
            warnings = warnings,
            error = if (plugins.isEmpty() && skipped.isEmpty()) "没有可导入的服务器" else null
        )
    }

    private fun skipReason(spec: JsonObject): String = when {
        spec["command"] != null -> "stdio 传输（command/args）暂不支持，请使用 Streamable HTTP 的 url"
        spec["transport"] != null -> "transport 不是 HTTP 类型"
        spec["server"] != null -> "嵌套 server 配置里缺少 url"
        else -> "缺少 url 字段"
    }

    private fun findUrl(spec: JsonObject): String? {
        fun text(key: String): String? =
            (spec[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        text("url")?.let { return it }
        text("serverUrl")?.let { return it }
        text("endpoint")?.let { return it }
        (spec["transport"] as? JsonObject)?.let { transport ->
            (transport["url"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.startsWith("http") }
                ?.let { return it }
        }
        (spec["server"] as? JsonObject)?.let { server ->
            (server["url"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.startsWith("http") }
                ?.let { return it }
        }
        return null
    }

    private fun findHeaders(spec: JsonObject): Map<String, String> {
        fun readHeaders(element: JsonObject?): Map<String, String> {
            val headers = element?.get("headers") as? JsonObject ?: return emptyMap()
            return headers.entries.mapNotNull { (key, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull
                if (key.isBlank() || text.isNullOrBlank()) null else key to text
            }.toMap()
        }
        val direct = readHeaders(spec)
        if (direct.isNotEmpty()) return direct
        return readHeaders(spec["transport"] as? JsonObject) + readHeaders(spec["server"] as? JsonObject)
    }
}
