package app.kujira.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.core.ProviderPreset
import app.kujira.android.core.ProviderPresets
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.ui.components.KujiraBottomSheet
import app.kujira.android.ui.components.KujiraButton
import app.kujira.android.ui.components.KujiraButtonStyle
import app.kujira.android.ui.components.KujiraIconButton
import app.kujira.android.ui.theme.KujiraTheme

/**
 * Provider manager. Modelled on RikkaHub's provider list and Chatbox's preset
 * sheet: every provider is visible in one place, with key status, model count,
 * an enable switch and a one-tap "add preset" entry point.
 */
@Composable
fun ProviderListScreen(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    activeModelId: String?,
    configuredKeys: Set<String>,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onOpenProvider: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabledCount = providers.count { it.enabled }
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    val activeModel = activeProvider?.let { provider ->
        provider.models.firstOrNull { it.id == activeModelId } ?: provider.defaultModel()
    }

    SettingsPage(
        title = "模型供应商",
        subtitle = "${providers.size} 个 Provider · $enabledCount 个已启用",
        onBack = onBack,
        modifier = modifier,
        actions = {
            KujiraIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "添加 Provider",
                onClick = onAddProvider,
            )
        },
    ) {
        if (activeProvider != null) {
            SettingsSection(title = "当前使用") {
                SettingsRow(
                    title = activeModel?.displayName ?: activeProvider.name,
                    subtitle = "${activeProvider.name} · ${activeProvider.baseUrl}",
                    icon = providerIcon(activeProvider.kind),
                    onClick = { onOpenProvider(activeProvider.id) },
                )
            }
        }

        SettingsSection(title = "Provider") {
            if (providers.isEmpty()) {
                SettingsRow(
                    title = "还没有 Provider",
                    subtitle = "点右上角 + 添加 OpenCode、OpenAI 或自定义端点",
                )
            } else {
                providers.forEachIndexed { index, provider ->
                    if (index > 0) SettingsDivider()
                    ProviderRow(
                        provider = provider,
                        active = provider.id == activeProviderId,
                        keyConfigured = provider.id in configuredKeys,
                        onClick = { onOpenProvider(provider.id) },
                        onToggleEnabled = { onToggleEnabled(provider.id, it) },
                    )
                }
            }
        }

        Text(
            text = "点击 Provider 编辑 Base URL、API Key 和模型；开关控制它是否出现在模型选择器。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderConfig,
    active: Boolean,
    keyConfigured: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val meta = buildList {
        if (active) add("当前")
        add("${provider.models.size} 个模型")
        if (provider.builtin) add("内置")
    }.joinToString(" · ")
    val keyWarning = if (keyConfigured) null else "未配置 Key"

    SettingsRow(
        title = provider.name,
        subtitle = listOfNotNull(meta, keyWarning).joinToString("\n"),
        icon = providerIcon(provider.kind),
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        },
    )
}

/**
 * Bottom sheet listing catalogue presets. Existing providers remain visible as
 * "打开" so the sheet doubles as a shortcut to the most-used gateways.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPresetSheet(
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (ProviderPreset) -> Unit,
    onCustom: () -> Unit,
) {
    KujiraBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "添加 Provider",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "预填 Base URL、文档和模型列表，添加后只需填 Key。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ProviderPresets.picker.forEachIndexed { index, preset ->
                    if (index > 0) SettingsDivider()
                    val exists = preset.id in existingIds
                    SettingsRow(
                        title = preset.name,
                        subtitle = preset.description,
                        icon = providerIcon(preset.kind),
                        onClick = { onSelect(preset) },
                        trailing = {
                            Text(
                                text = if (exists) "打开" else "添加",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (exists) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            KujiraButton(
                text = "自定义 OpenAI 兼容端点",
                onClick = onCustom,
                style = KujiraButtonStyle.OUTLINED,
                leadingIcon = Icons.Outlined.Tune,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun providerIcon(kind: ProviderKind): ImageVector = when (kind) {
    ProviderKind.OPENAI -> Icons.Outlined.Bolt
    ProviderKind.ANTHROPIC -> Icons.Outlined.Lightbulb
    ProviderKind.DEEPSEEK -> Icons.Outlined.Memory
    ProviderKind.OPENROUTER -> Icons.Outlined.Router
    ProviderKind.OPENCODE_ZEN, ProviderKind.OPENCODE_GO -> Icons.Outlined.Code
    ProviderKind.CUSTOM -> Icons.Outlined.Tune
}

@Preview(name = "Providers dark", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun ProviderListDarkPreview() {
    KujiraTheme(darkTheme = true) {
        ProviderListScreen(
            providers = previewProviders + ProviderPresets.openCodeZen.toProvider().copy(enabled = true),
            activeProviderId = "provider-deepseek",
            activeModelId = "deepseek-chat",
            configuredKeys = setOf("provider-deepseek", "opencode-zen"),
            onBack = {},
            onAddProvider = {},
            onOpenProvider = {},
            onToggleEnabled = { _, _ -> },
        )
    }
}

@Preview(name = "Provider sheet", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun ProviderPresetSheetPreview() {
    KujiraTheme(darkTheme = true) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ProviderPresetSheet(
                existingIds = setOf("deepseek", "opencode-zen"),
                onDismiss = {},
                onSelect = {},
                onCustom = {},
            )
        }
    }
}
