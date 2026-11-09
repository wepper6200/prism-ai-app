// Сборка нативной библиотеки (llama.cpp + JNI-мост) для Desktop текущей ОС через CMake.
// Нужны cmake и компилятор C++ (на Windows — Visual Studio Build Tools).
// Android-вариант собирает сам Gradle в модуле :androidApp (externalNativeBuild).
//
//   -Pprism.vulkan=true  — добавить Vulkan-бэкенд (нужен Vulkan SDK);
//   -Pprism.ccache=true  — кэшировать компиляцию через ccache.

val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("win")
val archName = if (System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"
val platformDir = when {
    isWindows -> "windows-$archName"
    osName.contains("mac") -> "macos-$archName"
    else -> "linux-$archName"
}

val vulkan = providers.gradleProperty("prism.vulkan").map { it.toBoolean() }.orElse(false)
val ccache = providers.gradleProperty("prism.ccache").map { it.toBoolean() }.orElse(false)
val cmakeBuildDir = layout.buildDirectory.dir("cmake")

val configureNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Конфигурирует CMake-сборку llama.cpp и JNI-моста."
    val buildDir = cmakeBuildDir.get().asFile
    val args = mutableListOf(
        "cmake", "-S", projectDir.absolutePath, "-B", buildDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DPRISM_VULKAN=${if (vulkan.get()) "ON" else "OFF"}",
    )
    if (ccache.get()) {
        args += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
    }
    commandLine(args)
}

val buildNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Собирает нативные библиотеки."
    dependsOn(configureNative)
    commandLine("cmake", "--build", cmakeBuildDir.get().asFile.absolutePath, "--config", "Release", "--parallel")
}

// Библиотеки для запуска и тестов: build/libs.
val assembleNative by tasks.registering(Sync::class) {
    group = "native"
    description = "Собирает нативные библиотеки текущей ОС в build/libs."
    dependsOn(buildNative)
    from(cmakeBuildDir.map { it.dir("bin") }) {
        include("*.dll", "*.so", "*.dylib")
    }
    if (isWindows) {
        // Рантайм MSVC кладём рядом: на машине пользователя его может не быть.
        val system32 = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
        listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")
            .map { File(system32, it) }
            .filter { it.isFile }
            .forEach { from(it) }
    }
    into(layout.buildDirectory.dir("libs"))
}

// Ресурсы приложения Compose Desktop: build/app-resources/<ос>-<архитектура>/.
val assembleAppResources by tasks.registering(Sync::class) {
    group = "native"
    description = "Раскладывает нативные библиотеки в ресурсы приложения Compose Desktop."
    from(assembleNative)
    into(layout.buildDirectory.dir("app-resources/$platformDir"))
}
