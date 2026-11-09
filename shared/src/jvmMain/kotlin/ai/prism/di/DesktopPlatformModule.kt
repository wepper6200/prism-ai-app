package ai.prism.di

import ai.prism.data.db.PrismDatabase
import ai.prism.data.settings.KeyValueStore
import ai.prism.data.settings.PreferencesKeyValueStore
import ai.prism.desktop.AppDirectories
import ai.prism.domain.model.PlatformCapabilities
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.util.Properties

fun desktopPlatformModule(): Module = module {
    single<KeyValueStore> { PreferencesKeyValueStore() }
    single<SqlDriver> {
        val file = File(AppDirectories.dataDir(), DATABASE_NAME)
        JdbcSqliteDriver(
            url = "jdbc:sqlite:${file.absolutePath}",
            properties = Properties(),
            schema = PrismDatabase.Schema,
        )
    }
    single { PlatformCapabilities(supportsDynamicColor = false) }
}

private const val DATABASE_NAME = "prism.db"
