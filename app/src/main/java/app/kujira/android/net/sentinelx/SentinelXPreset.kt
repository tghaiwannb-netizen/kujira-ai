package app.kujira.android.net.sentinelx

import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.net.mcp.McpJson
import app.kujira.android.net.mcp.OAuthConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Everything that is specific to the SentinelX preset.
 *
 * The OAuth constants are copied verbatim from the verified desktop bridge
 * (`dsh-plugins/sentinelx-mcp/bridge.mjs`); a single character off and
 * Keycloak refuses the redirect or rejects the token exchange. Keep this file
 * as the only source of truth for them.
 */
object SentinelXPreset {

    const val PRESET = "sentinelx"
    const val PLUGIN_ID = "sentinelx"
    const val DISPLAY_NAME = "SentinelX"
    const val EMOJI = "🛰️"

    // -- hub -------------------------------------------------------------

    /** Must be `/mcp/mcp`; `/mcp` answers 307 to an http 401 page. */
    const val HUB_URL = "https://mcp.sentinelx.app/mcp/mcp"

    /** OAuth resource indicator for the hub (extra auth param `resource`). */
    const val RESOURCE = HUB_URL

    // -- Keycloak --------------------------------------------------------

    const val ISSUER = "https://keycloak.pensa.ar/realms/mcp"
    const val AUTHORIZATION_ENDPOINT = "$ISSUER/protocol/openid-connect/auth"
    const val TOKEN_ENDPOINT = "$ISSUER/protocol/openid-connect/token"
    const val CLIENT_ID = "416add6f-801f-4359-9bab-96b774e70ddf"

    const val REDIRECT_PORT = 20573
    const val CALLBACK_PATH = "/oauth/callback"
    const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT$CALLBACK_PATH"

    /** Scopes the tools actually need; the hub's discovery document only advertises openid. */
    val SCOPES: List<String> = listOf(
        "openid",
        "profile",
        "email",
        "offline_access",
        "sentinelx:exec",
        "sentinelx:state",
        "sentinelx:capabilities",
        "sentinelx:service",
        "sentinelx:restart",
        "sentinelx:edit",
        "sentinelx:script",
        "sentinelx:upload",
        "sentinelx:ping"
    )

    val SCOPE: String = SCOPES.joinToString(" ")

    val EXTRA_AUTH_PARAMS: Map<String, String> = mapOf(
        "prompt" to "consent",
        "resource" to RESOURCE
    )

    /** OAuth configuration for the built-in preset. */
    fun oauthConfig(pluginId: String = PLUGIN_ID): OAuthConfig = OAuthConfig(
        pluginId = pluginId,
        authorizationEndpoint = AUTHORIZATION_ENDPOINT,
        tokenEndpoint = TOKEN_ENDPOINT,
        clientId = CLIENT_ID,
        redirectUri = REDIRECT_URI,
        redirectPort = REDIRECT_PORT,
        callbackPath = CALLBACK_PATH,
        scopes = SCOPES,
        extraAuthParams = EXTRA_AUTH_PARAMS
    )

    fun isSentinelX(plugin: PluginConfig): Boolean = plugin.preset == PRESET

    // -- tools -----------------------------------------------------------

    /**
     * Safe subset enabled on first run. Exposing all 95 tools would burn the
     * context window, so everything else stays opt-in in the plugin detail page.
     * Must stay in sync with `SettingsStore.builtinPlugins()`.
     */
    val DEFAULT_ENABLED_TOOLS: List<String> = listOf(
        "sentinel_list_hosts",
        "sentinel_state",
        "sentinel_capabilities",
        "sentinel_ping",
        "sentinel_exec",
        "sentinel_read",
        "sentinel_list",
        "sentinel_service_status",
        "sentinel_git_diff",
        "sentinel_project_snapshot",
        "cloudflare_dns_list_zones",
        "cloudflare_dns_list_records"
    )

    /** Prefix-based tool grouping used by the detail page. Order is display order. */
    data class ToolGroup(val key: String, val title: String, val emoji: String, val prefix: String)

    val OTHER_GROUP = ToolGroup("other", "其他", "🧩", "")

