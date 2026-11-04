package app.kujira.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import app.kujira.android.data.model.Conversation
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.ThemeMode
import app.kujira.android.ui.chat.ChatScreen
import app.kujira.android.ui.chat.previewMessages
import app.kujira.android.ui.settings.SettingsScreen
import app.kujira.android.ui.settings.previewProviders
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

    @Test
    fun chatDark() = render("chat-dark", dark = true, sizeDp = 891) {
        ChatScreen(
            messages = previewMessages,
            conversationTitle = "服务器状态检查",
            modelLabel = "DeepSeek V3.2",
            pluginsLabel = "2 个插件已连接",
            animateEntrances = false,
            onOpenModelPicker = {},
            onOpenSettings = {}
        )
    }

    @Test
    fun chatLight() = render("chat-light", dark = false, sizeDp = 891) {
        ChatScreen(
            messages = previewMessages,
            conversationTitle = "服务器状态检查",
            modelLabel = "Claude Sonnet 4.6",
            pluginsLabel = "1 个插件已连接",
            animateEntrances = false,
            onOpenModelPicker = {},
            onOpenSettings = {}
        )
    }

    @Test
    fun chatEmptyDark() = render("chat-empty-dark", dark = true, sizeDp = 891) {
        ChatScreen(
            messages = emptyList(),
            conversationTitle = "",
            modelLabel = "DeepSeek V3.2",
            onOpenModelPicker = {},
            onOpenSettings = {}
        )
    }

    @Test
    fun settingsDark() = render("settings-dark", dark = true, sizeDp = 891) {
        SettingsScreen(
            settings = app.kujira.android.data.model.AppSettings(
                themeMode = ThemeMode.DARK,
                activeProviderId = "deepseek",
                activeModelId = "deepseek-chat"
            ),
            providers = previewProviders,
            plugins = listOf(
                PluginConfig(
                    id = "sentinelx",
                    name = "SentinelX",
                    emoji = "🛰️",
                    url = "https://mcp.sentinelx.app/mcp/mcp",
                    preset = "sentinelx"
                )
            ),
            appVersion = "1.0.0",
            onBack = null
        )
    }

    @Test
    fun settingsLight() = render("settings-light", dark = false, sizeDp = 891) {
        SettingsScreen(
            settings = app.kujira.android.data.model.AppSettings(themeMode = ThemeMode.LIGHT),
            providers = previewProviders,
            plugins = emptyList(),
            appVersion = "1.0.0",
            onBack = null
        )
    }

    @Test
    fun pluginsDark() = render("plugins-dark", dark = true, sizeDp = 891) {
        app.kujira.android.ui.plugins.PluginListScreen(
            plugins = listOf(
                PluginConfig(
                    id = "sentinelx",
                    name = "SentinelX",
                    emoji = "🛰️",
                    url = "https://mcp.sentinelx.app/mcp/mcp",
                    preset = "sentinelx",
                    builtin = true,
                    status = app.kujira.android.data.model.PluginStatus.CONNECTED,
                    serverName = "SentinelX",
                    serverVersion = "3.4.11",
                    enabledToolNames = listOf("sentinel_exec", "sentinel_state", "sentinel_read")
                ),
                PluginConfig(
                    id = "custom",
                    name = "我的文件服务",
                    emoji = "📁",
                    url = "https://example.com/mcp",
                    status = app.kujira.android.data.model.PluginStatus.UNKNOWN
                )
            ),
            onBack = {},
            onAdd = {},
            onImport = {},
            onOpen = {},
            onToggleEnabled = { _, _ -> }
        )
    }

    @Test
    fun sentinelXLoggedOut() = render("sentinelx-out", dark = true, sizeDp = 891) {
        app.kujira.android.ui.sentinelx.SentinelXScreen(
            plugin = PluginConfig(
                id = "sentinelx",
                name = "SentinelX",
                emoji = "🛰️",
                url = "https://mcp.sentinelx.app/mcp/mcp",
                preset = "sentinelx",
                builtin = true,
                enabledToolNames = listOf("sentinel_exec", "sentinel_state")
            ),
            hosts = emptyList(),
            selectedHostId = null,
            onBack = {},
            onLogin = {},
            onLogout = {},
            onRefresh = {},
            onSelectHost = {},
            onQuickPrompt = {},
            onManageTools = {},
            authorized = false,
            totalToolCount = 0,
            enabledToolCount = 0
        )
    }

    @Test
    fun sentinelXConnected() = render("sentinelx-connected", dark = true, sizeDp = 891) {
        app.kujira.android.ui.sentinelx.SentinelXScreen(
            plugin = PluginConfig(
                id = "sentinelx",
                name = "SentinelX",
                emoji = "🛰️",
                url = "https://mcp.sentinelx.app/mcp/mcp",
                preset = "sentinelx",
                builtin = true,
                status = app.kujira.android.data.model.PluginStatus.CONNECTED,
                serverName = "SentinelX",
                serverVersion = "3.4.11",
                enabledToolNames = listOf("sentinel_exec", "sentinel_state", "sentinel_read")
            ),
            hosts = listOf(
                app.kujira.android.net.sentinelx.SentinelXPreset.HostEntry(
                    id = "host_demo_0001",
                    hostname = "demo-server",
                    label = null,
                    connected = true,
                    agentVersion = "0.23.34"
                )
            ),
            selectedHostId = "host_demo_0001",
            planSummary = "Free · 1/1 台已激活",
            onBack = {},
            onLogin = {},
            onLogout = {},
            onRefresh = {},
            onSelectHost = {},
            onQuickPrompt = {},
            onManageTools = {},
            authorized = true,
            totalToolCount = 95,
            enabledToolCount = 12
        )
    }

    private fun render(
        name: String,
        dark: Boolean,
        sizeDp: Int,
        content: @Composable () -> Unit
    ) {
        // Disable system animations so enter transitions finish instantly under Robolectric.
        android.provider.Settings.Global.putFloat(
            org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f
        )
        android.provider.Settings.Global.putFloat(
            org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 0f
        )
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            KujiraTheme(darkTheme = dark) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) { content() }
            }
        }
        // Advance the simulated clock so enter animations settle before capture.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(16)
        }
        val decor = activity.window.decorView
        val widthPx = 1080
        val heightPx = (sizeDp * 2.625f).toInt()
        // LazyColumn needs several measure/layout passes under Robolectric before
        // every visible item is composed and placed.
        repeat(6) {
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, widthPx, heightPx)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(120))
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
