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
    }
    buildTypes {
        // Пользователям раздаётся релизная сборка: не отладочная (Play Защита строже к отладочным APK,
        // которые ставят приложения). Без секретов подписи — отладочным ключом этой машины.
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("prism") ?: signingConfigs.getByName("debug")
        }
        getByName("debug") {
            signingConfigs.findByName("prism")?.let { signingConfig = it }
        }
    }

    lint {
        // lintVital релиза останавливает сборку на правилах Google Play (доступ ко всем файлам, установка пакетов);
        // приложения раздаются вне Play, а предупреждения компилятора и так считаются ошибками.
        checkReleaseBuilds = false
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
// Результат упаковывается как lib*.so: только такие файлы Android устанавливает в каталог нативных библиотек
// и разрешает запускать. Две сборки: базовая ARMv8.2 (dotprod, fp16) и с i8mm (матричные инструкции int8
// процессоров последних лет — быстрее на рисовании и видео); приложение выбирает по /proc/cpuinfo.
val imagegenJniLibs = layout.buildDirectory.dir("imagegen-jniLibs")
val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
val ndkDirectory = androidComponents.sdkComponents.ndkDirectory
val cmakeVersion = libs.versions.android.cmake.get()
val minSdkVersion = libs.versions.android.minSdk.get()
val useCcache = providers.gradleProperty("prism.ccache").map { it.toBoolean() }.getOrElse(false)

data class ImageGenVariant(val suffix: String, val armArch: String, val libName: String)

val imagegenVariants = listOf(
    ImageGenVariant("", "armv8.2-a+dotprod+fp16", "prism_imagegen"),
    ImageGenVariant("I8mm", "armv8.2-a+dotprod+fp16+i8mm", "prism_imagegen_i8mm"),
)

val buildImageGenTasks = imagegenVariants.map { variant ->
    val buildDir = layout.buildDirectory.dir("cmake-imagegen${variant.suffix.lowercase()}")
    val configure = tasks.register<Exec>("configureImageGen${variant.suffix}Android") {
        group = "native"
        description = "Конфигурирует программу генерации изображений для Android (${variant.armArch})."
        val cmakeBin = sdkDirectory.map { it.dir("cmake/$cmakeVersion/bin") }
        val args = listOf(
            cmakeBin.get().file("cmake").asFile.absolutePath,
            "-S", rootProject.file("native/imagegen").absolutePath,
            "-B", buildDir.get().asFile.absolutePath,
            "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${cmakeBin.get().file("ninja").asFile.absolutePath}",
            "-DCMAKE_TOOLCHAIN_FILE=${ndkDirectory.get().file("build/cmake/android.toolchain.cmake").asFile.absolutePath}",
            "-DANDROID_ABI=arm64-v8a",
            "-DANDROID_PLATFORM=android-$minSdkVersion",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DPRISM_IMAGEGEN_ARM_ARCH=${variant.armArch}",
            "-DPRISM_IMAGEGEN_NAME=${variant.libName}",
        ) + if (useCcache) listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache") else emptyList()
        commandLine(args)
    }
    tasks.register<Exec>("buildImageGen${variant.suffix}Android") {
        group = "native"
        description = "Собирает программу генерации изображений для Android (${variant.armArch})."
        dependsOn(configure)
        val cmake = sdkDirectory.map { it.file("cmake/$cmakeVersion/bin/cmake") }
        val jobs = Runtime.getRuntime().availableProcessors().toString()
        commandLine(
            cmake.get().asFile.absolutePath, "--build", buildDir.get().asFile.absolutePath,
            "--target", "prism_imagegen", "--parallel", jobs,
        )
    }
}

val syncImageGen = tasks.register<Sync>("syncImageGenAndroid") {
    group = "native"
    dependsOn(buildImageGenTasks)
    imagegenVariants.forEach { variant ->
        from(layout.buildDirectory.dir("cmake-imagegen${variant.suffix.lowercase()}")) { include("lib${variant.libName}.so") }
    }
    into(imagegenJniLibs.map { it.dir("arm64-v8a") })
}

android.sourceSets.getByName("main").jniLibs.directories.add(imagegenJniLibs.get().asFile.absolutePath)

val musicgenBuildDir = layout.buildDirectory.dir("cmake-musicgen")
val configureMusicGen = tasks.register<Exec>("configureMusicGenAndroid") {
    val cmakeBin = sdkDirectory.map { it.dir("cmake/$cmakeVersion/bin") }.get()
    commandLine(
        cmakeBin.file("cmake").asFile.absolutePath,
        "-S", rootProject.file("native/musicgen").absolutePath,
        "-B", musicgenBuildDir.get().asFile.absolutePath, "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=${cmakeBin.file("ninja").asFile.absolutePath}",
        "-DCMAKE_TOOLCHAIN_FILE=${ndkDirectory.get().file("build/cmake/android.toolchain.cmake").asFile.absolutePath}",
        "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-$minSdkVersion", "-DCMAKE_BUILD_TYPE=Release",
    )
}
val buildMusicGen = tasks.register<Exec>("buildMusicGenAndroid") {
    dependsOn(configureMusicGen)
    commandLine(
        sdkDirectory.get().file("cmake/$cmakeVersion/bin/cmake").asFile.absolutePath,
        "--build", musicgenBuildDir.get().asFile.absolutePath, "--target", "prism_musicgen",
        "--parallel", Runtime.getRuntime().availableProcessors().toString(),
    )
}
val musicgenJniLibs = layout.buildDirectory.dir("musicgen-jniLibs")
val syncMusicGen = tasks.register<Sync>("syncMusicGenAndroid") {
    dependsOn(buildMusicGen)
    from(musicgenBuildDir) { include("libprism_musicgen.so") }
    into(musicgenJniLibs.map { it.dir("arm64-v8a") })
}
android.sourceSets.getByName("main").jniLibs.directories.add(musicgenJniLibs.get().asFile.absolutePath)
tasks.named("preBuild") { dependsOn(syncMusicGen) }
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
