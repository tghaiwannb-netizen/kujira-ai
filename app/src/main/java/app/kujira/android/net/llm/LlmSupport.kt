package app.kujira.android.net.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Shared JSON configuration for every provider payload.
 *
 * Providers add fields all the time, so unknown keys are ignored instead of
 * failing the whole stream; `explicitNulls = false` keeps request bodies tidy.
 */
val LlmJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

internal val JsonMediaType: MediaType = "application/json; charset=utf-8".toMediaType()
internal val EventStreamMediaType: MediaType = "text/event-stream".toMediaType()

/**
 * OpenCode Zen / Go use a stable per-conversation id for sticky routing and
 * prompt-cache reuse. Go requires it. Keep the value to the character set the
 * gateway accepts and cap it at its documented 128-character limit.
 */
internal fun openCodeSessionId(sessionId: String?): String {
    val sanitized = sessionId
        ?.trim()
        ?.replace(Regex("[^\\w.:-]"), "-")
        ?.take(128)
        .orEmpty()
    return sanitized.ifBlank { "kujira" }
}

/** Non-2xx HTTP response. [message] is already a human-readable Chinese explanation. */
internal class LlmHttpException(
    val code: Int,
    val rawBody: String?,
    message: String,
) : IOException(message)

/**
 * A protocol-level failure discovered inside an otherwise healthy SSE stream
 * (for example an `{"error": ...}` chunk or an Anthropic `error` event).
 */
internal class LlmStreamException(
    message: String,
    val code: Int? = null,
    val retryable: Boolean = false,
) : Exception(message)

/**
 * Maps transport and HTTP failures onto user-facing Chinese messages.
 *
 * Kept as pure functions so the mapping is unit-testable without a network.
 */
internal object LlmErrors {

    fun isRetryableCode(code: Int?): Boolean =
        code == null || code == 408 || code == 409 || code == 425 || code == 429 || code in 500..599

    fun friendlyHttpMessage(code: Int, rawBody: String?): String {
        val detail = serverDetail(rawBody)?.let { "：${it.take(300)}" }.orEmpty()
        return when (code) {
            400 -> "请求参数有误（400）$detail"
            401 -> "API Key 无效或已过期（401），请到设置中检查该 Provider 的密钥。$detail"
            403 -> "没有访问权限（403），请检查账号权限、地区限制或模型是否已开通。$detail"
            404 -> "接口地址或模型不存在（404），请检查 Base URL 和模型 ID。$detail"
            408 -> "请求超时（408），请稍后重试。$detail"
            413 -> "请求内容过大（413），请缩短对话或减小输入。$detail"
            422 -> "请求无法被模型处理（422），请检查参数或输入内容。$detail"
            429 -> "请求过于频繁或额度不足（429），请稍后重试或检查账户余额与限流设置。$detail"
            503 -> "模型服务暂时不可用（503），请稍后重试。$detail"
            in 500..599 -> "模型服务端错误（$code），通常是临时问题，请稍后重试。$detail"
            else -> "请求失败（$code）$detail"
        }.trim()
    }

    fun ioFailure(e: IOException): LlmEvent.Failed = when (e) {
        is SocketTimeoutException -> LlmEvent.Failed(
            "请求超时：服务器长时间没有响应，请检查网络后重试。",
            null,
            true
        )

        is UnknownHostException -> LlmEvent.Failed(
            "无法解析服务器地址，请检查网络或 Provider 的 Base URL。",
            null,
            true
        )

        is ConnectException -> LlmEvent.Failed(
            "无法连接到服务器，请检查网络连接后重试。",
            null,
            true
        )

        is SSLException -> LlmEvent.Failed(
            "安全连接（TLS）失败，可能是网络代理或证书问题。",
            null,
            false
        )

        is InterruptedIOException -> LlmEvent.Failed(
            "请求被中断或超时，请重试。",
            null,
            true
        )

        is SocketException -> LlmEvent.Failed(
            "网络连接被中断（${e.message ?: "socket error"}），请重试。",
            null,
            true
        )

        else -> LlmEvent.Failed(
            "网络请求失败：${e.message ?: e.javaClass.simpleName}",
            null,
            true
        )
    }

    /** Pulls a provider error message out of a JSON error body, if there is one. */
    fun serverDetail(rawBody: String?): String? {
        val body = rawBody?.trim().orEmpty()
        if (body.isEmpty()) return null
        val element = runCatching { LlmJson.parseToJsonElement(body) }.getOrNull()
        val obj = element as? JsonObject
        if (obj == null) return body.take(300)
        val error = obj["error"]
        val nested = error as? JsonObject
        return nested?.stringOf("message")
            ?: obj.stringOf("message")
            ?: obj.stringOf("detail")
            ?: (error as? JsonPrimitive)?.contentOrNull
            ?: nested?.stringOf("type")
            ?: body.take(300)
    }
}

// ---------------------------------------------------------------------------
// Small JSON helpers shared by the wire builders and the stream aggregators.
// ---------------------------------------------------------------------------

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonElement?.asInt(): Int? = asString()?.trim()?.toIntOrNull()

internal fun JsonElement?.asLong(): Long? = asString()?.trim()?.toLongOrNull()

internal fun JsonElement?.asDouble(): Double? = asString()?.trim()?.toDoubleOrNull()

internal fun JsonElement?.asBoolean(): Boolean? = when (val s = asString()?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

internal fun JsonObject.stringOf(key: String): String? = this[key].asString()

internal fun JsonObject.intOf(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { this[it].asInt() }

internal fun JsonObject.longOf(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { this[it].asLong() }

/** Serializes a tool argument fragment that may arrive as a JSON string or an object. */
internal fun JsonElement?.asArgumentFragment(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull.takeUnless { it.isNullOrEmpty() }
    else -> toString()
}
