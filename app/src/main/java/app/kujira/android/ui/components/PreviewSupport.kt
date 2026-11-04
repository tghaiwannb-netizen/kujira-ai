package app.kujira.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kujira.android.ui.theme.KujiraTheme

/**
 * Small helper so every component preview renders with the real Kujira theme
 * on the real background — no washed-out default Material previews.
 */
@Composable
internal fun KujiraPreviewBox(
    dark: Boolean,
    width: Dp? = null,
    content: @Composable () -> Unit,
) {
    KujiraTheme(darkTheme = dark) {
        Box(
            modifier = Modifier
                .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            content()
        }
    }
}
