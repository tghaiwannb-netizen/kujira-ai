package app.kujira.android.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.net.mcp.PluginImport

/**
 * Paste-and-import dialog for Claude Desktop style `mcpServers` JSON.
 *
 * Parsing is live (pure function from [PluginImport]) so the user sees what
 * will be imported — and why stdio entries cannot be — before confirming.
 */
@Composable
fun ImportPluginsDialog(
    onDismiss: () -> Unit,
    onImport: (List<PluginConfig>) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) {
        if (text.isBlank()) null else PluginImport.parse(text)
    }
    val importable = parsed?.plugins.orEmpty()
    val canImport = parsed?.error == null && importable.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴导入 MCP 插件") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "粘贴 Claude Desktop 格式的 mcpServers JSON。仅支持 Streamable HTTP（带 url）的服务器，stdio（command/args）无法在手机上运行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = {
                        Text(
                            text = """{"mcpServers":{"我的插件":{"url":"https://example.com/mcp"}}}""",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                parsed?.error?.let { message ->
                    MessageBanner(message)
                }

                if (importable.isNotEmpty()) {
                    Text(
                        text = "将导入 ${importable.size} 个插件：" + importable.joinToString("、") { it.name },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!parsed?.skipped.isNullOrEmpty()) {
                    Text(
                        text = "跳过 ${parsed!!.skipped.size} 个：" +
                            parsed.skipped.joinToString("；") { "${it.name}（${it.reason}）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                parsed?.warnings?.forEach { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canImport) onImport(importable) },
                enabled = canImport
            ) {
                Text(if (importable.isEmpty()) "导入" else "导入 ${importable.size} 个")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
