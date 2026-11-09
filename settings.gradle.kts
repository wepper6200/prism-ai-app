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
    }
}

include(":shared")
// Desktop-сборкам Android-приложение не нужно: -Pprism.skipAndroid=true не конфигурирует его (и не требует NDK).
if (providers.gradleProperty("prism.skipAndroid").orNull != "true") {
    include(":androidApp")
}
include(":desktopApp")
include(":native")
