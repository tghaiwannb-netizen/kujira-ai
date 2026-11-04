package app.kujira.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pill chip with no outline — used for the model selector and plugin status
 * inside the composer.
 */
@Composable
fun KujiraChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    leadingDotColor: Color? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    maxWidth: Dp = 240.dp,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val chipModifier = modifier
        .widthIn(max = maxWidth)
        .defaultMinSize(minHeight = 30.dp)

    val contentRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingDotColor != null) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(leadingDotColor)
                )
            }
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = content,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingIcon != null) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = content,
                )
            }
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = chipModifier,
            enabled = enabled,
            shape = CircleShape,
            color = container,
            contentColor = content,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            contentRow()
        }
    } else {
        Surface(
            modifier = chipModifier,
            shape = CircleShape,
            color = container,
            contentColor = content,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            contentRow()
        }
    }
}

@Preview(name = "Chip dark", showBackground = true)
@Composable
private fun KujiraChipDarkPreview() {
    KujiraPreviewBox(dark = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KujiraChip(
                label = "DeepSeek V3.2",
                leadingIcon = Icons.Outlined.Bolt,
                trailingIcon = Icons.Outlined.ArrowDropDown,
                onClick = {},
            )
            KujiraChip(
                label = "2 个插件已连接",
                leadingDotColor = MaterialTheme.colorScheme.tertiary,
                onClick = {},
            )
            KujiraChip(label = "已选中", selected = true)
        }
    }
}

@Preview(name = "Chip light", showBackground = true)
@Composable
private fun KujiraChipLightPreview() {
    KujiraPreviewBox(dark = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KujiraChip(
                label = "DeepSeek V3.2",
                leadingIcon = Icons.Outlined.Bolt,
                trailingIcon = Icons.Outlined.ArrowDropDown,
                onClick = {},
            )
            KujiraChip(
                label = "插件已关闭",
                leadingDotColor = MaterialTheme.colorScheme.outline,
                onClick = {},
            )
        }
    }
}
