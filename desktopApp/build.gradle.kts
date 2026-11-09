import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(providers.gradleProperty("prism.werror").map { it.toBoolean() }.orElse(true))
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "ai.prism.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "PrismAI"
            packageVersion = libs.versions.prism.versionName.get()
            description = "Prism AI — офлайн ИИ-ассистент"
            vendor = "Prism AI"
            // java.sql — для SQLite (SQLDelight), java.prefs — для сохранения настроек.
            modules("java.sql", "java.prefs")

            windows {
                iconFile.set(project.file("icons/prism.ico"))
                menuGroup = "Prism AI"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "6f1d2c1e-3b8a-4f5e-9c2d-7a4b8e9f0a11"
            }
        }
    }
}
