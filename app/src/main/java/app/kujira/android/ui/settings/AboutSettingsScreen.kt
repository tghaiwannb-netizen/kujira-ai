package app.kujira.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.ui.theme.KujiraTheme

/** Version, licenses, acknowledgements and a small brand moment. */
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    appVersion: String = "1.0.0",
    buildInfo: String? = null,
    onOpenSourceLicenses: (() -> Unit)? = null,
    onOpenAcknowledgements: (() -> Unit)? = null,
) {
    SettingsPage(
        title = "关于",
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = buildAnnotatedString {
                    append("Kujira")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(".") }
                },
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "一只能帮你干活的鲸鱼",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "版本 $appVersion",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Kujira 是一个 Android 原生 AI 客户端，支持多模型对话、流式 Markdown 渲染、"
                        + "会话历史，以及通过 MCP 插件调用外部工具。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "默认内置 SentinelX 插件，可以管理服务器、Cloudflare、容器和更多基础设施。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }

        SettingsSection(title = "信息") {
            SettingsValueRow(
                title = "版本",
                value = appVersion,
                icon = Icons.Outlined.Info,
            )
            if (buildInfo != null) {
                SettingsDivider()
                SettingsValueRow(
                    title = "构建",
                    value = buildInfo,
                    icon = Icons.Outlined.Code,
                )
            }
            SettingsDivider()
            SettingsRow(
                title = "开源许可",
                subtitle = "Compose、Kotlin、OkHttp 等依赖的许可信息",
                icon = Icons.Outlined.Description,
                onClick = onOpenSourceLicenses,
            )
            SettingsDivider()
            SettingsRow(
                title = "致谢",
                subtitle = "感谢所有让这个项目成为可能的人",
                icon = Icons.Outlined.FavoriteBorder,
                onClick = onOpenAcknowledgements,
            )
        }

        Text(
            text = "© 2026 Kujira · Kotlin + Jetpack Compose",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "About dark", showBackground = true, widthDp = 400, heightDp = 780)
@Composable
private fun AboutDarkPreview() {
    KujiraTheme(darkTheme = true) {
        AboutSettingsScreen(
            onBack = {},
            appVersion = PREVIEW_APP_VERSION,
            onOpenSourceLicenses = {},
            onOpenAcknowledgements = {},
        )
    }
}

@Preview(name = "About light", showBackground = true, widthDp = 400, heightDp = 780)
@Composable
private fun AboutLightPreview() {
    KujiraTheme(darkTheme = false) {
        AboutSettingsScreen(
            onBack = {},
            appVersion = PREVIEW_APP_VERSION,
        )
    }
}
