package ai.prism.android

import ai.prism.di.androidPlatformModule
import ai.prism.di.initKoin
import android.app.Application
import org.koin.core.Koin

class PrismApplication : Application() {

    lateinit var koin: Koin
        private set

    override fun onCreate() {
        super.onCreate()
        koin = initKoin(androidPlatformModule(this)).koin
    }
}
