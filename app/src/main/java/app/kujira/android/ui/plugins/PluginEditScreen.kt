package app.kujira.android.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.McpAuthMode
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.net.sentinelx.SentinelXPreset

/**
 * Add / edit an MCP plugin: name, emoji, URL, auth mode, headers.
 *
 * `onSave(config, bearerToken)` — `bearerToken` is non-null only when the user
 * typed a new token for BEARER auth; the caller stores it in the encrypted
 * SecureStore (see `McpSecrets.bearerKey`), never in the plugin JSON.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginEditScreen(
    initial: PluginConfig?,
    onBack: () -> Unit,
    onSave: (PluginConfig, String?) -> Unit,
    modifier: Modifier = Modifier,
    initialBearerToken: String? = null
) {
    val lockedPreset = initial != null && SentinelXPreset.isSentinelX(initial) && initial.builtin

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "🔌") }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var authMode by remember { mutableStateOf(initial?.authMode ?: McpAuthMode.NONE) }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val headers = remember {
        mutableStateListOf<HeaderDraft>().apply {
            initial?.headers?.forEach { (key, value) -> add(HeaderDraft(key, value)) }
        }
    }

    fun save() {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        error = when {
            trimmedName.isEmpty() -> "请填写插件名称"
            !lockedPreset && !trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://") ->
                "URL 需要以 http:// 或 https:// 开头"
            lockedPreset -> null
            else -> null
        }
        if (error != null) return

        val headerMap = headers
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value.trim() }

        val base = initial ?: PluginConfig(name = trimmedName, url = trimmedUrl)
        val config = base.copy(
            name = trimmedName,
            emoji = emoji.trim().ifEmpty { "🔌" },
            url = if (lockedPreset) initial!!.url else trimmedUrl,
            authMode = if (lockedPreset) initial!!.authMode else authMode,
            headers = headerMap
        )
        val newToken = token.trim().takeIf { it.isNotEmpty() && config.authMode == McpAuthMode.BEARER }
        onSave(config, newToken)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "添加插件" else "编辑插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { save() }) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                placeholder = { Text("例如：我的知识库") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { if (it.length <= 8) emoji = it },
                    label = { Text("图标") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
                Text(
                    text = "用 emoji 作为插件图标。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("MCP 端点 URL") },
                placeholder = { Text("https://example.com/mcp") },
                singleLine = true,
                enabled = !lockedPreset,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    if (lockedPreset) {
                        Text("内置插件使用 SentinelX 官方端点，不可修改。")
                    } else {
                        Text("必须是 Streamable HTTP 端点（POST JSON-RPC）。")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("认证方式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McpAuthMode.entries.forEach { mode ->
                        FilterChip(
                            selected = authMode == mode,
                            enabled = !lockedPreset,
                            onClick = { authMode = mode },
                            label = { Text(authModeLabel(mode), style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
                Text(
                    text = authModeHint(authMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (authMode == McpAuthMode.BEARER && !lockedPreset) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer Token") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showToken) "隐藏 Token" else "显示 Token"
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            if (initialBearerToken.isNullOrBlank()) {
                                "Token 会加密保存在 Android Keystore 中。"
                            } else {
                                "已有保存的 Token；留空表示保持不变。"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("自定义 Header", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { headers.add(HeaderDraft("", "")) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                }
                if (headers.isEmpty()) {
                    Text(
                        text = "一般不需要。需要静态鉴权头时在这里添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                headers.forEachIndexed { index, header ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = header.key,
                            onValueChange = { headers[index] = headers[index].copy(key = it) },
                            label = { Text("Header") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = header.value,
                            onValueChange = { headers[index] = headers[index].copy(value = it) },
                            label = { Text("值") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { headers.removeAt(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = "删除 Header")
                        }
                    }
                }
            }

            error?.let { MessageBanner(it) }

            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("保存")
            }

            if (lockedPreset) {
                Text(
                    text = "这是内置的 SentinelX 插件：端点与 OAuth 已固定，你仍然可以修改名称和图标。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private data class HeaderDraft(val key: String, val value: String)

private fun authModeLabel(mode: McpAuthMode): String = when (mode) {
    McpAuthMode.NONE -> "无认证"
    McpAuthMode.BEARER -> "Bearer"
    McpAuthMode.OAUTH -> "OAuth"
}

private fun authModeHint(mode: McpAuthMode): String = when (mode) {
    McpAuthMode.NONE -> "服务器不需要凭证，直接连接。"
    McpAuthMode.BEARER -> "使用 Bearer Token 认证；Token 加密保存，不写进插件配置。"
    McpAuthMode.OAUTH -> "浏览器授权（Authorization Code + PKCE）。目前内置 SentinelX 预设支持此模式。"
}
