package app.kujira.android.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ThemeMode
import app.kujira.android.ui.theme.KujiraTheme

/** Theme, type scale and reading preferences. */
@Composable
fun AppearanceSettingsScreen(
    settings: AppSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onShowReasoningChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPage(
        title = "外观",
        subtitle = "主题、字号与阅读偏好",
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsSection(title = "主题") {
            SettingsChoiceRow(
                title = "深色",
                subtitle = "墨黑底 + 陶土橙，默认",
                icon = Icons.Outlined.DarkMode,
                selected = settings.themeMode == ThemeMode.DARK,
                onClick = { onThemeModeChange(ThemeMode.DARK) },
            )
            SettingsDivider()
            SettingsChoiceRow(
                title = "浅色",
                subtitle = "暖纸底，适合白天阅读",
                icon = Icons.Outlined.LightMode,
                selected = settings.themeMode == ThemeMode.LIGHT,
                onClick = { onThemeModeChange(ThemeMode.LIGHT) },
            )
            SettingsDivider()
            SettingsChoiceRow(
                title = "跟随系统",
                subtitle = "随系统的深色模式自动切换",
                icon = Icons.Outlined.Contrast,
                selected = settings.themeMode == ThemeMode.SYSTEM,
                onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
            )
        }

        SettingsSection(title = "字号") {
            listOf(
                0.9f to "小",
                1.0f to "中",
                1.15f to "大",
                1.3f to "特大",
            ).forEachIndexed { index, (scale, label) ->
                if (index > 0) SettingsDivider()
                SettingsChoiceRow(
                    title = label,
                    subtitle = "正文 ${(16 * scale).toInt()}sp",
                    selected = kotlin.math.abs(settings.fontScale - scale) < 0.02f,
                    onClick = { onFontScaleChange(scale) },
                )
            }
        }

        Surface(
            modifier = Modifier.padding(horizontal = 4.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(
                text = "Kujira 会记住你的阅读偏好。重要的信息会被加粗，代码用等宽字体展示。",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (16 * settings.fontScale).sp,
                    lineHeight = (25 * settings.fontScale).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
            )
        }

        SettingsSection(title = "阅读") {
            SettingsSwitchRow(
                title = "显示思考过程",
                subtitle = "展示模型在回答前的 reasoning 内容",
                icon = Icons.Outlined.Lightbulb,
                checked = settings.showReasoning,
                onCheckedChange = onShowReasoningChange,
            )
        }
    }
}

@Preview(name = "Appearance dark", showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun AppearanceDarkPreview() {
    KujiraTheme(darkTheme = true) {
        AppearanceSettingsScreen(
            settings = previewSettings,
            onThemeModeChange = {},
            onFontScaleChange = {},
            onShowReasoningChange = {},
            onBack = {},
        )
    }
}

@Preview(name = "Appearance light", showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun AppearanceLightPreview() {
    KujiraTheme(darkTheme = false) {
        AppearanceSettingsScreen(
            settings = previewSettings.copy(themeMode = ThemeMode.LIGHT, fontScale = 1.15f),
            onThemeModeChange = {},
            onFontScaleChange = {},
            onShowReasoningChange = {},
            onBack = {},
        )
    }
}
