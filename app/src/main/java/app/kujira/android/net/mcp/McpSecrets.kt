package app.kujira.android.net.mcp

import app.kujira.android.core.SecureStore

/**
 * SecureStore key conventions for MCP credentials.
 *
 * Kept in one place so the UI (token entry), the manager and the OAuth service
 * cannot drift apart. Values live in the Keystore-encrypted [SecureStore]; the
 * plugin config only ever references these keys.
 */
object McpSecrets {

    /** Bearer token for a plugin whose auth mode is [app.kujira.android.data.model.McpAuthMode.BEARER]. */
    fun bearerKey(pluginId: String): String = "plugin:$pluginId:bearer"

    /** OAuth token JSON for a plugin authorized through the loopback flow. */
    fun oauthKey(pluginId: String): String = "oauth:$pluginId"

    fun bearerToken(store: SecureStore, pluginId: String): String? =
        store.get(bearerKey(pluginId))?.takeIf { it.isNotBlank() }

    fun putBearerToken(store: SecureStore, pluginId: String, token: String?) {
        if (token.isNullOrBlank()) store.remove(bearerKey(pluginId))
        else store.put(bearerKey(pluginId), token)
    }

    fun removeAll(store: SecureStore, pluginId: String) {
        store.remove(bearerKey(pluginId))
        store.remove(oauthKey(pluginId))
    }
}
