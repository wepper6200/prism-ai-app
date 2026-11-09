import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.sqldelight)
}

/**
 * Генерирует `ai.prism.core.BuildInfo` из version catalog, чтобы версия приложения
 * задавалась в одном месте (libs.versions.toml) для всех платформ.
 */
abstract class GenerateBuildInfoTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDir.file("ai/prism/core/BuildInfo.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package ai.prism.core
            |
            |/** Сгенерировано Gradle из gradle/libs.versions.toml. Не редактировать вручную. */
            |object BuildInfo {
            |    const val VERSION_NAME: String = "${versionName.get()}"
            |}
            |""".trimMargin(),
        )
    }
}

val generateBuildInfo = tasks.register<GenerateBuildInfoTask>("generateBuildInfo") {
    versionName.set(libs.versions.prism.versionName)
    outputDir.set(layout.buildDirectory.dir("generated/buildInfo/kotlin"))
}

kotlin {
    android {
        namespace = "ai.prism.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources {
            enable = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo.flatMap { it.outputDir })
            dependencies {
                api(libs.compose.runtime)
                api(libs.compose.foundation)
                api(libs.compose.ui)
                api(libs.compose.material3)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.components.resources)

                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)

                api(libs.koin.core)
                api(libs.sqldelight.runtime)

                api(libs.decompose)
                implementation(libs.decompose.extensions.compose)
                api(libs.essenty.lifecycle)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.sqldelight.sqlite.driver)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    packageOfResClass = "ai.prism.resources"
    publicResClass = false
}

sqldelight {
    databases {
        create("PrismDatabase") {
            packageName.set("ai.prism.data.db")
        }
    }
}

tasks.withType<Test>().configureEach {
    // Скриншоты экранов из UI-тестов складываются сюда и выгружаются в CI как артефакт.
    systemProperty("prism.screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
