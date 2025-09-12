plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.shadowlinkvpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.shadowlinkvpn"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "../strongswan/libs")
            java.srcDirs("src/main/java")
            res.srcDirs("src/main/res")
        }
    }

    tasks.withType<JavaCompile> {
        exclude("org/strongswan/android/logic/ManagedUserCertificateInstaller.java")
        exclude("org/strongswan/android/logic/ManagedTrustedCertificateInstaller.java")
        exclude("org/strongswan/android/logic/ManagedTrustedCertificateManager.java")
        exclude("org/strongswan/android/logic/ManagedUserCertificateManager.java")
        exclude("org/strongswan/android/data/VpnProfileManagedDataSource.java")
        exclude("org/strongswan/android/data/VpnProfileSqlDataSource.java")
        exclude("org/strongswan/android/data/ManagedTrustedCertificateRepository.java")
        exclude("org/strongswan/android/data/ManagedUserCertificateRepository.java")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Material icons (extended) for visibility icons
    implementation("androidx.compose.material:material-icons-extended")

    // Networking
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.foundation)

    // OpenVPN libraries
    implementation("co.pango:openvpn-aar:5.6.0-RC2")
    implementation("co.pango:core-vpn:5.6.0-RC2")
    implementation("co.pango:bolts-tasks:5.6.0-RC2")
    runtimeOnly("co.pango:core-logger:5.6.0-RC2")
    implementation("co.pango:core-interface:5.6.0-RC2")
    runtimeOnly("co.pango:sdk-interface:5.6.0-RC2")
    implementation("co.pango:core-credentials:5.6.0-RC2")
    implementation("co.pango:sdk-core:5.6.0-RC2")
    runtimeOnly("co.pango:sdk-network-layer:5.6.0-RC2")
    runtimeOnly("co.pango:sdk-daemon:5.6.0-RC2")
    runtimeOnly("co.pango:core-service:5.6.0-RC2")
    implementation("co.pango:sdk:5.6.0-RC2")
    implementation("co.pango:sdk-utils:5.6.0-RC2")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("co.pango:sdk-openvpn:5.6.0-RC2")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.preference)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
}
