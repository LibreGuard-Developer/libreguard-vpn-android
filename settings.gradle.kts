pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Add JitPack to resolve dependencies like com.github.PhilJay:MPAndroidChart
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "LibreGuardVPN"
include(":app")
include(":ics-openvpn:main")
project(":ics-openvpn:main").projectDir = File(rootDir, "ics-openvpn/main")
