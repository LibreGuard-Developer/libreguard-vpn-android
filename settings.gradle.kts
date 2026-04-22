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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
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
// strongswan is present as a git submodule at strongswan/, but not included as a Gradle module
// Include strongSwan Android library module as :strongswan-android
include(":strongswan-android")
project(":strongswan-android").projectDir = File(rootDir, "strongswan/src/frontends/android/app")
