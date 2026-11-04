package app.kujira.android

import androidx.test.core.app.ApplicationProvider
import app.kujira.android.core.SecureStore
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.model.ToolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Boot-path smoke tests: the DI container and encrypted storage must initialise
 * on a plain Android runtime before any screen can work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppContainerTest {

    private fun container(): app.kujira.android.di.AppContainer {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return app.kujira.android.di.AppContainer(context)
    }

    @Test
    fun containerInitialises() {
        val container = container()
        assertNotNull(container.database)
        assertNotNull(container.settingsStore)
        assertNotNull(container.llmRouter)
        assertNotNull(container.providerRepository)
        assertNotNull(container.mcpManager)
        assertNotNull(container.toolExecutor)
        assertNotNull(container.chatEngine)
        assertNotNull(container.sentinelXService)
    }

    @Test
    fun defaultsAreUsable() {
        val container = container()
        val settings = container.settingsStore.settings.value
        val providers = container.settingsStore.providers.value
        assertTrue(providers.isNotEmpty())
        assertNotNull(container.providerRepository.activeProvider())
        assertNotNull(settings.activeModelId)
        // SentinelX preset must always be present and enabled by default.
        val sentinel = container.settingsStore.plugins.value.firstOrNull { it.preset == "sentinelx" }
        assertNotNull(sentinel)
        assertTrue(sentinel!!.enabledToolNames.isNotEmpty())
    }

    @Test
    fun secureStoreRoundTrips() {
        val store = SecureStore(ApplicationProvider.getApplicationContext())
        store.put("test:key", "s3cret-value")
        assertEquals("s3cret-value", store.get("test:key"))
        store.remove("test:key")
        assertEquals(null, store.get("test:key"))
    }

    @Test
    fun toolPoliciesResolve() {
        val container = container()
        val tool = app.kujira.android.data.model.ToolDescriptor(
            pluginId = "sentinelx",
            pluginName = "SentinelX",
            name = "sentinel_exec",
            description = "run command",
            inputSchemaJson = "{}"
        )
        assertTrue(tool.isDangerous)
        assertEquals(ToolPolicy.ASK, container.toolExecutor.policyFor(tool))
    }
}
