package app.kujira.android.net.sentinelx

import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.ToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelXPresetTest {

    /** Captured from the live hub (`sentinel_list_hosts`). */
    private val liveHostsOutput = """
        {"ok":true,"hosts":[{"host_id":"host_demo_0001","hostname":"demo-server","label":null,"session_id":"sess_demo_0001","connected_at":"2026-11-04T13:29:15.966485+00:00","agent_version":"0.23.34","capabilities":["exec","read"],"operational":true}],"count":1,"slots":{"plan":"free","plan_label":"Free","limit":1,"active":1,"parked":0,"available":0,"enforced":true}}
    """.trimIndent()

    /** Captured from the live hub (`sentinel_capabilities detail=summary`). */
    private val liveCapabilitiesOutput = """
        {"ok":true,"agent":"sentinelx-cloud-core","version":"0.23.34","host":{"hostname":"demo-server","label":null,"kernel":"6.1.0-42-amd64","arch":"x86_64"},"ops_supported":["capabilities","exec","read","service","state"],"limits":{"exec_timeout_default":60,"exec_timeout_max":600},"upload_base":"/var/lib/sentinelx/uploads","file_ops_limits":{"max_read_bytes":262144},"policy_summary":{"allowed_commands":95,"services":7,"locations":1,"playbooks":13,"file_ops_paths":1,"writable_paths":1,"trusted_fetch_hosts":4},"query_contract":{"help":["topic"]},"hub_features":{"async_jobs":"Long exec...","compute":"Cloud VMs..."}}
    """.trimIndent()

    private fun tool(name: String) = ToolDescriptor(
        pluginId = "sentinelx",
        pluginName = "SentinelX",
        name = name,
        description = "",
        inputSchemaJson = "{}"
    )

    @Test
    fun `oauth constants match the verified desktop bridge`() {
        assertEquals("https://keycloak.pensa.ar/realms/mcp", SentinelXPreset.ISSUER)
        assertEquals(
            "https://keycloak.pensa.ar/realms/mcp/protocol/openid-connect/auth",
            SentinelXPreset.AUTHORIZATION_ENDPOINT
        )
        assertEquals(
            "https://keycloak.pensa.ar/realms/mcp/protocol/openid-connect/token",
            SentinelXPreset.TOKEN_ENDPOINT
        )
        assertEquals("416add6f-801f-4359-9bab-96b774e70ddf", SentinelXPreset.CLIENT_ID)
        assertEquals("http://localhost:20573/oauth/callback", SentinelXPreset.REDIRECT_URI)
        assertEquals("https://mcp.sentinelx.app/mcp/mcp", SentinelXPreset.HUB_URL)
        assertEquals("consent", SentinelXPreset.EXTRA_AUTH_PARAMS["prompt"])
        assertEquals(SentinelXPreset.HUB_URL, SentinelXPreset.EXTRA_AUTH_PARAMS["resource"])
    }

    @Test
    fun `scope list matches the bridge`() {
        assertEquals(
            listOf(
                "openid", "profile", "email", "offline_access",
                "sentinelx:exec", "sentinelx:state", "sentinelx:capabilities",
                "sentinelx:service", "sentinelx:restart", "sentinelx:edit",
                "sentinelx:script", "sentinelx:upload", "sentinelx:ping"
            ),
            SentinelXPreset.SCOPES
        )
        assertEquals(SentinelXPreset.SCOPES.joinToString(" "), SentinelXPreset.SCOPE)
    }

    @Test
    fun `oauth config wires all keycloak values`() {
        val config = SentinelXPreset.oauthConfig()
        assertEquals(SentinelXPreset.CLIENT_ID, config.clientId)
        assertEquals(SentinelXPreset.REDIRECT_URI, config.redirectUri)
        assertEquals(20573, config.redirectPort)
        assertEquals("/oauth/callback", config.callbackPath)
        assertEquals("oauth:sentinelx", config.tokenKey)
        assertEquals(SentinelXPreset.SCOPES, config.scopes)
    }

    @Test
    fun `parses hosts from plain text output`() {
        val snapshot = SentinelXPreset.parseHostsSnapshot(liveHostsOutput)
        assertEquals(1, snapshot.hosts.size)
        val host = snapshot.hosts[0]
        assertEquals("host_demo_0001", host.id)
        assertEquals("demo-server", host.hostname)
        assertTrue(host.connected)
        assertTrue(host.operational)
        assertEquals("0.23.34", host.agentVersion)
        assertEquals("demo-server", host.displayName)
        assertEquals("Free · 1/1 台已激活", snapshot.planSummary)
    }

    @Test
    fun `parses hosts from structured content and handles surrounding text`() {
        val structured = SentinelXPreset.parseHostsSnapshot(null, liveHostsOutput)
        assertEquals(1, structured.hosts.size)

        val wrapped = SentinelXPreset.parseHostsSnapshot("结果如下： $liveHostsOutput 完毕")
        assertEquals(1, wrapped.hosts.size)
    }

    @Test
    fun `empty or broken host output yields an empty snapshot`() {
        assertTrue(SentinelXPreset.parseHostsSnapshot(null).hosts.isEmpty())
        assertTrue(SentinelXPreset.parseHostsSnapshot("not json").hosts.isEmpty())
        assertTrue(SentinelXPreset.parseHostsSnapshot("""{"hosts":[]}""").hosts.isEmpty())
    }

    @Test
    fun `parses capability summary`() {
        val caps = SentinelXPreset.parseCapabilities(liveCapabilitiesOutput)
        assertNotNull(caps)
        assertEquals("sentinelx-cloud-core", caps!!.agent)
        assertEquals("0.23.34", caps.version)
        assertEquals("demo-server", caps.hostDisplayName)
        assertEquals("x86_64", caps.arch)
        assertEquals(5, caps.opsSupported.size)
        assertEquals(60, caps.execTimeoutDefault)
        assertEquals(600, caps.execTimeoutMax)
        assertEquals(95, caps.policySummary["allowed_commands"])
        assertEquals(7, caps.policySummary["services"])
        assertTrue(caps.hubFeatures.containsKey("async_jobs"))
    }

    @Test
    fun `capability parse returns null for errors`() {
        assertNull(SentinelXPreset.parseCapabilities("""{"ok":false,"error":"unauthorized"}"""))
        assertNull(SentinelXPreset.parseCapabilities(null))
    }

    @Test
    fun `groups 95 tools by prefix`() {
        val names = listOf(
            "sentinel_exec", "sentinel_read", "cloudflare_dns_list_zones", "telegram_send_message",
            "resend_send_email", "compute_list", "notifications_check", "list_integrations"
        )
        val groups = SentinelXPreset.groupTools(names.map(::tool))
        assertEquals(
            listOf("sentinel", "cloudflare", "telegram", "resend", "compute", "notifications", "other"),
            groups.map { it.group.key }
        )
        assertEquals(2, groups.first { it.group.key == "sentinel" }.tools.size)
        assertEquals("list_integrations", groups.first { it.group.key == "other" }.tools.single().name)
    }

    @Test
    fun `group lookup falls back to other`() {
        assertEquals("sentinel", SentinelXPreset.groupFor("sentinel_ping").key)
        assertEquals("other", SentinelXPreset.groupFor("list_integrations").key)
        assertEquals("服务器控制", SentinelXPreset.groupFor("sentinel_ping").title)
    }

    @Test
    fun `default enabled tools are a safe subset`() {
        assertTrue(SentinelXPreset.DEFAULT_ENABLED_TOOLS.contains("sentinel_capabilities"))
        assertTrue(SentinelXPreset.DEFAULT_ENABLED_TOOLS.contains("sentinel_exec"))
        assertFalse(SentinelXPreset.DEFAULT_ENABLED_TOOLS.contains("sentinel_delete"))
        assertEquals(SentinelXPreset.DEFAULT_ENABLED_TOOLS.size, SentinelXPreset.DEFAULT_ENABLED_TOOLS.toSet().size)
    }

    @Test
    fun `quick prompts are six actionable cards`() {
        assertEquals(6, SentinelXPreset.QUICK_PROMPTS.size)
        assertTrue(SentinelXPreset.QUICK_PROMPTS.all { it.title.isNotBlank() && it.prompt.isNotBlank() })
    }

    @Test
    fun `explains common hub errors in chinese`() {
        assertNotNull(SentinelXPreset.explainError("hub returned 401: missing or malformed Authorization header"))
        assertNotNull(SentinelXPreset.explainError("upgrade_required: host limit reached"))
        assertNotNull(SentinelXPreset.explainError("path_not_allowed: /etc/nginx/nginx.conf"))
        assertNotNull(SentinelXPreset.explainError("ambiguous_host: multiple hosts connected"))
        assertNotNull(SentinelXPreset.explainError("MCP 请求超时（60s）：服务器没有及时响应"))
        assertNull(SentinelXPreset.explainError(null))
        assertNull(SentinelXPreset.explainError("totally unrelated text"))
    }

    @Test
    fun `auth errors are detected`() {
        assertTrue(SentinelXPreset.isAuthError("HTTP 401"))
        assertTrue(SentinelXPreset.isAuthError("授权已失效，请重新登录"))
        assertFalse(SentinelXPreset.isAuthError("path_not_allowed"))
        assertFalse(SentinelXPreset.isAuthError(null))
    }

    @Test
    fun `recognises the builtin plugin`() {
        assertTrue(
            SentinelXPreset.isSentinelX(
                PluginConfig(name = "SentinelX", url = SentinelXPreset.HUB_URL, preset = "sentinelx")
            )
        )
        assertFalse(SentinelXPreset.isSentinelX(PluginConfig(name = "x", url = "https://x.dev")))
    }

    @Test
    fun `ops get chinese labels with fallback`() {
        assertEquals("执行命令", SentinelXPreset.opLabel("exec"))
        assertEquals("unknown_op", SentinelXPreset.opLabel("unknown_op"))
    }
}
