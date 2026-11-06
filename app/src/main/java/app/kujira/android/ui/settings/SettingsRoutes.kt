package app.kujira.android.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.ToolAudit
import app.kujira.android.di.AppContainer
import app.kujira.android.domain.ModelSyncResult
import app.kujira.android.domain.UsageAggregator
import app.kujira.android.domain.UsageReport
import app.kujira.android.domain.toModelPrice
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
    onNavigateUsage: () -> Unit,
    onNavigatePlugins: () -> Unit,
    onNavigateAudit: () -> Unit,
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
        onNavigateUsage = onNavigateUsage,
        onNavigatePlugins = onNavigatePlugins,
        onNavigateAudit = onNavigateAudit,
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
    val keyRevision by repo.keyRevision.collectAsStateWithLifecycle()
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
    val keyConfigured = remember(provider.id, keyRevision) { repo.hasApiKey(provider.id) }

    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var modelsUrl by remember(provider.id) { mutableStateOf(provider.modelsUrl.orEmpty()) }
    var enabled by remember(provider.id) { mutableStateOf(provider.enabled) }
    var apiKey by remember(provider.id) { mutableStateOf("") }
    var modelsText by remember(provider.id) {
        mutableStateOf(provider.models.joinToString("\n") {
            if (it.displayName == it.id) it.id else "${it.id}|${it.displayName}"
        })
    }
    var knownModels by remember(provider.id) { mutableStateOf(provider.models) }
    var defaultModelId by remember(provider.id) { mutableStateOf(provider.defaultModelId.orEmpty()) }
    var testing by remember(provider.id) { mutableStateOf(false) }
    var testResult by remember(provider.id) { mutableStateOf<TestConnectionResult?>(null) }
    var syncingModels by remember(provider.id) { mutableStateOf(false) }
    var syncResult by remember(provider.id) { mutableStateOf<TestConnectionResult?>(null) }
    val scope = rememberCoroutineScope()

    fun modelsFromEditor(): List<ModelInfo> =
        repo.parseModelsWithMetadata(modelsText, (knownModels + provider.models).distinctBy { it.id })

    fun candidateProvider(): ProviderConfig {
        val models = modelsFromEditor()
        return provider.copy(
            name = name.ifBlank { provider.name },
            baseUrl = baseUrl.ifBlank { provider.baseUrl },
            models = models,
            modelsUrl = modelsUrl.trim().takeIf { it.isNotEmpty() },
            enabled = enabled,
            defaultModelId = defaultModelId.trim().takeIf { id -> models.any { it.id == id } }
                ?: models.firstOrNull()?.id
        )
    }

    ProviderSettingsScreen(
        provider = provider,
        onBack = onBack,
        isNew = isNew,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        apiKeyConfigured = keyConfigured,
        modelsText = modelsText,
        knownModels = knownModels,
        modelsUrl = modelsUrl,
        defaultModelId = defaultModelId,
        onNameChange = { name = it },
        onBaseUrlChange = { baseUrl = it },
        onApiKeyChange = { apiKey = it },
        onModelsChange = { modelsText = it },
        onModelsUrlChange = { modelsUrl = it },
        onDefaultModelChange = { defaultModelId = it },
        onEnabledChange = { value ->
            enabled = value
            if (!isNew) repo.setEnabled(provider.id, value)
        },
        onFetchModels = {
            scope.launch {
                syncingModels = true
                syncResult = null
                val candidate = candidateProvider()
                val key = apiKey.ifBlank { repo.apiKey(provider.id) }
                val outcome = if (candidate.builtin && key.isNullOrBlank()) {
                    ModelSyncResult(false, "请先填写 API Key，再从服务端同步模型。")
                } else {
                    repo.fetchModels(candidate, key)
                }
                if (outcome.ok) {
                    knownModels = outcome.models
                    modelsText = outcome.models.joinToString("\n") {
                        if (it.displayName == it.id) it.id else "${it.id}|${it.displayName}"
                    }
                    if (defaultModelId.isBlank() || outcome.models.none { it.id == defaultModelId }) {
                        defaultModelId = outcome.models.firstOrNull()?.id.orEmpty()
                    }
                }
                syncResult = TestConnectionResult(outcome.ok, outcome.message)
                syncingModels = false
            }
        },
        fetchingModels = syncingModels,
        modelSyncResult = syncResult,
        testing = testing,
        testResult = testResult,
        onTestConnection = {
            scope.launch {
                testing = true
                testResult = null
                val candidate = candidateProvider()
                val key = apiKey.ifBlank { repo.apiKey(provider.id) }
                val result = if (candidate.builtin && key.isNullOrBlank()) {
                    TestConnectionResult(false, "请先填写 API Key")
                } else {
                    val outcome = repo.testConnection(candidate, candidate.defaultModelId)
                    TestConnectionResult(outcome.ok, outcome.message)
                }
                testResult = result
                testing = false
            }
        },
        onDelete = if (isNew) null else {
            {
                repo.delete(provider.id)
                onBack()
            }
        },
        onSave = {
            val models = modelsFromEditor()
            val updated = candidateProvider().copy(
                models = models,
                defaultModelId = defaultModelId.trim().takeIf { id -> models.any { it.id == id } }
                    ?: models.firstOrNull()?.id
            )
            repo.save(updated)
            if (apiKey.isNotBlank()) repo.saveApiKey(updated.id, apiKey)
            repo.ensureActiveSelection()
            onBack()
        }
    )
}

