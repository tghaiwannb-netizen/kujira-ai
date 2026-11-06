package app.kujira.android.core

import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.isOpenCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetsTest {

    @Test
    fun `catalogue ids and model ids are unique`() {
        val ids = ProviderPresets.picker.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        ProviderPresets.picker.forEach { preset ->
            val modelIds = preset.models.map { it.id }
            assertEquals("duplicate models in ${preset.id}", modelIds.size, modelIds.distinct().size)
            preset.defaultModelId?.let { default ->
                assertTrue("missing default $default in ${preset.id}", modelIds.contains(default))
            }
        }
    }

    @Test
    fun `opencode presets point at the inference gateway`() {
        val zen = ProviderPresets.openCodeZen
        val go = ProviderPresets.openCodeGo
        assertEquals(ProviderKind.OPENCODE_ZEN, zen.kind)
        assertEquals(ProviderKind.OPENCODE_GO, go.kind)
        assertTrue(zen.kind.isOpenCode)
        assertTrue(go.kind.isOpenCode)
        assertEquals("https://opencode.ai/inference/openai/v1", zen.baseUrl)
        assertEquals("https://opencode.ai/inference/go/openai/v1", go.baseUrl)
        assertEquals("https://opencode.ai/inference/v1/models", zen.modelsUrl)
        assertEquals(zen.modelsUrl, go.modelsUrl)
        assertTrue(zen.models.any { it.id == "deepseek-v4.1-flash" })
        assertTrue(go.models.any { it.id == "kimi-k3" })
        assertTrue(zen.description.contains("OpenCode"))
    }

    @Test
    fun `built-in providers keep the original four enabled`() {
        val core = ProviderPresets.core
        assertTrue(core.any { it.id == "deepseek" && it.enabledByDefault })
        assertTrue(core.any { it.id == "openai" && it.enabledByDefault })
        assertTrue(core.any { it.id == "anthropic" && it.enabledByDefault })
        assertTrue(core.any { it.id == "openrouter" && it.enabledByDefault })
        assertFalse(core.first { it.id == "opencode-zen" }.enabledByDefault)
        assertFalse(core.first { it.id == "opencode-go" }.enabledByDefault)
    }

    @Test
    fun `preset toProvider carries metadata and stable id`() {
        val provider = ProviderPresets.openCodeZen.toProvider()
        assertEquals("opencode-zen", provider.id)
        assertEquals("OpenCode Zen", provider.name)
        assertTrue(provider.builtin)
        assertNotNull(provider.apiKeyUrl)
        assertNotNull(provider.docsUrl)
        assertEquals("deepseek-v4.1-flash", provider.defaultModelId)
        assertEquals(ProviderKind.OPENCODE_ZEN, provider.kind)
    }
}
