package app.kujira.android.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("kujira_settings_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `fresh install seeds opencode once`() {
        val store = SettingsStore(context)
        val providers = store.providers.value
        assertTrue(providers.any { it.id == "opencode-zen" })
        assertTrue(providers.any { it.id == "opencode-go" })
        assertFalse(providers.first { it.id == "opencode-zen" }.enabled)
        assertFalse(providers.first { it.id == "opencode-go" }.enabled)
    }

    @Test
    fun `upgrade from an older provider list adds opencode`() {
        val store = SettingsStore(context)
        store.saveProviders(store.providers.value.filterNot { it.id.startsWith("opencode-") })
        // Simulate a settings file written before provider-preset seeding existed.
        context.getSharedPreferences("kujira_settings_v1", Context.MODE_PRIVATE)
            .edit()
            .remove("seeded_provider_presets")
            .commit()

        val upgraded = SettingsStore(context)
        assertTrue(upgraded.providers.value.any { it.id == "opencode-zen" })
        assertTrue(upgraded.providers.value.any { it.id == "opencode-go" })
    }

    @Test
    fun `deleting a built-in provider stays deleted`() {
        val store = SettingsStore(context)
        store.saveProviders(store.providers.value.filterNot { it.id == "opencode-zen" })

        val reopened = SettingsStore(context)
        assertFalse(reopened.providers.value.any { it.id == "opencode-zen" })
        // Other core presets are untouched.
        assertTrue(reopened.providers.value.any { it.id == "deepseek" })
    }
}
