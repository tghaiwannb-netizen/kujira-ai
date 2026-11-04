package app.kujira.android.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Destructive confirmation dialog, rounded to match the rest of Kujira. */
@Composable
fun KujiraConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "确定",
    dismissText: String = "取消",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Preview(name = "ConfirmDialog dark", showBackground = true)
@Composable
private fun KujiraConfirmDialogDarkPreview() {
    KujiraPreviewBox(dark = true) {
        KujiraConfirmDialog(
            title = "清空全部数据？",
            message = "所有会话、消息和插件配置都会被删除，且无法恢复。",
            confirmText = "清空",
            destructive = true,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "ConfirmDialog light", showBackground = true)
@Composable
private fun KujiraConfirmDialogLightPreview() {
    KujiraPreviewBox(dark = false) {
        KujiraConfirmDialog(
            title = "清空全部数据？",
            message = "所有会话、消息和插件配置都会被删除，且无法恢复。",
            confirmText = "清空",
            destructive = true,
            onConfirm = {},
            onDismiss = {},
        )
    }
}