@Composable
fun ProviderListRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenProvider: (String) -> Unit,
    onAddCustomProvider: () -> Unit,
) {
    val repo = container.providerRepository
    val providers by repo.providers.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val keyRevision by repo.keyRevision.collectAsStateWithLifecycle()
    var showPresetSheet by remember { mutableStateOf(false) }
    val configuredKeys = remember(providers, keyRevision) {
        providers.filter { repo.hasApiKey(it.id) }.mapTo(HashSet()) { it.id }
    }

    ProviderListScreen(
        providers = providers,
        activeProviderId = settings.activeProviderId,
        activeModelId = settings.activeModelId,
        configuredKeys = configuredKeys,
        onBack = onBack,
        onAddProvider = { showPresetSheet = true },
        onOpenProvider = onOpenProvider,
        onToggleEnabled = { id, enabled -> repo.setEnabled(id, enabled) },
    )

    if (showPresetSheet) {
        ProviderPresetSheet(
            existingIds = providers.mapTo(HashSet()) { it.id },
            onDismiss = { showPresetSheet = false },
            onSelect = { preset ->
                showPresetSheet = false
                val provider = repo.addPreset(preset)
                onOpenProvider(provider.id)
            },
            onCustom = {
                showPresetSheet = false
                onAddCustomProvider()
            },
        )
    }
}

@Composable
fun UsageRoute(container: AppContainer, onBack: () -> Unit) {
    val providers by container.providerRepository.providers.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf(UsageReport()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(providers) {
        loading = true
        val prices = buildMap {
            for (provider in providers) {
                for (model in provider.models) {
                    val price = model.toModelPrice() ?: continue
                    put(UsageAggregator.priceKey(provider.id, model.id), price)
                    putIfAbsent(UsageAggregator.priceKey(null, model.id), price)
                }
            }
        }
        report = UsageAggregator.aggregate(container.database.usageRows(), prices)
        loading = false
    }

    val providerNames = remember(providers) { providers.associate { it.id to it.name } }
    val modelNames = remember(providers) {
        buildMap {
            for (provider in providers) {
                for (model in provider.models) {
                    put(UsageAggregator.priceKey(provider.id, model.id), model.displayName)
                }
            }
        }
    }

    UsageSettingsScreen(
        report = report,
        loading = loading,
        onBack = onBack,
        providerName = { id -> id?.let { providerNames[it] } ?: "未知 Provider" },
        modelName = { providerId, modelId ->
            modelId?.let {
                modelNames[UsageAggregator.priceKey(providerId, it)]
                    ?: providers.firstOrNull { p -> p.id == providerId }
                        ?.models?.firstOrNull { m -> m.id == it }?.displayName
                    ?: it
            } ?: "未知模型"
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
fun AuditRoute(container: AppContainer, onBack: () -> Unit) {
    var audits by remember { mutableStateOf(emptyList<ToolAudit>()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        loading = true
        audits = container.database.audits(limit = 500)
        loading = false
    }

    AuditLogScreen(
        audits = audits,
        loading = loading,
        onBack = onBack,
        onClear = {
            scope.launch {
                container.database.clearAudits()
                refreshKey += 1
            }
        }
    )
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

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                Toast.makeText(context, "读取文件失败", Toast.LENGTH_LONG).show()
                return@launch
            }
            runCatching { container.exportService.importJson(text) }
                .onSuccess { result ->
                    Toast.makeText(
                        context,
                        "已导入 ${result.conversations} 个会话 / ${result.messages} 条消息",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { failure ->
                    Toast.makeText(context, "导入失败：${failure.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    DataSettingsScreen(
        onBack = onBack,
        onExportData = {
            scope.launch {
                runCatching {
                    val payload = container.exportService.exportJson()
                    val dir = java.io.File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
                    val file = java.io.File(dir, "kujira-export-${System.currentTimeMillis()}.json")
                    file.writeText(payload)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Kujira 数据导出")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "导出 Kujira 数据"))
                }.onFailure { failure ->
                    Toast.makeText(context, "导出失败：${failure.message}", Toast.LENGTH_LONG).show()
                }
            }
        },
        onImportData = {
            runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                .onFailure {
                    Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                }
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
