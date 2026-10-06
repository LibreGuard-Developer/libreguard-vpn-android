// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
    id("com.google.firebase.crashlytics") version "3.0.7" apply false
}

// Keep generated StrongSwan output outside the nested checkout.
project(":strongswan-android") {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("strongswan-android"))
}

// Build OpenVPN for the same ARM architectures packaged by the host app.
// The fork retains its default ABI support when built independently.
project(":ics-openvpn:main") {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            defaultConfig.ndk.abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }
}
