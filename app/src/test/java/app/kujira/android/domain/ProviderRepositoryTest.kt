package app.kujira.android.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.kujira.android.core.ProviderPresets
import app.kujira.android.core.SecureStore
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.net.llm.LlmStreamer
import app.kujira.android.net.llm.ModelCatalogClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProviderRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer
    private val pool = Executors.newCachedThreadPool()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        context.getSharedPreferences("kujira_settings_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            start()
        }
    }

    @After
    fun tearDown() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun repository(): ProviderRepository = ProviderRepository(
        settingsStore = SettingsStore(context),
        secureStore = SecureStore(context),
        streamer = LlmStreamer { emptyFlow() },
        modelCatalog = ModelCatalogClient(httpClient),
    )

    @Test
    fun `explicitly added preset is enabled and persistent`() {
        val repo = repository()
        val added = repo.addPreset(ProviderPresets.google)

        assertTrue(added.enabled)
        assertEquals("google", added.id)
        assertTrue(repo.get("google")!!.enabled)
        assertTrue(repo.get("google")!!.models.isNotEmpty())
    }

    @Test
    fun `disabling the active provider moves selection`() {
        val repo = repository()
        repo.setActive("deepseek", "deepseek-chat")

        repo.setEnabled("deepseek", false)

        assertFalse(repo.get("deepseek")!!.enabled)
        assertNotEquals("deepseek", repo.settings.value.activeProviderId)
        val fallback = repo.activeProvider()
        assertTrue(fallback != null && fallback.enabled)
    }

    @Test
    fun `api key changes bump the revision`() {
        val repo = repository()
        val before = repo.keyRevision.value

        repo.saveApiKey("deepseek", "sk-test")
        assertTrue(repo.hasApiKey("deepseek"))
        assertTrue(repo.keyRevision.value > before)

        repo.saveApiKey("deepseek", "")
        assertFalse(repo.hasApiKey("deepseek"))
    }

    @Test
    fun `fetch models merges metadata without persisting`() = runBlocking {
        server.createContext("/v1/models") { exchange ->
            val body = """
                {"data":[
                  {"id":"old-model","limit":{"context":128000},"capabilities":{"tools":{"supported":true}}},
                  {"id":"new-model","name":"New Model","limit":{"context":256000}}
                ]}
            """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val repo = repository()
        val provider = ProviderConfig(
            id = "catalog-test",
            kind = ProviderKind.CUSTOM,
            name = "Catalog Test",
            baseUrl = "http://127.0.0.1:${server.address.port}",
            models = listOf(ModelInfo("old-model", "我的旧模型")),
            modelsUrl = "http://127.0.0.1:${server.address.port}/v1/models",
            builtin = false
        )

        val result = repo.fetchModels(provider)

        assertTrue(result.ok)
        assertEquals(2, result.total)
        assertEquals(1, result.added)
        val old = result.models.first { it.id == "old-model" }
        assertEquals("我的旧模型", old.displayName)
        assertEquals(128_000, old.contextWindow)
        assertTrue(old.supportsTools)
        assertNull(repo.get("catalog-test"))
    }
}
