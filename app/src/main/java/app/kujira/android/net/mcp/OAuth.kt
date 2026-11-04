package app.kujira.android.net.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Where a plugin's OAuth 2.0 authorization-code flow points.
 *
 * SentinelX values live in [app.kujira.android.net.sentinelx.SentinelXPreset];
 * this type is transport-agnostic and Android-free so it can be unit tested.
 */
data class OAuthConfig(
    val pluginId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val redirectPort: Int,
    val callbackPath: String = LoopbackServer.DEFAULT_CALLBACK_PATH,
    val scopes: List<String> = emptyList(),
    val extraAuthParams: Map<String, String> = emptyMap(),
    val tokenKey: String = McpSecrets.oauthKey(pluginId)
)

/** Persisted OAuth token. `expires_at` is an absolute epoch-millis deadline. */
@Serializable
data class OAuthToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 0L,
    @SerialName("expires_at") val expiresAt: Long = 0L,
    @SerialName("scope") val scope: String? = null
) {
    /** True when the token expires within [skewMs] (refresh proactively). */
    fun isExpiring(now: Long = System.currentTimeMillis(), skewMs: Long = OAuthService.REFRESH_SKEW_MS): Boolean =
        expiresAt <= 0L || expiresAt - now <= skewMs
}

/** PKCE (RFC 7636) + authorization URL helpers. Pure JVM, unit tested. */
object Pkce {

    const val VERIFIER_BYTES = 48
    const val STATE_BYTES = 16

    /** High-entropy code verifier, base64url without padding. */
    fun verifier(bytes: Int = VERIFIER_BYTES): String = base64Url(randomBytes(bytes))

    /** S256 challenge of [verifier]. */
    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Random hex state (16 bytes, matching the desktop bridge). */
    fun state(bytes: Int = STATE_BYTES): String =
        randomBytes(bytes).joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Builds the Keycloak authorization URL including PKCE + extra params. */
    fun authorizationUrl(config: OAuthConfig, codeChallenge: String, state: String): String {
        val builder = config.authorizationEndpoint.toHttpUrl().newBuilder()
        builder.addQueryParameter("response_type", "code")
        builder.addQueryParameter("client_id", config.clientId)
        builder.addQueryParameter("code_challenge", codeChallenge)
        builder.addQueryParameter("code_challenge_method", "S256")
        builder.addQueryParameter("redirect_uri", config.redirectUri)
        builder.addQueryParameter("state", state)
        if (config.scopes.isNotEmpty()) {
            builder.addQueryParameter("scope", config.scopes.joinToString(" "))
        }
        config.extraAuthParams.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }
}

/**
 * OAuth 2.0 Authorization Code + PKCE with a loopback redirect.
 *
 * Flow: start [LoopbackServer] on 127.0.0.1:20573 → open the authorization URL
 * in a Custom Tab → receive `code` → exchange it at the token endpoint → store
 * the encrypted token JSON in [app.kujira.android.core.SecureStore].
 *
 * Tokens are treated as one opaque JSON blob so refresh_token rotation works
 * transparently. Refreshing is proactive: [OAuthToken.isExpiring] fires
 * [REFRESH_SKEW_MS] before the real expiry.
 */
