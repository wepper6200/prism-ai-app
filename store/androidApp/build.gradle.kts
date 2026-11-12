import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ai.prism.store.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ai.prism.store"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.store.versionCode.get().toInt()
        versionName = libs.versions.store.versionName.get()
    }

    // Тот же постоянный ключ, что у Prism AI (секреты PRISM_KEYSTORE_* в CI).
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(providers.gradleProperty("prism.werror").map { it.toBoolean() }.orElse(true))
    }
}

dependencies {
    implementation(project(":store:shared"))
    implementation(libs.androidx.activity.compose)
}
