package app.kujira.android.di

import android.content.Context
import app.kujira.android.core.SecureStore
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.db.AppDatabase
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container.
 *
 * Everything in the app hangs off this; it is created once in
 * [app.kujira.android.KujiraApplication] and handed to view-model factories.
 * Components from the network/MCP layers are wired here once their
 * implementations land.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val database: AppDatabase = AppDatabase(appContext)
    val secureStore: SecureStore = SecureStore(appContext)
    val settingsStore: SettingsStore = SettingsStore(appContext)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Fast client for small JSON-RPC control calls and OAuth token exchange. */
    val controlClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
}
