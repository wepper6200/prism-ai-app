import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.desktop.java.get()))
        allWarningsAsErrors.set(providers.gradleProperty("prism.werror").map { it.toBoolean() }.orElse(true))
    }
}

dependencies {
    implementation(project(":store:shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "ai.prism.store.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dprism.store.version=${libs.versions.store.versionName.get()}")

        nativeDistributions {
            packageName = "PrismStore"
            packageVersion = libs.versions.store.versionName.get()
            description = "Prism Store — приложения Prism"
            vendor = "Prism AI"
            windows {
                iconFile.set(rootProject.file("desktopApp/icons/prism.ico"))
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.desktop.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.desktop.java.get())
}
