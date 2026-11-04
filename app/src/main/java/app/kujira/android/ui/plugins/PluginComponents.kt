package app.kujira.android.ui.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.PluginStatus
import app.kujira.android.data.model.ToolPolicy
import app.kujira.android.net.sentinelx.SentinelXPreset

/*
 * Shared building blocks for the plugin / SentinelX screens.
 *
 * Everything here is stateless; screens own their transient UI state and the
 * Lead wires the real state and callbacks.
 */

internal val PluginCardShape = RoundedCornerShape(18.dp)
internal val PluginChipShape = RoundedCornerShape(999.dp)

fun pluginStatusLabel(status: PluginStatus): String = when (status) {
    PluginStatus.UNKNOWN -> "未连接"
    PluginStatus.CONNECTING -> "连接中…"
    PluginStatus.CONNECTED -> "已连接"
    PluginStatus.ERROR -> "连接失败"
    PluginStatus.AUTH_REQUIRED -> "需要授权"
}

@Composable
fun pluginStatusColor(status: PluginStatus): Color = when (status) {
    PluginStatus.CONNECTED -> MaterialTheme.colorScheme.tertiary
    PluginStatus.CONNECTING -> MaterialTheme.colorScheme.secondary
    PluginStatus.ERROR -> MaterialTheme.colorScheme.error
    PluginStatus.AUTH_REQUIRED -> MaterialTheme.colorScheme.primary
    PluginStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun toolPolicyLabel(policy: ToolPolicy): String = when (policy) {
    ToolPolicy.AUTO -> "自动"
    ToolPolicy.ASK -> "确认"
    ToolPolicy.DISABLED -> "禁用"
}

/** Human explanation for a plugin failure, using the SentinelX translator when relevant. */
fun explainPluginError(plugin: PluginConfig, message: String?): String? {
    val text = message?.takeIf { it.isNotBlank() } ?: return null
    if (plugin.isSentinelX) {
        return SentinelXPreset.explainError(text) ?: text
    }
    return text
}

@Composable
fun StatusChip(status: PluginStatus, modifier: Modifier = Modifier) {
    val color = pluginStatusColor(status)
    Surface(modifier = modifier, color = color.copy(alpha = 0.14f), shape = PluginChipShape) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(pluginStatusLabel(status), style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier = modifier, color = color.copy(alpha = 0.14f), shape = PluginChipShape) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun EmojiBadge(emoji: String, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, fontSize = (size.value * 0.46f).sp)
    }
}

@Composable
fun PolicyChips(
    selected: ToolPolicy,
    onSelect: (ToolPolicy) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolPolicy.entries.forEach { policy ->
            FilterChip(
                selected = selected == policy,
                onClick = { onSelect(policy) },
                label = { Text(toolPolicyLabel(policy), style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        val style = if (mono) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodySmall
        }
        Text(text = value, style = style, modifier = Modifier.weight(1f))
    }
}

@Composable
fun MessageBanner(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = true
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun EmptyHint(
    emoji: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = emoji, fontSize = 34.sp)
        Spacer(Modifier.size(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        fontWeight = FontWeight.Normal,
        modifier = modifier
    )
}