    val TOOL_GROUPS: List<ToolGroup> = listOf(
        ToolGroup("sentinel", "服务器控制", "🖥️", "sentinel_"),
        ToolGroup("cloudflare", "Cloudflare", "🌐", "cloudflare_"),
        ToolGroup("telegram", "Telegram", "✈️", "telegram_"),
        ToolGroup("resend", "Resend 邮件", "✉️", "resend_"),
        ToolGroup("compute", "云主机", "☁️", "compute_"),
        ToolGroup("notifications", "通知与任务", "🔔", "notifications_")
    )

    fun groupFor(toolName: String): ToolGroup =
        TOOL_GROUPS.firstOrNull { toolName.startsWith(it.prefix) } ?: OTHER_GROUP

    data class GroupedTools(val group: ToolGroup, val tools: List<ToolDescriptor>)

    fun groupTools(tools: List<ToolDescriptor>): List<GroupedTools> {
        val byKey = tools.groupBy { groupFor(it.name).key }
        val ordered = TOOL_GROUPS + OTHER_GROUP
        return ordered.mapNotNull { group ->
            val items = byKey[group.key] ?: return@mapNotNull null
            if (items.isEmpty()) null else GroupedTools(group, items.sortedBy { it.name })
        }
    }

    // -- host parsing ----------------------------------------------------

    data class HostEntry(
        val id: String,
        val label: String? = null,
        val hostname: String? = null,
        val connected: Boolean = false,
        val operational: Boolean = true,
        val agentVersion: String? = null
    ) {
        val displayName: String
            get() = label?.takeIf { it.isNotBlank() }
                ?: hostname?.takeIf { it.isNotBlank() }
                ?: id

        val subtitle: String
            get() = when {
                !connected -> "离线"
                hostname != null && hostname != displayName -> hostname
                else -> id
            }
    }

    data class HostsSnapshot(
        val hosts: List<HostEntry> = emptyList(),
        val count: Int = 0,
        val planLabel: String? = null,
        val limit: Int? = null,
        val active: Int? = null,
        val parked: Int? = null,
        val available: Int? = null
    ) {
        val connectedHosts: List<HostEntry> get() = hosts.filter { it.connected }

        /** Human summary of the plan slots, e.g. "Free · 1/1 台已激活". */
        val planSummary: String?
            get() {
                val name = planLabel?.takeIf { it.isNotBlank() } ?: return null
                if (limit == null && active == null) return name
                return "$name · ${active ?: 0}/${limit ?: 0} 台已激活"
            }
    }

    data class CapabilitiesSummary(
        val agent: String? = null,
        val version: String? = null,
        val hostname: String? = null,
        val hostLabel: String? = null,
        val kernel: String? = null,
        val arch: String? = null,
        val opsSupported: List<String> = emptyList(),
        val execTimeoutDefault: Int? = null,
        val execTimeoutMax: Int? = null,
        val uploadBase: String? = null,
        val policySummary: Map<String, Int> = emptyMap(),
        val hubFeatures: Map<String, String> = emptyMap()
    ) {
        val hostDisplayName: String
            get() = hostLabel?.takeIf { it.isNotBlank() }
                ?: hostname?.takeIf { it.isNotBlank() }
                ?: "未知主机"

        val isAvailable: Boolean
            get() = hostname != null || version != null || opsSupported.isNotEmpty()
    }

    /**
     * Tool outputs of the hub are JSON both as `content[].text` and as
     * `structuredContent`; prefer the structured copy, fall back to text.
     */
    fun parseJsonPayload(output: String?, structuredJson: String? = null): JsonObject? {
        McpJson.parseObject(structuredJson)?.let { return it }
        val text = output?.trim().orEmpty()
        if (text.isEmpty()) return null
        McpJson.parseObject(text)?.let { return it }
        // Tolerate wrapped text around the JSON object.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start in 0 until end) return McpJson.parseObject(text.substring(start, end + 1))
        return null
    }

