package dev.gfire.termvr

import android.app.Application
import kotlinx.coroutines.launch

/**
 * Application entry point initializing the singleton dependency container.
 */
class TermVrApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) {
            container.appScope.launch {
                container.supervisor.value?.onTrimMemory(level)
            }
        }
    }
}
