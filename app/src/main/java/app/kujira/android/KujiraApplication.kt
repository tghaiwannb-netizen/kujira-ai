package app.kujira.android

import android.app.Application
import app.kujira.android.di.AppContainer

class KujiraApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
