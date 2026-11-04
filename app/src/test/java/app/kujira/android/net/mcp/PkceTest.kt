package app.kujira.android.net.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {

    @Test
    fun `sha256 challenge matches the rfc 7636 vector`() {
        // RFC 7636 appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge(verifier))
    }

    @Test
    fun `verifier is base64url without padding`() {
        val verifier = Pkce.verifier()
        assertEquals(64, verifier.length) // 48 bytes -> 64 base64url chars
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `state is 16 random hex bytes`() {
        val state = Pkce.state()
        assertEquals(32, state.length)
        assertTrue(state.all { it in "0123456789abcdef" })
        assertTrue(state != Pkce.state())
    }

    @Test
    fun `authorization url carries pkce redirect scopes and extra params`() {
        val config = OAuthConfig(
            pluginId = "sentinelx",
            authorizationEndpoint = "https://keycloak.example.com/realms/mcp/protocol/openid-connect/auth",
            tokenEndpoint = "https://keycloak.example.com/realms/mcp/protocol/openid-connect/token",
            clientId = "client-1",
            redirectUri = "http://localhost:20573/oauth/callback",
            redirectPort = 20573,
            scopes = listOf("openid", "sentinelx:exec"),
            extraAuthParams = mapOf("prompt" to "consent", "resource" to "https://hub.example/mcp")
        )
        val url = Pkce.authorizationUrl(config, "challenge-1", "state-1")
        assertTrue(url.startsWith(config.authorizationEndpoint))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client-1"))
        assertTrue(url.contains("code_challenge=challenge-1"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A20573%2Foauth%2Fcallback"))
        assertTrue(url.contains("state=state-1"))
        assertTrue(url.contains("scope=openid%20sentinelx%3Aexec"))
        assertTrue(url.contains("prompt=consent"))
        assertTrue(url.contains("resource=https%3A%2F%2Fhub.example%2Fmcp"))
    }

    @Test
    fun `oauth config derives the secure store key from the plugin id`() {
        val config = OAuthConfig(
            pluginId = "sentinelx",
            authorizationEndpoint = "https://a",
            tokenEndpoint = "https://b",
            clientId = "c",
            redirectUri = "http://localhost:20573/oauth/callback",
            redirectPort = 20573
        )
        assertEquals("oauth:sentinelx", config.tokenKey)
        assertNotNull(McpSecrets.oauthKey("sentinelx"))
    }

    @Test
    fun `token expiring check uses a sixty second skew`() {
        val now = 1_000_000L
        assertTrue(OAuthToken("a", expiresAt = now + 30_000L).isExpiring(now))
        assertTrue(!OAuthToken("a", expiresAt = now + 120_000L).isExpiring(now))
        assertTrue(OAuthToken("a", expiresAt = 0L).isExpiring(now))
    }
}
