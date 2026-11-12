import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ai.prism.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        applicationId = "ai.prism.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.prism.versionCode.get().toInt()
        versionName = libs.versions.prism.versionName.get()

        ndk {
            // Целевая платформа продукта — только arm64-v8a.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // llama.cpp всегда собирается с оптимизациями, даже в debug-сборке приложения.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                if (providers.gradleProperty("prism.ccache").map { it.toBoolean() }.getOrElse(false)) {
                    arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
                }
            }
        }
    }

    // Постоянный ключ подписи: с ним новая версия ставится поверх старой и модели остаются на телефоне.
    // CI берёт его из секретов репозитория (PRISM_KEYSTORE_*); без ключа сборка подписывается отладочным ключом.
    val keystoreFile = providers.environmentVariable("PRISM_KEYSTORE_FILE").orNull?.let(::file)?.takeIf { it.isFile }
    if (keystoreFile != null) {
        signingConfigs {
            create("prism") {
                storeFile = keystoreFile
                storePassword = providers.environmentVariable("PRISM_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("PRISM_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("PRISM_KEY_PASSWORD").get()
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("prism") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = libs.versions.android.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Бэкенды ggml загружаются по пути из nativeLibraryDir, поэтому .so распаковываются при установке.
            useLegacyPackaging = true
        }
    }
}

// Программа генерации изображений (stable-diffusion.cpp) — отдельная CMake-сборка тулчейном NDK.
// Результат упаковывается как libprism_imagegen.so: только файлы lib*.so Android устанавливает
// в каталог нативных библиотек и разрешает запускать.
val imagegenBuildDir = layout.buildDirectory.dir("cmake-imagegen")
val imagegenJniLibs = layout.buildDirectory.dir("imagegen-jniLibs")
val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
val ndkDirectory = androidComponents.sdkComponents.ndkDirectory
val cmakeVersion = libs.versions.android.cmake.get()
val minSdkVersion = libs.versions.android.minSdk.get()
val useCcache = providers.gradleProperty("prism.ccache").map { it.toBoolean() }.getOrElse(false)

val configureImageGen = tasks.register<Exec>("configureImageGenAndroid") {
    group = "native"
    description = "Конфигурирует сборку программы генерации изображений для Android (arm64-v8a)."
    val cmakeBin = sdkDirectory.map { it.dir("cmake/$cmakeVersion/bin") }
    val args = listOf(
        cmakeBin.get().file("cmake").asFile.absolutePath,
        "-S", rootProject.file("native/imagegen").absolutePath,
        "-B", imagegenBuildDir.get().asFile.absolutePath,
        "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=${cmakeBin.get().file("ninja").asFile.absolutePath}",
        "-DCMAKE_TOOLCHAIN_FILE=${ndkDirectory.get().file("build/cmake/android.toolchain.cmake").asFile.absolutePath}",
        "-DANDROID_ABI=arm64-v8a",
        "-DANDROID_PLATFORM=android-$minSdkVersion",
        "-DCMAKE_BUILD_TYPE=Release",
    ) + if (useCcache) listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache") else emptyList()
    commandLine(args)
}

val buildImageGen = tasks.register<Exec>("buildImageGenAndroid") {
    group = "native"
    description = "Собирает программу генерации изображений для Android."
    dependsOn(configureImageGen)
    val cmake = sdkDirectory.map { it.file("cmake/$cmakeVersion/bin/cmake") }
    val jobs = Runtime.getRuntime().availableProcessors().toString()
    commandLine(
        cmake.get().asFile.absolutePath, "--build", imagegenBuildDir.get().asFile.absolutePath,
        "--target", "prism_imagegen", "--parallel", jobs,
    )
}

val syncImageGen = tasks.register<Sync>("syncImageGenAndroid") {
    group = "native"
    dependsOn(buildImageGen)
    from(imagegenBuildDir) { include("libprism_imagegen.so") }
    into(imagegenJniLibs.map { it.dir("arm64-v8a") })
}

android.sourceSets.getByName("main").jniLibs.srcDir(imagegenJniLibs.get().asFile)
tasks.named("preBuild") { dependsOn(syncImageGen) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(providers.gradleProperty("prism.werror").map { it.toBoolean() }.orElse(true))
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
