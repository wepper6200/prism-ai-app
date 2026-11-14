// Сборка нативной библиотеки (llama.cpp + JNI-мост) для Desktop текущей ОС через CMake.
// Нужны cmake и компилятор C++ (на Windows — Visual Studio Build Tools).
// Android-вариант собирает сам Gradle в модуле :androidApp (externalNativeBuild).
//
//   -Pprism.vulkan=true   — добавить Vulkan-бэкенд (нужен Vulkan SDK);
//   -Pprism.imagegen=true — собрать программу генерации изображений (stable-diffusion.cpp, отдельная CMake-сборка);
//   -Pprism.ccache=true   — кэшировать компиляцию через ccache.

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
val imagegen = providers.gradleProperty("prism.imagegen").map { it.toBoolean() }.orElse(false)
val cmakeBuildDir = layout.buildDirectory.dir("cmake")
val imagegenBuildDir = layout.buildDirectory.dir("cmake-imagegen")

val configureNative = tasks.register<Exec>("configureNative") {
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

val buildNative = tasks.register<Exec>("buildNative") {
    group = "native"
    description = "Собирает нативные библиотеки."
    dependsOn(configureNative)
    // Число потоков ограничено ядрами: «--parallel» без числа в Makefile-генераторе запускает
    // неограниченное число компиляторов и исчерпывает память.
    val jobs = Runtime.getRuntime().availableProcessors().toString()
    commandLine("cmake", "--build", cmakeBuildDir.get().asFile.absolutePath, "--config", "Release", "--parallel", jobs)
}

val configureImageGen = tasks.register<Exec>("configureImageGen") {
    group = "native"
    description = "Конфигурирует CMake-сборку программы генерации изображений (stable-diffusion.cpp)."
    val buildDir = imagegenBuildDir.get().asFile
    val args = mutableListOf(
        "cmake", "-S", file("imagegen").absolutePath, "-B", buildDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DPRISM_IMAGEGEN_VULKAN=${if (vulkan.get()) "ON" else "OFF"}",
    )
    if (ccache.get()) {
        args += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
    }
    commandLine(args)
}

val buildImageGen = tasks.register<Exec>("buildImageGen") {
    group = "native"
    description = "Собирает программу генерации изображений."
    dependsOn(configureImageGen)
    val jobs = Runtime.getRuntime().availableProcessors().toString()
    commandLine(
        "cmake", "--build", imagegenBuildDir.get().asFile.absolutePath, "--config", "Release",
        "--target", "prism_imagegen", "--parallel", jobs,
    )
}

// Библиотеки для запуска и тестов: build/libs.
val musicgenBuildDir = layout.buildDirectory.dir("cmake-musicgen")
val configureMusicGen = tasks.register<Exec>("configureMusicGen") {
    group = "native"
    commandLine(
        "cmake", "-S", file("musicgen").absolutePath, "-B", musicgenBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release", "-DPRISM_MUSICGEN_VULKAN=${if (vulkan.get()) "ON" else "OFF"}",
    )
}
val buildMusicGen = tasks.register<Exec>("buildMusicGen") {
    group = "native"
    dependsOn(configureMusicGen)
    commandLine(
        "cmake", "--build", musicgenBuildDir.get().asFile.absolutePath, "--config", "Release",
        "--target", "prism_musicgen", "--parallel", Runtime.getRuntime().availableProcessors().toString(),
    )
}

val assembleNative = tasks.register<Sync>("assembleNative") {
    group = "native"
    description = "Собирает нативные библиотеки текущей ОС в build/libs."
    dependsOn(buildNative)
    from(cmakeBuildDir.map { it.dir("bin") }) {
        include("*.dll", "*.so", "*.dylib")
    }
    if (imagegen.get()) {
        dependsOn(buildMusicGen)
        from(musicgenBuildDir.map { it.dir("bin") }) { include("prism_musicgen", "prism_musicgen.exe") }
        dependsOn(buildImageGen)
        from(imagegenBuildDir.map { it.dir("bin") }) {
            include("prism_imagegen", "prism_imagegen.exe")
        }
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
val assembleAppResources = tasks.register<Sync>("assembleAppResources") {
    group = "native"
    description = "Раскладывает нативные библиотеки в ресурсы приложения Compose Desktop."
    from(assembleNative)
    into(layout.buildDirectory.dir("app-resources/$platformDir"))
}
