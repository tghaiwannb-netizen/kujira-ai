package app.kujira.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Visual weight of a [KujiraButton]. */
enum class KujiraButtonStyle { PRIMARY, TONAL, OUTLINED, TEXT }

/**
 * The single button used across Kujira: soft 16dp corners, no elevation,
 * 48dp minimum touch target.
 */
@Composable
fun KujiraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KujiraButtonStyle = KujiraButtonStyle.PRIMARY,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
) {
    val shape = RoundedCornerShape(16.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
        if (trailingIcon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    when (style) {
        KujiraButtonStyle.PRIMARY -> Button(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = 48.dp),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            contentPadding = contentPadding,
            content = content,
        )

        KujiraButtonStyle.TONAL -> FilledTonalButton(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = 48.dp),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 0.dp),
            contentPadding = contentPadding,
            content = content,
        )

        KujiraButtonStyle.OUTLINED -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = 48.dp),
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = contentPadding,
            content = content,
        )

        KujiraButtonStyle.TEXT -> TextButton(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = 48.dp),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

/** Borderless icon button with Kujira's muted tint by default. */
@Composable
fun KujiraIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}

@Preview(name = "Buttons dark", showBackground = true)
@Composable
private fun KujiraButtonsDarkPreview() {
    KujiraPreviewBox(dark = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KujiraButton(text = "主要按钮", onClick = {})
                KujiraButton(text = "次要", onClick = {}, style = KujiraButtonStyle.TONAL)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KujiraButton(text = "描边", onClick = {}, style = KujiraButtonStyle.OUTLINED)
                KujiraButton(text = "文字", onClick = {}, style = KujiraButtonStyle.TEXT)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KujiraButton(
                    text = "新建会话",
                    onClick = {},
                    leadingIcon = Icons.Outlined.Add,
                    trailingIcon = Icons.AutoMirrored.Rounded.Send,
                )
                KujiraButton(text = "不可用", onClick = {}, enabled = false)
            }
        }
    }
}

@Preview(name = "Buttons light", showBackground = true)
@Composable
private fun KujiraButtonsLightPreview() {
    KujiraPreviewBox(dark = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KujiraButton(text = "主要按钮", onClick = {})
                KujiraButton(text = "次要", onClick = {}, style = KujiraButtonStyle.TONAL)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KujiraButton(text = "描边", onClick = {}, style = KujiraButtonStyle.OUTLINED)
                KujiraButton(text = "文字", onClick = {}, style = KujiraButtonStyle.TEXT)
            }
            KujiraButton(text = "不可用", onClick = {}, enabled = false)
        }
    }
}
