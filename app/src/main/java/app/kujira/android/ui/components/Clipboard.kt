package app.kujira.android.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Copies text using the platform clipboard. On Android 13+ the system shows
 * its own copy confirmation, so we only toast on older versions.
 */
fun copyToClipboard(
    context: Context,
    text: String,
    label: String = "已复制",
) {
    if (text.isEmpty()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("kujira", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
    }
}

/** Composable-friendly wrapper around [copyToClipboard]. */
@Composable
fun rememberClipboardCopy(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text: String -> copyToClipboard(context, text) }
    }
}
