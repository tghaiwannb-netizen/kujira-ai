package app.kujira.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.kujira.android.core.SecureStore
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.model.PluginConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SecretPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun secureStore() = SecureStore(
        context, SecretKeySpec(ByteArray(32) { 0x4B }, "AES")
    )

    @Test
    fun pluginHeadersAreEncryptedAndSurviveReload() {
        val prefs = context.getSharedPreferences("kujira_settings_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val secrets = secureStore()
        val store = SettingsStore(context, secrets)
        val plugin = PluginConfig(
            id = "header-test", name = "private-test",
            url = "https://example.org/mcp",
            headers = mapOf("X-API-Key" to "unique-super-secret-value")
        )
        store.upsertPlugin(plugin)
        assertFalse(prefs.getString("plugins_json", "")!!.contains("unique-super-secret-value"))
        val reloaded = SettingsStore(context, secrets)
        assertEquals("unique-super-secret-value",
            reloaded.plugins.value.first { it.id == "header-test" }.headers["X-API-Key"])
        store.deletePlugin(plugin.id)
        assertEquals(null, secrets.get("plugin:header-test:headers"))
    }

    @Test
    fun legacyPlaintextHeadersAreMigratedAndRedacted() {
        val prefs = context.getSharedPreferences("kujira_settings_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("plugins_json",
            """[{"id":"legacy-headers-test","name":"old","url":"https://example.org/mcp","headers":{"X-Token":"old-unencrypted-secret"}}]"""
        ).commit()
        val secrets = secureStore()
        val store = SettingsStore(context, secrets)
        assertEquals("old-unencrypted-secret",
            store.plugins.value.first { it.id == "legacy-headers-test" }.headers["X-Token"])
        assertFalse(prefs.getString("plugins_json", "")!!.contains("old-unencrypted-secret"))
        assertFalse(secrets.get("plugin:legacy-headers-test:headers").isNullOrBlank())
    }

    @Test
    fun legacyPlaintextSecretFallbackIsDiscarded() {
        val prefs = context.getSharedPreferences("kujira_secure_v1", Context.MODE_PRIVATE)
        prefs.edit().putString("unsafe-test-key", "plain:c29tZS1zZWNyZXQ=").commit()
        val store = secureStore()
        assertEquals(null, store.get("unsafe-test-key"))
        assertFalse(prefs.contains("unsafe-test-key"))
    }
}
