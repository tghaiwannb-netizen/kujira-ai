package app.kujira.android.net.mcp

import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.data.model.ToolPolicy

/*
 * Tool enablement / confirmation policy resolution.
 *
 * Two sources describe which tools a plugin exposes:
 *  - `PluginConfig.enabledToolNames` — the allow-list stamped by the preset
 *    (SentinelX seeds a few safe tools because exposing all 95 would eat the
 *    context window). An empty list means "no allow-list, everything is fair".
 *  - `PluginConfig.toolPolicies` — explicit per-tool overrides written by the
 *    user in the plugin detail screen.
 *
 * Explicit overrides always win; otherwise a tool not on a non-empty
 * allow-list is disabled, and the remaining tools default to ASK for
 * dangerous names (exec/delete/restart/...) and AUTO for the rest.
 */
object ToolPolicies {

    fun defaultPolicy(tool: ToolDescriptor): ToolPolicy =
        if (tool.isDangerous) ToolPolicy.ASK else ToolPolicy.AUTO

    fun effective(plugin: PluginConfig, tool: ToolDescriptor): ToolPolicy {
        plugin.toolPolicies[tool.name]?.let { return it }
        val allowList = plugin.enabledToolNames
        if (allowList.isNotEmpty() && tool.name !in allowList) return ToolPolicy.DISABLED
        return defaultPolicy(tool)
    }

    fun isAvailable(plugin: PluginConfig, tool: ToolDescriptor): Boolean =
        effective(plugin, tool) != ToolPolicy.DISABLED

    /** Tools the model is allowed to see for this plugin. */
    fun available(plugin: PluginConfig, tools: List<ToolDescriptor>): List<ToolDescriptor> =
        tools.filter { isAvailable(plugin, it) }

    fun enabledCount(plugin: PluginConfig, tools: List<ToolDescriptor>): Int =
        tools.count { isAvailable(plugin, it) }

    /** Applies one explicit policy to one tool, keeping the allow-list in sync. */
    fun withPolicy(plugin: PluginConfig, tool: ToolDescriptor, policy: ToolPolicy): PluginConfig {
        val names = plugin.enabledToolNames.toMutableList()
        if (policy == ToolPolicy.DISABLED) {
            names.remove(tool.name)
        } else if (tool.name !in names) {
            names.add(tool.name)
        }
        return plugin.copy(
            toolPolicies = plugin.toolPolicies + (tool.name to policy),
            enabledToolNames = names
        )
    }

    /** Applies one explicit policy to many tools. */
    fun withPolicies(plugin: PluginConfig, tools: List<ToolDescriptor>, policy: ToolPolicy): PluginConfig {
        val names = plugin.enabledToolNames.toMutableList()
        tools.forEach { tool ->
            if (policy == ToolPolicy.DISABLED) {
                names.remove(tool.name)
            } else if (tool.name !in names) {
                names.add(tool.name)
            }
        }
        val overrides = plugin.toolPolicies.toMutableMap()
        tools.forEach { overrides[it.name] = policy }
        return plugin.copy(toolPolicies = overrides, enabledToolNames = names)
    }

    /**
     * Enables [tools] with their default policy (ASK for dangerous ones) — a
     * safer "enable all" than forcing AUTO.
     */
    fun enable(plugin: PluginConfig, tools: List<ToolDescriptor>, names: Set<String>): PluginConfig {
        val selected = tools.filter { it.name in names }
        if (selected.isEmpty()) return plugin
        val allowed = plugin.enabledToolNames.toMutableList()
        val overrides = plugin.toolPolicies.toMutableMap()
        selected.forEach { tool ->
            overrides[tool.name] = defaultPolicy(tool)
            if (tool.name !in allowed) allowed.add(tool.name)
        }
        return plugin.copy(toolPolicies = overrides, enabledToolNames = allowed)
    }

    /** Disables [names] (all discovered tools when [names] covers them). */
    fun disable(plugin: PluginConfig, names: Set<String>): PluginConfig {
        if (names.isEmpty()) return plugin
        val overrides = plugin.toolPolicies.toMutableMap()
        names.forEach { overrides[it] = ToolPolicy.DISABLED }
        return plugin.copy(
            toolPolicies = overrides,
            enabledToolNames = plugin.enabledToolNames.filterNot { it in names }
        )
    }

    /** Enables every discovered tool, keeping dangerous tools on ASK. */
    fun enableAll(plugin: PluginConfig, tools: List<ToolDescriptor>): PluginConfig =
        enable(plugin, tools, tools.map { it.name }.toSet())

    /** Disables every discovered tool. */
    fun disableAll(plugin: PluginConfig, tools: List<ToolDescriptor>): PluginConfig =
        disable(plugin, tools.map { it.name }.toSet())
}