    /** Parses `sentinel_list_hosts` output. */
    fun parseHostsSnapshot(output: String?, structuredJson: String? = null): HostsSnapshot {
        val payload = parseJsonPayload(output, structuredJson) ?: return HostsSnapshot()
        val hostArray = McpJson.array(payload, "hosts") ?: JsonArray(emptyList())
        val hosts = hostArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = McpJson.string(obj, "host_id") ?: McpJson.string(obj, "id") ?: return@mapNotNull null
            val sessionId = McpJson.string(obj, "session_id")
            HostEntry(
                id = id,
                label = McpJson.string(obj, "label")?.takeIf { it.isNotBlank() },
                hostname = McpJson.string(obj, "hostname"),
                connected = !sessionId.isNullOrBlank(),
                operational = McpJson.boolean(obj, "operational") ?: true,
                agentVersion = McpJson.string(obj, "agent_version")
            )
        }
        val slots = McpJson.obj(payload, "slots")
        return HostsSnapshot(
            hosts = hosts,
            count = McpJson.int(payload, "count") ?: hosts.size,
            planLabel = McpJson.string(slots, "plan_label") ?: McpJson.string(slots, "plan"),
            limit = McpJson.int(slots, "limit"),
            active = McpJson.int(slots, "active"),
            parked = McpJson.int(slots, "parked"),
            available = McpJson.int(slots, "available")
        )
    }

    fun parseHosts(output: String?, structuredJson: String? = null): List<HostEntry> =
        parseHostsSnapshot(output, structuredJson).hosts

    /** Parses `sentinel_capabilities detail=summary` output. */
    fun parseCapabilities(output: String?, structuredJson: String? = null): CapabilitiesSummary? {
        val payload = parseJsonPayload(output, structuredJson) ?: return null
        val host = McpJson.obj(payload, "host")
        val limits = McpJson.obj(payload, "limits")
        val ops = McpJson.array(payload, "ops_supported")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val summary = CapabilitiesSummary(
            agent = McpJson.string(payload, "agent"),
            version = McpJson.string(payload, "version"),
            hostname = McpJson.string(host, "hostname"),
            hostLabel = McpJson.string(host, "label"),
            kernel = McpJson.string(host, "kernel"),
            arch = McpJson.string(host, "arch"),
            opsSupported = ops,
            execTimeoutDefault = McpJson.int(limits, "exec_timeout_default"),
            execTimeoutMax = McpJson.int(limits, "exec_timeout_max"),
            uploadBase = McpJson.string(payload, "upload_base"),
            policySummary = readIntMap(McpJson.obj(payload, "policy_summary")),
            hubFeatures = readStringMap(McpJson.obj(payload, "hub_features"))
        )
        return summary.takeIf { it.isAvailable }
    }

    /** Chinese labels for the `ops_supported` list shown on the overview page. */
    fun opLabel(op: String): String = OP_LABELS[op] ?: op

    private val OP_LABELS: Map<String, String> = mapOf(
        "capabilities" to "能力查询",
        "state" to "主机状态",
        "ping" to "连通检测",
        "exec" to "执行命令",
        "script_run" to "运行脚本",
        "read" to "读文件",
        "read_audit" to "审计日志",
        "read_media" to "读媒体文件",
        "list" to "列目录",
        "search" to "内容搜索",
        "edit" to "改文件",
        "edit_secret" to "写密钥",
        "edit_upload_init" to "编辑上传",
        "edit_upload_file" to "编辑上传",
        "edit_upload_complete" to "编辑上传",
        "upload_init" to "上传文件",
        "upload_file" to "上传文件",
        "upload_chunk" to "上传文件",
        "upload_complete" to "上传文件",
        "delete" to "删除",
        "move" to "移动",
        "copy" to "复制",
        "chmod" to "改权限",
        "chown" to "改属主",
        "service" to "服务管理",
        "restart" to "重启服务",
        "git" to "Git",
        "project_snapshot" to "项目快照",
        "file_export_init" to "导出文件",
        "file_export_chunk" to "导出文件",
        "file_export_complete" to "导出文件",
        "help" to "帮助",
        "local_api" to "本地 API",
        "opaque_ref" to "关联引用"
    )

    private fun readIntMap(obj: JsonObject?): Map<String, Int> =
        obj?.entries?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.intOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()

    private fun readStringMap(obj: JsonObject?): Map<String, String> =
        obj?.entries?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { key to it }
        }?.toMap() ?: emptyMap()

    // -- quick prompts ---------------------------------------------------

    data class QuickPrompt(
        val emoji: String,
        val title: String,
        val prompt: String
    )

    /** One-tap prompts shown on the SentinelX page; the text goes into the chat input. */
    val QUICK_PROMPTS: List<QuickPrompt> = listOf(
        QuickPrompt("📊", "查看服务器状态", "用 sentinel_state 查看当前 SentinelX 主机的状态，并总结 CPU、内存与负载。"),
        QuickPrompt("💾", "磁盘占用", "用 sentinel_exec 执行 `df -h`，告诉我磁盘占用情况，指出快满的分区。"),
        QuickPrompt("🔁", "重启 nginx", "重启服务器上的 nginx 服务，并告诉我结果。"),
        QuickPrompt("📁", "列目录", "列出 /opt 目录下的文件和子目录。"),
        QuickPrompt("📜", "拉日志", "读取 /var/log/nginx/error.log 的最后 50 行，帮我找出最近的错误。"),
        QuickPrompt("🌐", "Cloudflare 记录", "列出我的 Cloudflare zone，以及每个 zone 里的 DNS 记录。")
    )

    // -- error translation -----------------------------------------------

    /** Turns hub errors into a plain-Chinese explanation, or null when unknown. */
    fun explainError(message: String?): String? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = text.lowercase()
        return when {
            lower.contains("401") ||
                lower.contains("unauthor") ||
                lower.contains("invalid bearer") ||
                lower.contains("授权已失效") ||
                lower.contains("授权已过期") ||
                lower.contains("尚未登录") ||
                (lower.contains("token") && (lower.contains("expire") || lower.contains("invalid"))) ->
                "SentinelX 授权已失效：点「重新授权」用浏览器登录一次，回来就能继续用。"

            lower.contains("upgrade_required") || lower.contains("upgrade required") ->
                "当前套餐的主机槽位已满：免费版只能同时激活 1 台主机。到 SentinelX 控制台停掉一台，或升级套餐。"

            lower.contains("path_not_allowed") || lower.contains("path not allowed") ->
                "目标路径不在主机的 file_ops 白名单里：编辑服务器上的 /etc/sentinelx/config.yaml 放行该路径，然后重启 SentinelX agent。"

            lower.contains("ambiguous_host") ->
                "连接了多台主机：先在本页顶部的「当前主机」里选一台，再让模型调用工具。"

            lower.contains("host_not_connected") ||
                lower.contains("host not connected") ||
                lower.contains("offline") ||
                lower.contains("not connected") ->
                "选中的主机当前离线：确认服务器上的 SentinelX agent 在运行，然后点刷新。"

            lower.contains("not_allowed") || lower.contains("not allowed") ||
                lower.contains("allowlist") || lower.contains("exec_mode") ->
                "这条命令/操作不在主机的白名单里：到 /etc/sentinelx/config.yaml 的 allowed_commands 里添加后重启 agent。"

            lower.contains("permission_denied") || lower.contains("permission denied") ->
                "主机拒绝了这次操作：运行 SentinelX agent 的系统用户没有对应文件的权限。"

            lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时") ->
                "远程主机响应超时：命令可能还在跑，稍后再试；耗时任务可以在参数里调大 timeout。"

            lower.contains("无法连接") || lower.contains("unable to resolve") ||
                lower.contains("connect") || lower.contains("network") || lower.contains("网络") ->
                "网络连接失败：检查手机网络是否能访问 mcp.sentinelx.app。"

            else -> null
        }
    }

    /** True when [message] describes an authentication problem. */
    fun isAuthError(message: String?): Boolean {
        val lower = message?.lowercase() ?: return false
        return lower.contains("401") ||
            lower.contains("unauthor") ||
            lower.contains("invalid bearer") ||
            lower.contains("授权已失效") ||
            lower.contains("授权已过期") ||
            lower.contains("尚未登录") ||
            (lower.contains("token") && (lower.contains("expire") || lower.contains("invalid")))
    }
}
