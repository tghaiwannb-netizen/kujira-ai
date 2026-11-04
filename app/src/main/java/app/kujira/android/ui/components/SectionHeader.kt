package app.kujira.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Serif content-section heading — used for page sections, not for dense
 * settings groups (those use a quieter uppercase label).
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke(this)
    }
}

@Preview(name = "SectionHeader dark", showBackground = true)
@Composable
private fun SectionHeaderDarkPreview() {
    KujiraPreviewBox(dark = true) {
        Column {
            SectionHeader(title = "外观", subtitle = "主题、字号与阅读偏好")
            Spacer(Modifier.height(20.dp))
            SectionHeader(
                title = "最近使用",
                trailing = { KujiraButton(text = "全部", onClick = {}, style = KujiraButtonStyle.TEXT) },
            )
        }
    }
}

@Preview(name = "SectionHeader light", showBackground = true)
@Composable
private fun SectionHeaderLightPreview() {
    KujiraPreviewBox(dark = false) {
        SectionHeader(title = "外观", subtitle = "主题、字号与阅读偏好")
    }
}
