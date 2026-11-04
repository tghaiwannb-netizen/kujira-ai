package app.kujira.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.kujira.android.ui.theme.KujiraTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * JVM screenshot harness: renders Compose UI through a Robolectric activity and
 * draws the decor view into a bitmap, writing PNGs to app/build/screenshots/.
 * Lets the team eyeball screens without an emulator or device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {

    private val widthPx = 1080
    private val heightPx = 2400

    @Test
    fun smoke() = render("smoke") {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Kujira", style = MaterialTheme.typography.displayLarge)
            }
        }
    }

    private fun render(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            KujiraTheme(darkTheme = true) { content() }
        }
        // Let Compose run its initial composition and one measure/layout pass.
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(16)
        }
        val decor = activity.window.decorView
        if (decor.width == 0 || decor.height == 0) {
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, widthPx, heightPx)
        }
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(16)
        }
        val w = if (decor.width > 0) decor.width else widthPx
        val h = if (decor.height > 0) decor.height else heightPx
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val out = File("build/screenshots/$name.png")
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT ${out.absolutePath} ${w}x$h")
        controller.pause().stop().destroy()
    }
}
