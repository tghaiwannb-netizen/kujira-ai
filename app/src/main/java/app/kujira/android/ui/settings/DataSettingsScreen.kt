package app.kujira.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.ui.components.KujiraConfirmDialog
import app.kujira.android.ui.theme.KujiraTheme

/** Export / import / destructive data actions with in-page confirmation. */
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onExportData: () -> Unit,
    onImportData: () -> Unit,
    onClearConversations: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    conversationCount: Int = 0,
    messageCount: Int = 0,
) {
    var confirmAction by remember { mutableStateOf<DataAction?>(null) }

    SettingsPage(
        title = "数据",
        subtitle = "备份、导入与清理",
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsSection(title = "本地数据") {
            SettingsValueRow(title = "会话", value = "$conversationCount 个", icon = Icons.Outlined.Notes)
            SettingsDivider()
            SettingsValueRow(title = "消息", value = "$messageCount 条", icon = Icons.Outlined.History)
        }

        SettingsSection(title = "备份") {
            SettingsRow(
                title = "导出全部数据",
                subtitle = "会话、设置与插件配置导出为 JSON",
                icon = Icons.Outlined.Download,
                onClick = onExportData,
            )
            SettingsDivider()
            SettingsRow(
                title = "从备份导入",
                subtitle = "从 JSON 文件恢复，重复项会跳过",
                icon = Icons.Outlined.Upload,
                onClick = onImportData,
            )
        }

        SettingsSection(title = "危险操作") {
            SettingsRow(
                title = "清空所有会话",
                subtitle = "保留设置与插件配置",
                icon = Icons.Outlined.DeleteSweep,
                destructive = true,
                onClick = { confirmAction = DataAction.CLEAR_CONVERSATIONS },
            )
            SettingsDivider()
            SettingsRow(
                title = "清空全部数据",
                subtitle = "会话、设置、插件配置与密钥都会被删除",
                icon = Icons.Outlined.DeleteSweep,
                destructive = true,
                onClick = { confirmAction = DataAction.CLEAR_ALL },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "所有数据只保存在本机。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "导出文件不包含 API Key。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(4.dp))
    }

    confirmAction?.let { action ->
        val clearAll = action == DataAction.CLEAR_ALL
        KujiraConfirmDialog(
            title = if (clearAll) "清空全部数据？" else "清空所有会话？",
            message = if (clearAll) {
                "会话、消息、设置、插件配置和加密保存的 API Key 都会被删除，且无法恢复。"
            } else {
                "所有会话和消息会被删除，设置与插件配置保留。此操作无法恢复。"
            },
            confirmText = "清空",
            destructive = true,
            onConfirm = {
                confirmAction = null
                if (clearAll) onClearAll() else onClearConversations()
            },
            onDismiss = { confirmAction = null },
        )
    }
}

private enum class DataAction { CLEAR_CONVERSATIONS, CLEAR_ALL }

@Preview(name = "Data dark", showBackground = true, widthDp = 400, heightDp = 760)
@Composable
private fun DataDarkPreview() {
    KujiraTheme(darkTheme = true) {
        DataSettingsScreen(
            onBack = {},
            onExportData = {},
            onImportData = {},
            onClearConversations = {},
            onClearAll = {},
            conversationCount = 12,
            messageCount = 148,
        )
    }
}

@Preview(name = "Data light", showBackground = true, widthDp = 400, heightDp = 760)
@Composable
private fun DataLightPreview() {
    KujiraTheme(darkTheme = false) {
        DataSettingsScreen(
            onBack = {},
            onExportData = {},
            onImportData = {},
            onClearConversations = {},
            onClearAll = {},
            conversationCount = 3,
            messageCount = 27,
        )
    }
}
