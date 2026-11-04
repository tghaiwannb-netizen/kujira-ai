package app.kujira.android.net.mcp

import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.data.model.ToolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPoliciesTest {

    private fun tool(name: String) = ToolDescriptor(
        pluginId = "p1",
        pluginName = "P1",
        name = name,
        description = "",
        inputSchemaJson = """{"type":"object","properties":{}}"""
    )

    private fun plugin(enabled: List<String> = emptyList(), policies: Map<String, ToolPolicy> = emptyMap()) =
        PluginConfig(
            id = "p1",
            name = "P1",
            url = "https://example.com/mcp",
            enabledToolNames = enabled,
            toolPolicies = policies
        )

    @Test
    fun `allowed tools default to auto, dangerous ones to ask`() {
        val read = tool("sentinel_read")
        val exec = tool("sentinel_exec")
        assertEquals(ToolPolicy.AUTO, ToolPolicies.defaultPolicy(read))
        assertEquals(ToolPolicy.ASK, ToolPolicies.defaultPolicy(exec))
    }

    @Test
    fun `allow list disables everything else`() {
        val plugin = plugin(enabled = listOf("a", "b"))
        assertEquals(ToolPolicy.AUTO, ToolPolicies.effective(plugin, tool("a")))
        assertEquals(ToolPolicy.DISABLED, ToolPolicies.effective(plugin, tool("c")))
    }

    @Test
    fun `empty allow list means every tool is available`() {
        val plugin = plugin()
        assertEquals(ToolPolicy.AUTO, ToolPolicies.effective(plugin, tool("anything")))
    }

    @Test
    fun `explicit override wins over the allow list and defaults`() {
        val plugin = plugin(enabled = listOf("a"), policies = mapOf("b" to ToolPolicy.ASK))
        assertEquals(ToolPolicy.ASK, ToolPolicies.effective(plugin, tool("b")))
        assertEquals(
            ToolPolicy.AUTO,
            ToolPolicies.effective(plugin(enabled = emptyList(), policies = mapOf("exec_sh" to ToolPolicy.AUTO)), tool("exec_sh"))
        )
    }

    @Test
    fun `withPolicy keeps allow list in sync`() {
        val enabled = ToolPolicies.withPolicy(plugin(), tool("a"), ToolPolicy.AUTO)
        assertTrue("a" in enabled.enabledToolNames)
        val disabled = ToolPolicies.withPolicy(enabled, tool("a"), ToolPolicy.DISABLED)
        assertFalse("a" in disabled.enabledToolNames)
        assertEquals(ToolPolicy.DISABLED, disabled.toolPolicies["a"])
    }

    @Test
    fun `enable many uses default policies and disable many removes them`() {
        val tools = listOf(tool("sentinel_read"), tool("sentinel_exec"))
        val enabled = ToolPolicies.enable(plugin(), tools, setOf("sentinel_read", "sentinel_exec"))
        assertEquals(ToolPolicy.AUTO, enabled.toolPolicies["sentinel_read"])
        assertEquals(ToolPolicy.ASK, enabled.toolPolicies["sentinel_exec"])
        assertEquals(2, enabled.enabledToolNames.size)

        val disabled = ToolPolicies.disable(enabled, setOf("sentinel_read", "sentinel_exec"))
        assertTrue(disabled.enabledToolNames.isEmpty())
        assertEquals(ToolPolicy.DISABLED, disabled.toolPolicies["sentinel_read"])
    }

    @Test
    fun `available filters disabled tools`() {
        val tools = listOf(tool("a"), tool("b"), tool("c"))
        val plugin = plugin(enabled = listOf("a", "c"), policies = mapOf("b" to ToolPolicy.DISABLED))
        assertEquals(listOf("a", "c"), ToolPolicies.available(plugin, tools).map { it.name })
        assertEquals(2, ToolPolicies.enabledCount(plugin, tools))
    }

    @Test
    fun `enableAll keeps dangerous tools on ask`() {
        val tools = listOf(tool("sentinel_read"), tool("sentinel_delete"))
        val plugin = ToolPolicies.enableAll(plugin(), tools)
        assertEquals(ToolPolicy.AUTO, plugin.toolPolicies["sentinel_read"])
        assertEquals(ToolPolicy.ASK, plugin.toolPolicies["sentinel_delete"])
        assertEquals(2, plugin.enabledToolNames.size)
    }
}
