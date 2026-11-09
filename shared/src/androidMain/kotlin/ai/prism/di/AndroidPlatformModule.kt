package ai.prism.di

import ai.prism.data.db.PrismDatabase
import ai.prism.data.settings.KeyValueStore
import ai.prism.data.settings.SharedPreferencesKeyValueStore
import ai.prism.domain.model.PlatformCapabilities
import android.content.Context
import android.os.Build
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.koin.core.module.Module
import org.koin.dsl.module

fun androidPlatformModule(context: Context): Module = module {
    val appContext = context.applicationContext
    single<KeyValueStore> { SharedPreferencesKeyValueStore(appContext) }
    single<SqlDriver> { AndroidSqliteDriver(schema = PrismDatabase.Schema, context = appContext, name = DATABASE_NAME) }
    single {
        PlatformCapabilities(supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    }
}

private const val DATABASE_NAME = "prism.db"
