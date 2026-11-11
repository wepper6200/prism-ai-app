rootProject.name = "PrismAI"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // sherpa-onnx (голос) публикует AAR и jar только в релизах GitHub: файл <артефакт>-<версия>.<расширение>.
        ivy {
            name = "sherpaOnnxReleases"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/")
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("com.k2fsa.sherpa-onnx") }
        }
    }
}

include(":shared")
// Desktop-сборкам Android-приложение не нужно: -Pprism.skipAndroid=true не конфигурирует его (и не требует NDK).
if (providers.gradleProperty("prism.skipAndroid").orNull != "true") {
    include(":androidApp")
}
include(":desktopApp")
include(":native")
