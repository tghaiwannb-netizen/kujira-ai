package app.kujira.android.net.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Turns a `tools/call` result into readable text.
 *
 * MCP servers answer with `content[]` blocks (text / image / audio / resource /
 * resource_link). Binary blocks are summarised instead of dumped so a
 * base64 screenshot never enters the transcript or the audit log.
 */
object McpContent {

    fun toToolResult(result: JsonObject): McpToolResult {
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        val rendered = render(result["content"])
        val structured = (result["structuredContent"] as? JsonObject)?.toString()
        val output = when {
            rendered.isNotBlank() -> rendered
            !structured.isNullOrBlank() -> structured
            else -> "(空结果)"
        }
        return McpToolResult(output = output, isError = isError, structuredJson = structured)
    }

    /** Renders all content blocks, one per line. */
    fun render(content: JsonElement?): String {
        val blocks = content as? JsonArray ?: return ""
        return blocks.mapNotNull { renderBlock(it) }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    /** Renders one content block; unknown block types fall back to compact JSON. */
    fun renderBlock(element: JsonElement): String? {
        if (element is JsonPrimitive) return element.contentOrNull
        val obj = element as? JsonObject ?: return null
        return when (val type = (obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull
            "image" -> describeBinary("图片", obj)
            "audio" -> describeBinary("音频", obj)
            "resource" -> renderResource(obj)
            "resource_link", "resourceLink" -> {
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                val uri = (obj["uri"] as? JsonPrimitive)?.contentOrNull
                val label = listOfNotNull(name, uri).joinToString(" ").trim()
                if (label.isEmpty()) "[资源链接]" else "[资源链接] $label"
            }
            else -> {
                (obj["text"] as? JsonPrimitive)?.contentOrNull ?: obj.toString()
            }
        }
    }

    private fun renderResource(obj: JsonObject): String {
        val resource = obj["resource"] as? JsonObject ?: return "[资源]"
        val text = (resource["text"] as? JsonPrimitive)?.contentOrNull
        if (!text.isNullOrBlank()) return text
        val uri = (resource["uri"] as? JsonPrimitive)?.contentOrNull
        val mime = (resource["mimeType"] as? JsonPrimitive)?.contentOrNull
        return when {
            !uri.isNullOrBlank() && !mime.isNullOrBlank() -> "[资源 $mime] $uri"
            !uri.isNullOrBlank() -> "[资源] $uri"
            else -> "[资源]"
        }
    }

    private fun describeBinary(label: String, obj: JsonObject): String {
        val mime = (obj["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "未知类型"
        val base64 = (obj["data"] as? JsonPrimitive)?.contentOrNull
        val bytes = if (base64 == null) 0 else (base64.length.toLong() * 3L) / 4L
        val size = when {
            bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
            bytes >= 1024L -> "${bytes / 1024L} KB"
            bytes > 0 -> "$bytes B"
            else -> "未知大小"
        }
        return "[$label $mime，$size]"
    }
}
