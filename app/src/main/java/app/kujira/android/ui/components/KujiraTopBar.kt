package app.kujira.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Restrained top bar shared by settings pages and secondary screens.
 * The chat screen has its own header (menu / title / model / actions).
 */
@Composable
fun KujiraTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            KujiraIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                size = 44.dp,
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

@Preview(name = "TopBar dark", showBackground = true)
@Composable
private fun KujiraTopBarDarkPreview() {
    KujiraPreviewBox(dark = true) {
        Column {
            KujiraTopBar(title = "设置", onBack = {})
            KujiraTopBar(
                title = "外观",
                subtitle = "主题、字号与阅读偏好",
                onBack = {},
                actions = {
                    KujiraIconButton(
                        icon = Icons.Outlined.MoreVert,
                        contentDescription = "更多",
                        onClick = {},
                    )
                },
            )
        }
    }
}

@Preview(name = "TopBar light", showBackground = true)
@Composable
private fun KujiraTopBarLightPreview() {
    KujiraPreviewBox(dark = false) {
        KujiraTopBar(title = "设置", onBack = {})
    }
}
