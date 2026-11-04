package app.kujira.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.kujira.android.data.model.ThemeMode
import app.kujira.android.di.AppContainer
import app.kujira.android.ui.chat.ChatRoute
import app.kujira.android.ui.plugins.PluginDetailRoute
import app.kujira.android.ui.plugins.PluginEditRoute
import app.kujira.android.ui.plugins.PluginsRoute
import app.kujira.android.ui.plugins.SentinelXRoute
import app.kujira.android.ui.settings.AppearanceRoute
import app.kujira.android.ui.settings.DataRoute
import app.kujira.android.ui.settings.ModelRoute
import app.kujira.android.ui.settings.ProviderRoute
import app.kujira.android.ui.settings.RootSettingsRoute
import app.kujira.android.ui.settings.AboutRoute
import app.kujira.android.ui.theme.KujiraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KujiraApplication).container
        setContent {
            KujiraRoot(container)
        }
    }
}

@Composable
fun KujiraRoot(container: AppContainer) {
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    KujiraTheme(darkTheme = darkTheme) {
        // The user's font preference scales every sp value without touching dp layout.
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, settings.fontScale)) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val navController = rememberNavController()
                KujiraNavHost(container, navController)
            }
        }
    }
}

@Composable
private fun KujiraNavHost(container: AppContainer, navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.CHAT) {
        composable(Routes.CHAT) {
            ChatRoute(
                container = container,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenPlugins = { navController.navigate(Routes.PLUGINS) }
            )
        }
        composable(Routes.SETTINGS) {
            RootSettingsRoute(
                container = container,
                onBack = { navController.popBackStack() },
                onNavigateAppearance = { navController.navigate(Routes.APPEARANCE) },
                onNavigateModels = { navController.navigate(Routes.MODELS) },
                onNavigatePlugins = { navController.navigate(Routes.PLUGINS) },
                onNavigateData = { navController.navigate(Routes.DATA) },
                onNavigateAbout = { navController.navigate(Routes.ABOUT) }
            )
        }
        composable(Routes.APPEARANCE) {
            AppearanceRoute(container = container, onBack = { navController.popBackStack() })
        }
        composable(Routes.MODELS) {
            ModelRoute(
                container = container,
                onBack = { navController.popBackStack() },
                onManageProviders = { navController.navigate(Routes.PROVIDER_EDITOR) }
            )
        }
        composable(Routes.PROVIDER_EDITOR) {
            ProviderRoute(
                container = container,
                providerId = null,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.PROVIDER_DETAIL) { entry ->
            ProviderRoute(
                container = container,
                providerId = entry.arguments?.getString("providerId"),
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.PLUGINS) {
            PluginsRoute(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenPlugin = { id -> navController.navigate(Routes.pluginDetail(id)) },
                onOpenSentinelX = { navController.navigate(Routes.SENTINELX) }
            )
        }
        composable(Routes.SENTINELX) {
            SentinelXRoute(
                container = container,
                onBack = { navController.popBackStack() },
                onManageTools = { id -> navController.navigate(Routes.pluginDetail(id)) },
                onQuickPrompt = { prompt ->
                    container.pendingPrompt = prompt
                    navController.popBackStack(Routes.CHAT, inclusive = false)
                }
            )
        }
        composable(Routes.PLUGIN_DETAIL) { entry ->
            val pluginId = entry.arguments?.getString("pluginId").orEmpty()
            if (pluginId == Routes.NEW_PLUGIN_ID) {
                PluginEditRoute(
                    container = container,
                    pluginId = null,
                    onBack = { navController.popBackStack() }
                )
            } else {
                PluginDetailRoute(
                    container = container,
                    pluginId = pluginId,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(Routes.pluginEdit(pluginId)) },
                    onOpenSentinelX = { navController.navigate(Routes.SENTINELX) }
                )
            }
        }
        composable(Routes.PLUGIN_EDIT) { entry ->
            PluginEditRoute(
                container = container,
                pluginId = entry.arguments?.getString("pluginId"),
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.DATA) {
            DataRoute(container = container, onBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutRoute(container = container, onBack = { navController.popBackStack() })
        }
    }
}

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val APPEARANCE = "settings/appearance"
    const val MODELS = "settings/models"
    const val PROVIDER_EDITOR = "settings/provider/new"
    const val PROVIDER_DETAIL = "settings/provider/{providerId}"
    const val PLUGINS = "settings/plugins"
    const val SENTINELX = "sentinelx"
    const val NEW_PLUGIN_ID = "new"
    const val PLUGIN_DETAIL_PATH = "plugin"
    const val PLUGIN_DETAIL = "plugin/{pluginId}"
    const val PLUGIN_EDIT = "plugin/{pluginId}/edit"
    const val DATA = "settings/data"
    const val ABOUT = "settings/about"

    fun providerDetail(id: String) = "settings/provider/$id"

    fun pluginDetail(id: String) = "plugin/$id"

    fun pluginEdit(id: String) = "plugin/$id/edit"
}
