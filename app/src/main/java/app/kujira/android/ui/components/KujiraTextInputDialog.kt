package app.kujira.android.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Single-line rename/input dialog used by the session drawer and settings. */
@Composable
fun KujiraTextInputDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "名称",
    confirmText: String = "保存",
    dismissText: String = "取消",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier,
                label = { Text(label) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text(text = confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Preview(name = "InputDialog dark", showBackground = true)
@Composable
private fun KujiraTextInputDialogDarkPreview() {
    KujiraPreviewBox(dark = true) {
        KujiraTextInputDialog(
            title = "重命名会话",
            value = "服务器状态检查",
            onValueChange = {},
            onConfirm = {},
            onDismiss = {},
            label = "会话名称",
        )
    }
}

@Preview(name = "InputDialog light", showBackground = true)
@Composable
private fun KujiraTextInputDialogLightPreview() {
    KujiraPreviewBox(dark = false) {
        KujiraTextInputDialog(
            title = "重命名会话",
            value = "服务器状态检查",
            onValueChange = {},
            onConfirm = {},
            onDismiss = {},
            label = "会话名称",
        )
    }
}
