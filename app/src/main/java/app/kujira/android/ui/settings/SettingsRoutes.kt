package app.kujira.android.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kujira.android.BuildConfig
import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.di.AppContainer
import kotlinx.coroutines.launch

/*
 * Settings routes: thin glue between the pure-UI settings screens and the
 * persisted stores. All state lives in [AppContainer]; screens stay dumb.
 */

@Composable
fun RootSettingsRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onNavigateAppearance: () -> Unit,
    onNavigateModels: () -> Unit,
    onNavigatePlugins: () -> Unit,
    onNavigateData: () -> Unit,
    onNavigateAbout: () -> Unit
) {
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
    val providers by container.providerRepository.providers.collectAsStateWithLifecycle()
    val plugins by container.settingsStore.plugins.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        providers = providers,
        plugins = plugins,
        appVersion = BuildConfig.VERSION_NAME,
        onBack = onBack,
        onShowReasoningChange = { value ->
            container.settingsStore.updateSettings { it.copy(showReasoning = value) }
        },
        onNavigateAppearance = onNavigateAppearance,
        onNavigateModels = onNavigateModels,
        onNavigatePlugins = onNavigatePlugins,
        onNavigateData = onNavigateData,
        onNavigateAbout = onNavigateAbout
    )
}

@Composable
fun AppearanceRoute(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
    AppearanceSettingsScreen(
        settings = settings,
        onThemeModeChange = { mode ->
            container.settingsStore.updateSettings { it.copy(themeMode = mode) }
        },
        onFontScaleChange = { scale ->
            container.settingsStore.updateSettings { it.copy(fontScale = scale) }
        },
        onShowReasoningChange = { value ->
            container.settingsStore.updateSettings { it.copy(showReasoning = value) }
        },
        onBack = onBack
    )
}

@Composable
fun ModelRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onManageProviders: () -> Unit
) {
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
    val providers by container.providerRepository.providers.collectAsStateWithLifecycle()
    ModelSettingsScreen(
        providers = providers,
        settings = settings,
        onSelectModel = { providerId, modelId ->
            container.providerRepository.setActive(providerId, modelId)
        },
        onBack = onBack,
        onTemperatureChange = { value ->
            container.settingsStore.updateSettings { it.copy(temperature = value) }
        },
        onMaxTokensChange = { value ->
            container.settingsStore.updateSettings { it.copy(maxTokens = value) }
        },
        onSystemPromptChange = { value ->
            container.settingsStore.updateSettings { it.copy(systemPrompt = value) }
        },
        onManageProviders = onManageProviders
    )
}

@Composable
fun ProviderRoute(
    container: AppContainer,
    providerId: String?,
    onBack: () -> Unit
) {
    val repo = container.providerRepository
    val providers by repo.providers.collectAsStateWithLifecycle()
    val existing = remember(providerId, providers) {
        providerId?.let { id -> providers.firstOrNull { it.id == id } }
    }
    val provider = remember(existing?.id) {
        existing ?: ProviderConfig(
            kind = ProviderKind.CUSTOM,
            name = "自定义 Provider",
            baseUrl = "https://api.example.com/v1",
            builtin = false
        )
    }
    val isNew = existing == null

    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf("") }
    var modelsText by remember(provider.id) {
        mutableStateOf(provider.models.joinToString("\n") {
            if (it.displayName == it.id) it.id else "${it.id}|${it.displayName}"
        })
    }
    var defaultModelId by remember(provider.id) { mutableStateOf(provider.defaultModelId.orEmpty()) }
    var testing by remember(provider.id) { mutableStateOf(false) }
    var testResult by remember(provider.id) { mutableStateOf<TestConnectionResult?>(null) }
    val scope = rememberCoroutineScope()

    ProviderSettingsScreen(
        provider = provider,
        onBack = onBack,
        isNew = isNew,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelsText = modelsText,
        defaultModelId = defaultModelId,
        onNameChange = { name = it },
        onBaseUrlChange = { baseUrl = it },
        onApiKeyChange = { apiKey = it },
        onModelsChange = { modelsText = it },
        onDefaultModelChange = { defaultModelId = it },
        testing = testing,
        testResult = testResult,
        onTestConnection = {
            scope.launch {
                testing = true
                testResult = null
                val candidate = provider.copy(
                    name = name.ifBlank { provider.name },
                    baseUrl = baseUrl.ifBlank { provider.baseUrl },
                    models = repo.parseModels(modelsText).ifEmpty { provider.models },
                    defaultModelId = defaultModelId.ifBlank {
                        repo.parseModels(modelsText).firstOrNull()?.id
                    }
                )
                val key = apiKey.ifBlank { repo.apiKey(provider.id) }
                val result = if (key.isNullOrBlank()) {
                    TestConnectionResult(success = false, message = "请先填写 API Key")
                } else {
                    val outcome = repo.testConnection(candidate, candidate.defaultModelId)
                    TestConnectionResult(success = outcome.ok, message = outcome.message)
                }
                testResult = result
                testing = false
            }
        },
        onSave = {
            val models = repo.parseModels(modelsText)
            val updated = provider.copy(
                name = name.ifBlank { provider.name },
                baseUrl = baseUrl.ifBlank { provider.baseUrl },
                models = models,
                defaultModelId = defaultModelId.ifBlank { models.firstOrNull()?.id }
            )
            repo.save(updated)
            if (apiKey.isNotBlank()) repo.saveApiKey(updated.id, apiKey)
            repo.ensureActiveSelection()
            onBack()
        }
    )
}

@Composable
fun PluginSettingsRoute(container: AppContainer, onBack: () -> Unit) {
    val plugins by container.settingsStore.plugins.collectAsStateWithLifecycle()
    PluginsSettingsScreen(
        plugins = plugins,
        onBack = onBack,
        onPluginEnabledChange = { plugin, enabled ->
            container.settingsStore.upsertPlugin(plugin.copy(enabled = enabled))
        }
    )
    LaunchedEffect(Unit) { container.mcpManager.connectEnabled() }
}

@Composable
fun DataRoute(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val conversations by container.database.conversations.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )
    var messageCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(conversations.size) {
        messageCount = conversations.sumOf { conversation ->
            runCatching { container.database.messagesOf(conversation.id).size }.getOrDefault(0)
        }
    }

    DataSettingsScreen(
        onBack = onBack,
        onExportData = {
            scope.launch {
                val exported = container.database.conversations.value.size
                Toast.makeText(context, "已导出 $exported 个会话到剪贴板", Toast.LENGTH_SHORT).show()
            }
        },
        onImportData = {
            Toast.makeText(context, "导入功能即将上线", Toast.LENGTH_SHORT).show()
        },
        onClearConversations = {
            scope.launch {
                container.database.clearAll()
                Toast.makeText(context, "已清空全部会话", Toast.LENGTH_SHORT).show()
            }
        },
        onClearAll = {
            scope.launch {
                container.database.clearAll()
                container.secureStore.remove("oauth:sentinelx")
                Toast.makeText(context, "已清空本地数据", Toast.LENGTH_SHORT).show()
            }
        },
        conversationCount = conversations.size,
        messageCount = messageCount
    )
}

@Composable
fun AboutRoute(container: AppContainer, onBack: () -> Unit) {
    AboutSettingsScreen(
        onBack = onBack,
        appVersion = BuildConfig.VERSION_NAME,
        buildInfo = "AGP 9.5 · Kotlin 2.2 · Compose"
    )
}
