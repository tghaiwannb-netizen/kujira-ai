package app.kujira.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Blinking vertical bar appended to a streaming assistant message.
 * Sits on the text baseline, hence the small default size.
 */
@Composable
fun StreamingCursor(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    width: Dp = 2.dp,
    height: Dp = 16.dp,
) {
    val transition = rememberInfiniteTransition(label = "streaming-cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 540, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "streaming-cursor-alpha",
    )
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(1.dp))
            .background(color.copy(alpha = alpha))
    )
}

@Preview(name = "StreamingCursor dark", showBackground = true)
@Composable
private fun StreamingCursorDarkPreview() {
    KujiraPreviewBox(dark = true) {
        StreamingCursor(height = 22.dp)
    }
}

@Preview(name = "StreamingCursor light", showBackground = true)
@Composable
private fun StreamingCursorLightPreview() {
    KujiraPreviewBox(dark = false) {
        StreamingCursor(height = 22.dp)
    }
}
