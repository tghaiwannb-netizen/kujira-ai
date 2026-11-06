package app.kujira.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.ui.components.KujiraButton
import app.kujira.android.ui.components.KujiraButtonStyle
import app.kujira.android.ui.components.KujiraConfirmDialog
import app.kujira.android.ui.components.KujiraIconButton
import app.kujira.android.ui.theme.KujiraTheme

/** Result of a "测试连接" attempt, rendered inline under the form. */
data class TestConnectionResult(
    val success: Boolean,
    val message: String,
)

/**
 * Provider editor: name, base URL, API key, model list and connection test.
 *
 * Fully controlled — the Lead's ViewModel owns every value. The API key is
 * displayed masked with a visibility toggle; it is never logged.
 */
@Composable
fun ProviderSettingsScreen(
    provider: ProviderConfig,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isNew: Boolean = false,
    name: String = provider.name,
    baseUrl: String = provider.baseUrl,
    apiKey: String = "",
    apiKeyConfigured: Boolean = false,
    modelsText: String = provider.models.joinToString("\n") {
        if (it.displayName == it.id) it.id else "${it.id}|${it.displayName}"
    },
    knownModels: List<ModelInfo> = provider.models,
    modelsUrl: String = provider.modelsUrl.orEmpty(),
    defaultModelId: String = provider.defaultModelId.orEmpty(),
    onNameChange: (String) -> Unit = {},
    onBaseUrlChange: (String) -> Unit = {},
    onApiKeyChange: (String) -> Unit = {},
    onModelsChange: (String) -> Unit = {},
    onModelsUrlChange: (String) -> Unit = {},
    onDefaultModelChange: (String) -> Unit = {},
    onEnabledChange: ((Boolean) -> Unit)? = null,
    onFetchModels: (() -> Unit)? = null,
    fetchingModels: Boolean = false,
    modelSyncResult: TestConnectionResult? = null,
    onTestConnection: (() -> Unit)? = null,
    testing: Boolean = false,
    testResult: TestConnectionResult? = null,
    onDelete: (() -> Unit)? = null,
    onSave: () -> Unit = {},
) {
    var apiKeyVisible by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val modelPreview = remember(modelsText, knownModels) {
        val byId = knownModels.associateBy { it.id }
        modelsText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> line.substringBefore('|').trim() }
            .filter { it.isNotEmpty() }
            .map { id -> byId[id] ?: ModelInfo(id) }
            .distinctBy { it.id }
            .toList()
    }

    SettingsPage(
        title = if (isNew) "添加 Provider" else provider.name,
        subtitle = providerKindLabel(provider.kind),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (provider.builtin && !isNew) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(
                    text = provider.description
                        ?: "内置 Provider：只需填写 API Key，Base URL 通常不用改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        SettingsSection(title = "基础信息") {
            LabeledField(
                label = "名称",
                value = name,
                onValueChange = onNameChange,
                placeholder = "例如 DeepSeek",
            )
            SettingsDivider()
            LabeledField(
                label = "Base URL",
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                placeholder = "https://api.example.com/v1",
                keyboardType = KeyboardType.Uri,
            )
            SettingsDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "API Key",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = if (apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        decorationBox = { innerTextField ->
                            Box {
                                if (apiKey.isEmpty()) {
                                    Text(
                                        text = if (apiKeyConfigured) {
                                            "已保存 Key（留空则沿用）"
                                        } else {
                                            "粘贴 API Key…"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    KujiraIconButton(
                        icon = if (apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (apiKeyVisible) "隐藏 API Key" else "显示 API Key",
                        onClick = { apiKeyVisible = !apiKeyVisible },
                        size = 36.dp,
                        iconSize = 18.dp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (apiKeyConfigured) {
                        "Key 已加密保存在本机；留空不会覆盖它。"
                    } else {
                        "Key 使用 Android Keystore 加密后保存在本机。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            if (onEnabledChange != null) {
                SettingsDivider()
                SettingsSwitchRow(
                    title = "启用此 Provider",
                    subtitle = "禁用后不会出现在模型选择器里",
                    checked = provider.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
            if (!provider.apiKeyUrl.isNullOrBlank()) {
                SettingsDivider()
                SettingsRow(
                    title = "获取 API Key",
                    subtitle = provider.apiKeyUrl,
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = { uriHandler.openUri(provider.apiKeyUrl) },
                )
            }
            if (!provider.docsUrl.isNullOrBlank()) {
                SettingsDivider()
                SettingsRow(
                    title = "官方文档",
                    subtitle = provider.docsUrl,
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = { uriHandler.openUri(provider.docsUrl) },
                )
            }
        }

        SettingsSection(title = "模型列表") {
            if (onFetchModels != null) {
                SettingsRow(
                    title = if (fetchingModels) "正在同步模型…" else "从服务端同步模型",
                    subtitle = "读取 /models，自动补全上下文与能力",
                    icon = Icons.Outlined.Sync,
                    onClick = if (fetchingModels) null else onFetchModels,
                )
                modelSyncResult?.let { result ->
                    SettingsDivider()
                    InlineResultRow(result)
                }
                SettingsDivider()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = "每行一个，格式 id|显示名；只写 id 时显示名同 id。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(12.dp),
                ) {
                    BasicTextField(
                        value = modelsText,
                        onValueChange = onModelsChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 3,
                        maxLines = 8,
                        decorationBox = { innerTextField ->
                            Box {
                                if (modelsText.isEmpty()) {
                                    Text(
                                        text = "deepseek-chat|DeepSeek V3.2\ndeepseek-reasoner|DeepSeek R1",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                if (modelPreview.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    val toolCount = modelPreview.count { it.supportsTools }
                    val visionCount = modelPreview.count { it.supportsVision }
                    val reasoningCount = modelPreview.count { it.supportsReasoning }
                    Text(
                        text = buildString {
                            append("共 ${modelPreview.size} 个模型")
                            if (toolCount > 0) append(" · 工具 $toolCount")
                            if (visionCount > 0) append(" · 视觉 $visionCount")
                            if (reasoningCount > 0) append(" · 思考 $reasoningCount")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "默认模型 id",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = defaultModelId,
                        onValueChange = onDefaultModelChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box {
                                if (defaultModelId.isEmpty()) {
                                    Text(
                                        text = "留空时使用列表第一项",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }
            if (onFetchModels != null) {
                SettingsDivider()
                LabeledField(
                    label = "模型列表 URL（可选）",
                    value = modelsUrl,
                    onValueChange = onModelsUrlChange,
                    placeholder = "留空时使用 Base URL/models",
                    keyboardType = KeyboardType.Uri,
                )
            }
        }

        SettingsSection(title = "连接") {
            SettingsRow(
                title = "测试连接",
                subtitle = if (testing) "正在请求 provider…" else "发送一次最小请求验证 Key 与 Base URL",
                icon = Icons.Outlined.Sync,
                onClick = onTestConnection,
            )
            testResult?.let { result ->
                SettingsDivider()
                InlineResultRow(result)
            }
        }

        KujiraButton(
            text = if (isNew) "保存并添加" else "保存",
            onClick = onSave,
            leadingIcon = Icons.Outlined.Save,
            modifier = Modifier.fillMaxWidth(),
        )

        if (onTestConnection != null) {
            Text(
                text = "测试连接会发送一次极小的请求，约消耗几十个 tokens。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
        }

        if (onDelete != null && !isNew) {
            KujiraButton(
                text = "删除此 Provider",
                onClick = { showDeleteConfirm = true },
                leadingIcon = Icons.Outlined.DeleteOutline,
                style = KujiraButtonStyle.OUTLINED,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDeleteConfirm && onDelete != null) {
        KujiraConfirmDialog(
            title = "删除 ${provider.name}？",
            message = "模型列表和本机保存的 API Key 都会被移除。不会影响服务端账户。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun InlineResultRow(result: TestConnectionResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (result.success) {
                Icons.Outlined.CheckCircleOutline
            } else {
                Icons.Outlined.ErrorOutline
            },
            contentDescription = null,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
            tint = if (result.success) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = result.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (result.success) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

internal fun providerKindLabel(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENAI -> "OpenAI 兼容"
    ProviderKind.ANTHROPIC -> "Anthropic"
    ProviderKind.DEEPSEEK -> "DeepSeek"
    ProviderKind.OPENROUTER -> "OpenRouter"
    ProviderKind.OPENCODE_ZEN -> "OpenCode 官方按量网关"
    ProviderKind.OPENCODE_GO -> "OpenCode Go 订阅网关"
    ProviderKind.CUSTOM -> "自定义"
}

private fun previewProvider() = previewProviders.first()

@Preview(name = "Provider edit dark", showBackground = true, widthDp = 400, heightDp = 980)
@Composable
private fun ProviderEditDarkPreview() {
    KujiraTheme(darkTheme = true) {
        ProviderSettingsScreen(
            provider = previewProvider(),
            onBack = {},
            apiKey = "sk-1234567890abcdef",
            onTestConnection = {},
            onSave = {},
            testResult = TestConnectionResult(success = true, message = "连接成功，2 个模型可用（deepseek-chat / deepseek-reasoner）"),
        )
    }
}

@Preview(name = "Provider edit light", showBackground = true, widthDp = 400, heightDp = 980)
@Composable
private fun ProviderEditLightPreview() {
    KujiraTheme(darkTheme = false) {
        ProviderSettingsScreen(
            provider = previewProvider().copy(id = "provider-custom", name = "本地 Ollama", kind = ProviderKind.CUSTOM, baseUrl = "http://192.168.1.10:11434/v1", models = emptyList(), builtin = false),
            onBack = {},
            isNew = true,
            onTestConnection = {},
            onSave = {},
        )
    }
}
