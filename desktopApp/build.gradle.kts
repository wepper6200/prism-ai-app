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
            // Пока собирается только портативное приложение (createDistributable). Форматы установщика
            // (MSI/EXE) не заданы: иначе плагин требует WiX, а он скачивается с нестабильного GitHub Releases.
            packageName = "PrismAI"
            packageVersion = libs.versions.prism.versionName.get()
            description = "Prism AI — офлайн ИИ-ассистент"
            vendor = "Prism AI"
            // java.sql — для SQLite (SQLDelight), java.prefs — для сохранения настроек.
            modules("java.sql", "java.prefs")

            windows {
                iconFile.set(project.file("icons/prism.ico"))
            }
        }
    }
}
