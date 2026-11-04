package app.kujira.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.ui.theme.KujiraTheme

/**
 * Model selection + generation parameters. Model rows are grouped per
 * provider; selecting a model binds it to new messages.
 */
@Composable
fun ModelSettingsScreen(
    providers: List<ProviderConfig>,
    settings: AppSettings,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTemperatureChange: (Double) -> Unit = {},
    onMaxTokensChange: (Int) -> Unit = {},
    onSystemPromptChange: (String) -> Unit = {},
    onManageProviders: (() -> Unit)? = null,
) {
    SettingsPage(
        title = "模型",
        subtitle = "${providers.count { it.enabled }}/${providers.size} 个 Provider 已启用",
        onBack = onBack,
        modifier = modifier,
    ) {
        providers.forEach { provider ->
            SettingsSection(title = provider.name) {
                if (provider.models.isEmpty()) {
                    SettingsRow(
                        title = "还没有模型",
                        subtitle = "在 Provider 设置里添加模型列表",
                    )
                } else {
                    provider.models.forEachIndexed { index, model ->
                        if (index > 0) SettingsDivider()
                        val meta = buildList {
                            if (!provider.enabled) add("已禁用")
                            if (model.supportsTools) add("支持工具")
                            model.contextWindow?.let { add("${formatContextWindow(it)} 上下文") }
                        }.joinToString(" · ")
                        SettingsChoiceRow(
                            title = model.displayName,
                            subtitle = meta.ifBlank { provider.kind.name.lowercase() },
                            selected = settings.activeProviderId == provider.id &&
                                settings.activeModelId == model.id,
                            onClick = { onSelectModel(provider.id, model.id) },
                        )
                    }
                }
            }
        }

        if (onManageProviders != null) {
            SettingsSection(title = "Provider") {
                SettingsRow(
                    title = "Provider 与 API Key",
                    subtitle = "添加、编辑、测试连接",
                    icon = Icons.Outlined.Key,
                    onClick = onManageProviders,
                )
            }
        }

        SettingsSection(title = "生成参数") {
            ParameterSlider(
                title = "Temperature",
                value = formatTemperature(settings.temperature),
                sliderValue = settings.temperature.toFloat(),
                valueRange = 0f..2f,
                steps = 19,
                onValueChange = { onTemperatureChange((it * 10).toInt() / 10.0) },
            )
            SettingsDivider(indent = false)
            ParameterSlider(
                title = "最大输出 tokens",
                value = settings.maxTokens.toString(),
                sliderValue = settings.maxTokens.toFloat().coerceIn(512f, 8192f),
                valueRange = 512f..8192f,
                steps = 14,
                onValueChange = { onMaxTokensChange(it.toInt()) },
            )
            SettingsDivider(indent = false)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = "系统提示词",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
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
                        value = settings.systemPrompt,
                        onValueChange = onSystemPromptChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 3,
                        maxLines = 6,
                        decorationBox = { innerTextField ->
                            Box {
                                if (settings.systemPrompt.isEmpty()) {
                                    Text(
                                        text = "写点什么给 Kujira 定调…",
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
        }

        Text(
            text = "参数会应用到新消息，不会改动已有回复。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ParameterSlider(
    title: String,
    value: String,
    sliderValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
                inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}

private fun formatContextWindow(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1000 -> "${tokens / 1000}K"
    else -> tokens.toString()
}

@Preview(name = "Models dark", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun ModelSettingsDarkPreview() {
    KujiraTheme(darkTheme = true) {
        ModelSettingsScreen(
            providers = previewProviders,
            settings = previewSettings,
            onSelectModel = { _, _ -> },
            onBack = {},
            onManageProviders = {},
        )
    }
}

@Preview(name = "Models light", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun ModelSettingsLightPreview() {
    KujiraTheme(darkTheme = false) {
        ModelSettingsScreen(
            providers = previewProviders.take(2),
            settings = previewSettings.copy(activeModelId = "claude-sonnet-4-5", activeProviderId = "provider-anthropic"),
            onSelectModel = { _, _ -> },
            onBack = {},
        )
    }
}
