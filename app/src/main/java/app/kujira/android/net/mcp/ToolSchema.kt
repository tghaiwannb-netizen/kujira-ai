package app.kujira.android.net.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Small read-only helpers over a tool's JSON Schema (`inputSchema`).
 *
 * Used for parameter summaries in the plugin UI and for deciding whether a
 * `host_id` may be injected into a SentinelX tool call.
 */
object ToolSchema {

    const val HOST_ID = "host_id"

    private val prettyJson = Json { prettyPrint = true }

    /** Top-level `properties` of the schema, in declaration order. */
    fun properties(schemaJson: String?): Map<String, JsonElement> {
        val schema = McpJson.parseObject(schemaJson) ?: return emptyMap()
        val properties = schema["properties"] as? JsonObject ?: return emptyMap()
        return properties.entries.associate { (name, value) -> name to value }
    }

    /** Names listed in the schema's `required` array. */
    fun required(schemaJson: String?): Set<String> {
        val schema = McpJson.parseObject(schemaJson) ?: return emptySet()
        val required = schema["required"] as? JsonArray ?: return emptySet()
        return required.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    }

    fun hasParameter(schemaJson: String?, name: String): Boolean =
        properties(schemaJson).containsKey(name)

    /** One-line parameter summary, e.g. `参数：command*、timeout`. */
    fun summary(schemaJson: String?, maxParams: Int = 6): String {
        val properties = properties(schemaJson)
        if (properties.isEmpty()) return "无参数"
        val required = required(schemaJson)
        val names = properties.keys.take(maxParams).map { name ->
            if (name in required) "$name*" else name
        }
        val suffix = if (properties.size > maxParams) " 等 ${properties.size} 项" else ""
        return "参数：" + names.joinToString("、") + suffix
    }

    /** Pretty-printed schema for the expanded tool card. */
    fun pretty(schemaJson: String?): String {
        val element = McpJson.parse(schemaJson.orEmpty()) ?: return schemaJson.orEmpty()
        return runCatching {
            prettyJson.encodeToString(JsonElement.serializer(), element)
        }.getOrDefault(schemaJson.orEmpty())
    }
}
