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
        allWarningsAsErrors.set(providers.gradleProperty("prism.werror").map { it.toBoolean() }.orElse(true))
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
                implementation(libs.kotlinx.serialization.json)

                api(libs.koin.core)
                api(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)

                api(libs.decompose)
                implementation(libs.decompose.extensions.compose)
                api(libs.essenty.lifecycle)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Код, общий для Android и Desktop JVM (JNI llama.cpp, загрузчик моделей, файлы):
        // один каталог компилируется в обе JVM-цели.
        androidMain {
            kotlin.srcDir("src/jvmCommonMain/kotlin")
        }
        jvmMain {
            kotlin.srcDir("src/jvmCommonMain/kotlin")
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.pdfbox.android)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.pdfbox)
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

// Интеграционные тесты llama.cpp: -Pprism.nativeTests=true собирает нативную библиотеку для текущей ОС
// и передаёт тестам путь к ней. Путь к тестовой модели задаётся переменными PRISM_TEST_MODEL / PRISM_TEST_GEMMA.
val nativeTests = providers.gradleProperty("prism.nativeTests").map { it.toBoolean() }.orElse(false)

tasks.withType<Test>().configureEach {
    // В логе CI видно каждый тест (в том числе пропущенные) и ответы моделей из интеграционных тестов.
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // Скриншоты экранов из UI-тестов складываются сюда и выгружаются в CI как артефакт.
    systemProperty("prism.screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    if (nativeTests.get()) {
        dependsOn(":native:assembleNative")
        systemProperty("prism.native.dir", project(":native").layout.buildDirectory.dir("libs").get().asFile.absolutePath)
    }
}
