package app.kujira.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role
import app.kujira.android.ui.chat.MessageItem
import app.kujira.android.ui.chat.markdown.MarkdownText
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

/** Narrow probes to isolate rendering problems in transcript content. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class RenderProbeTest {

    @Test
    fun markdownAlone() = render("probe-markdown") {
        MarkdownText(
            markdown = "## nginx 日志\n\n最近的错误主要是 **502**：\n\n- upstream 超时\n- worker 重启\n\n```bash\ntail -100 /var/log/nginx/error.log\n```\n\n| 时间 | 状态 |\n| --- | --- |\n| 10:20 | 502 |\n",
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
    }

    @Test
    fun assistantPlain() = render("probe-assistant") {
        MessageItem(
            message = Message(
                conversationId = "c",
                role = Role.ASSISTANT,
                blocks = listOf(ContentBlock.Text("你好，我是 Kujira。这是一条普通的 assistant 回复，用来验证消息气泡渲染。")),
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    @Test
    fun assistantMarkdown() = render("probe-assistant-md") {
        MessageItem(
            message = Message(
                conversationId = "c",
                role = Role.ASSISTANT,
                blocks = listOf(ContentBlock.Text("## nginx 日志\n\n最近的错误主要是 **502**：\n\n- upstream 超时\n- worker 重启\n")),
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    @Test
    fun assistantWithTool() = render("probe-assistant-tool") {
        MessageItem(
            message = Message(
                conversationId = "c",
                role = Role.ASSISTANT,
                blocks = listOf(
                    ContentBlock.Text("我来查一下服务器状态。"),
                    ContentBlock.ToolCall(
                        id = "call-1",
                        name = "sentinel_exec",
                        argumentsJson = "{\"command\":\"df -h\"}",
                        pluginId = "sentinelx",
                        pluginName = "SentinelX"
                    ),
                    ContentBlock.ToolResult(
                        callId = "call-1",
                        toolName = "sentinel_exec",
                        pluginId = "sentinelx",
                        output = "/dev/vda1  40G  12G  26G  32% /",
                        isError = false,
                        durationMs = 320
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    @Test
    fun lazyColumnTwoItems() = render("probe-lazy") {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(listOf("A", "B", "C"), key = { _, s -> s }) { index, s ->
                androidx.compose.material3.Text(
                    text = "item $s index=$index",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            }
        }
    }

    @Test
    fun lazyColumnWithAnimateItem() = render("probe-lazy-anim") {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(listOf("A", "B", "C"), key = { _, s -> s }) { index, s ->
                androidx.compose.material3.Text(
                    text = "item $s index=$index",
                    modifier = Modifier.fillMaxWidth().padding(16.dp).animateItem(),
                    fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            }
        }
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        android.provider.Settings.Global.putFloat(
            org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f
        )
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            KujiraTheme(darkTheme = true) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) { content() }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(16)
        }
        val decor = activity.window.decorView
        val widthPx = 1080
        val heightPx = 900
        if (decor.width == 0 || decor.height == 0) {
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, widthPx, heightPx)
        }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
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
