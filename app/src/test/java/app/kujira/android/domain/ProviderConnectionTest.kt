package app.kujira.android.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.kujira.android.core.SecureStore
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.net.llm.LlmEvent
import app.kujira.android.net.llm.LlmStreamer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProviderConnectionTest {
    @Test
    fun connectionProbeUsesUnsavedEnteredApiKey() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val secure = SecureStore(ctx, SecretKeySpec(ByteArray(32) { 0x25 }, "AES"))
        var keyPassedToModel: String? = null
        val stream = LlmStreamer { req ->
            keyPassedToModel = req.apiKey
            flowOf(LlmEvent.Finished())
        }
        val repo = ProviderRepository(SettingsStore(ctx, secure), secure, stream)
        val provider = ProviderConfig(
            id = "unsaved-key-test",
            kind = ProviderKind.CUSTOM,
            name = "Test",
            baseUrl = "https://example.org/v1",
            models = listOf(ModelInfo("test-model"))
        )
        val result = repo.testConnection(provider, apiKeyOverride = "new-unsaved-key")
        assertTrue(result.message, result.ok)
        assertEquals("new-unsaved-key", keyPassedToModel)
    }
}
