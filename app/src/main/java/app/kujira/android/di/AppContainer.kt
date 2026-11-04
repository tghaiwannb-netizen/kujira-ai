package app.kujira.android.di

import android.content.Context
import app.kujira.android.core.SecureStore
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.db.AppDatabase
import app.kujira.android.domain.ChatEngine
import app.kujira.android.domain.ConfirmingToolExecutor
import app.kujira.android.domain.McpToolExecutor
import app.kujira.android.domain.ProviderRepository
import app.kujira.android.net.llm.LlmRouter
import app.kujira.android.net.mcp.McpManager
import app.kujira.android.net.sentinelx.SentinelXService
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container.
 *
 * Everything in the app hangs off this; it is created once in
 * [app.kujira.android.KujiraApplication] and handed to view models.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val database: AppDatabase = AppDatabase(appContext)
    val secureStore: SecureStore = SecureStore(appContext)
    val settingsStore: SettingsStore = SettingsStore(appContext)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Fast client for small JSON-RPC control calls and OAuth token exchange. */
    val controlClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // -- LLM ----------------------------------------------------------------

    val llmRouter: LlmRouter = LlmRouter(httpClient)

    val providerRepository: ProviderRepository = ProviderRepository(
        settingsStore = settingsStore,
        secureStore = secureStore,
        streamer = llmRouter
    )

    // -- MCP plugins --------------------------------------------------------

    val mcpManager: McpManager = McpManager(
        settings = settingsStore,
        secureStore = secureStore,
        okHttpClient = controlClient
    )

    val toolExecutor: McpToolExecutor = McpToolExecutor(
        manager = mcpManager,
        settings = settingsStore,
        database = database
    )

    /** Applies per-tool AUTO/ASK/DISABLED policy in front of the MCP executor. */
    val gatedToolExecutor: ConfirmingToolExecutor = ConfirmingToolExecutor(
        delegate = toolExecutor,
        policyOf = { tool -> toolExecutor.policyFor(tool) }
    )

    val sentinelXService: SentinelXService = SentinelXService(
        manager = mcpManager,
        settings = settingsStore
    )

    // -- chat ---------------------------------------------------------------

    val chatEngine: ChatEngine = ChatEngine(
        streamer = llmRouter,
        toolExecutorProvider = {
            if (settingsStore.settings.value.pluginsEnabled) gatedToolExecutor else null
        }
    )

    init {
        providerRepository.ensureActiveSelection()
    }
}