class OAuthService(
    okHttpClient: OkHttpClient = OkHttpClient(),
    private val tokenTimeoutMs: Long = 30_000L,
    private val logger: (String) -> Unit = {},
    private val openBrowser: (Context, String) -> Unit = { context, url -> openCustomTab(context, url) }
) {

    private val http: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Stored token for a plugin, or null when the user never authorized. */
    fun tokens(store: app.kujira.android.core.SecureStore, config: OAuthConfig): OAuthToken? {
        val raw = store.get(config.tokenKey) ?: return null
        return runCatching { McpJson.json.decodeFromString(OAuthToken.serializer(), raw) }.getOrNull()
    }

    /** True when a usable (or refreshable) token exists. */
    fun isAuthorized(store: app.kujira.android.core.SecureStore, config: OAuthConfig): Boolean {
        val token = tokens(store, config) ?: return false
        if (!token.isExpiring()) return true
        return !token.refreshToken.isNullOrBlank()
    }

    /** Epoch millis when the access token expires, for status text. */
    fun expiresAt(store: app.kujira.android.core.SecureStore, config: OAuthConfig): Long? =
        tokens(store, config)?.expiresAt?.takeIf { it > 0L }

    /** Removes the stored token (logout). */
    fun clear(store: app.kujira.android.core.SecureStore, config: OAuthConfig) {
        store.remove(config.tokenKey)
    }

    /**
     * Runs the browser authorization flow end to end and stores the token.
     * Must be called with a Context able to launch an Activity (Application or Activity).
     */
    suspend fun authorize(
        context: Context,
        store: app.kujira.android.core.SecureStore,
        config: OAuthConfig
    ): OAuthToken {
        val verifier = Pkce.verifier()
        val challenge = Pkce.challenge(verifier)
        val state = Pkce.state()
        val server = LoopbackServer(config.redirectPort, config.callbackPath, logger)
        try {
            server.start()
            val url = Pkce.authorizationUrl(config, challenge, state)
            logger("打开授权页：$url")
            try {
                withContext(Dispatchers.Main) { openBrowser(context, url) }
            } catch (failure: McpException) {
                throw failure
            } catch (failure: Throwable) {
                throw McpException("无法打开浏览器完成授权：${failure.message ?: failure.javaClass.simpleName}", failure)
            }

            val callback = server.awaitCallback()
            callback.error?.let {
                val description = callback.errorDescription?.takeIf { text -> text.isNotBlank() }
                throw McpAuthRequiredException(description ?: "授权被拒绝：$it")
            }
            val code = callback.code?.takeIf { it.isNotBlank() }
                ?: throw McpException("授权回调缺少 code 参数")
            if (callback.state != state) {
                throw McpException("授权回调的 state 不匹配，已拒绝本次响应")
            }

            val token = try {
                requestToken(
                    config,
                    mapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to config.redirectUri,
                        "client_id" to config.clientId,
                        "code_verifier" to verifier
                    )
                )
            } catch (failure: TokenEndpointFailure) {
                throw McpAuthRequiredException("用授权码换取 token 失败：${failure.detail.take(300)}")
            }
            save(store, config, token)
            logger("OAuth 授权成功（${config.pluginId}）")
            return token
        } finally {
            server.stop()
        }
    }

    /**
     * Returns a valid access token, refreshing it when it is about to expire or
     * when [forceRefresh] is set. Throws [McpAuthRequiredException] when the
     * user must authorize again.
     */
    suspend fun accessToken(
        store: app.kujira.android.core.SecureStore,
        config: OAuthConfig,
        forceRefresh: Boolean = false
    ): String {
        val token = tokens(store, config)
            ?: throw McpAuthRequiredException("尚未登录，请先完成授权")
        if (!forceRefresh && !token.isExpiring()) return token.accessToken

        val refreshToken = token.refreshToken
        if (refreshToken.isNullOrBlank()) {
            clear(store, config)
            throw McpAuthRequiredException("授权已过期，请重新登录")
        }

        logger("刷新 access token（${config.pluginId}）")
        val fresh = try {
            requestToken(
                config,
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to config.clientId
                )
            )
        } catch (failure: TokenEndpointFailure) {
            // invalid_grant etc. is terminal; a 5xx may be transient, so keep the token then.
            if (failure.status in 400..499) {
                clear(store, config)
                throw McpAuthRequiredException("授权已失效，请重新登录：${failure.detail.take(200)}")
            }
            throw McpException("刷新授权失败（HTTP ${failure.status}）：${failure.detail.take(200)}", failure)
        }

        val merged = if (fresh.refreshToken.isNullOrBlank()) fresh.copy(refreshToken = refreshToken) else fresh
        save(store, config, merged)
        return merged.accessToken
    }

    /** Token provider consumed by [StreamableHttpTransport] (401 → refresh once). */
    fun provider(
        store: app.kujira.android.core.SecureStore,
        config: OAuthConfig
    ): McpAuthTokenProvider = object : McpAuthTokenProvider {
        override suspend fun accessToken(): String = this@OAuthService.accessToken(store, config)

        override suspend fun refreshAccessToken(): String =
            this@OAuthService.accessToken(store, config, forceRefresh = true)
    }

    // ------------------------------------------------------------------
    // token endpoint
    // ------------------------------------------------------------------

    private fun save(
        store: app.kujira.android.core.SecureStore,
        config: OAuthConfig,
        token: OAuthToken
    ) {
        store.put(config.tokenKey, McpJson.json.encodeToString(OAuthToken.serializer(), token))
    }

    private suspend fun requestToken(config: OAuthConfig, form: Map<String, String>): OAuthToken =
        withContext(Dispatchers.IO) {
            val body = form.entries
                .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
                .toRequestBody(FORM_MEDIA_TYPE)
            val request = Request.Builder()
                .url(config.tokenEndpoint)
                .post(body)
                .header("accept", "application/json")
                .build()
            val call = http.newCall(request)
            runCatching { call.timeout().timeout(tokenTimeoutMs, TimeUnit.MILLISECONDS) }

            val response = try {
                call.execute()
            } catch (failure: IOException) {
                throw McpException(
                    "无法连接授权服务器：${failure.message ?: failure.javaClass.simpleName}",
                    failure
                )
            }

            response.use { res ->
                val text = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                if (!res.isSuccessful) {
                    throw TokenEndpointFailure(res.code, tokenErrorDetail(text))
                }
                val obj = McpJson.parseObject(text)
                    ?: throw McpException("授权服务器返回了非 JSON 响应：${text.take(200)}")
                val access = McpJson.string(obj, "access_token")
                    ?: throw McpException("授权服务器响应缺少 access_token")
                val expiresIn = McpJson.string(obj, "expires_in")?.toLongOrNull() ?: DEFAULT_EXPIRES_IN_SECONDS
                OAuthToken(
                    accessToken = access,
                    refreshToken = McpJson.string(obj, "refresh_token"),
                    tokenType = McpJson.string(obj, "token_type") ?: "Bearer",
                    expiresIn = expiresIn,
                    expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
                    scope = McpJson.string(obj, "scope")
                )
            }
        }

    private fun tokenErrorDetail(body: String): String {
        val obj = McpJson.parseObject(body)
        val detail = listOf("error_description", "error", "message", "detail")
            .firstNotNullOfOrNull { key -> McpJson.string(obj, key)?.takeIf { it.isNotBlank() } }
        return detail ?: body.take(300).ifBlank { "未知错误" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private class TokenEndpointFailure(val status: Int, val detail: String) :
        Exception("token endpoint $status: $detail")

    companion object {

        /** Refresh this long before the real expiry. */
        const val REFRESH_SKEW_MS = 60_000L

        private const val DEFAULT_EXPIRES_IN_SECONDS = 300L

        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

        /** Opens [url] in a Custom Tab, falling back to a plain browser intent. */
        fun openCustomTab(context: Context, url: String) {
            val uri = Uri.parse(url)
            val customTabs = CustomTabsIntent.Builder().setShowTitle(true).build()
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                customTabs.launchUrl(context, uri)
            } catch (failure: Throwable) {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
