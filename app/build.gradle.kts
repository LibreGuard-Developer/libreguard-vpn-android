import java.util.Properties

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

val adiRegistrationProperties = Properties().apply {
    val assetFile = file("src/main/assets/adi-registration.properties")
    val rootFile = rootProject.file("adi-registration.properties")
    val sourceFile = when {
        assetFile.exists() -> assetFile
        rootFile.exists() -> rootFile
        else -> null
    }

    if (sourceFile != null) {
        val content = sourceFile.readText().trim()
        if (content.contains("=")) {
            // It's a properties file format
            load(sourceFile.inputStream())
        } else if (content.isNotEmpty()) {
            // It's a raw fragment file format - put it into the expected key
            setProperty("adi.registration.fragment", content)
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "net.libreguard.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.libreguard.vpn"
        minSdk = 29
        targetSdk = 35
        versionCode = 10810
        versionName = "1.8.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID",
            "\"${localProperties.getProperty("google.webClientId", "")}\"")
        buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID",
            "\"${localProperties.getProperty("google.androidClientId", "")}\"")
        buildConfigField("String", "GOOGLE_PLAY_PRODUCT_ID",
            "\"${localProperties.getProperty("google.playProductId", "libreguard_vpn_monthly")}\"")
        buildConfigField("String", "GOOGLE_PLAY_BACKEND_SUBSCRIPTION_ID",
            "\"${localProperties.getProperty("google.playBackendSubscriptionId", "libreguard_vpn_monthly")}\"")

        // Select variants from ics-openvpn (library has flavorDimensions: implementation, ovpnimpl)
        missingDimensionStrategy("implementation", "skeleton")
        missingDimensionStrategy("ovpnimpl", "ovpn23")

        val adiFragmentRaw = adiRegistrationProperties.getProperty("adi.registration.fragment", "").trim()
        val adiFragment = if (adiFragmentRaw.isBlank() || adiFragmentRaw.startsWith("PASTE") || adiFragmentRaw == "YOUR_ADI_FRAGMENT_FROM_GOOGLE_PLAY_CONSOLE" || adiFragmentRaw == "YOUR_ADI_REGISTRATION_FRAGMENT_HERE") "" else adiFragmentRaw
        if (adiFragment.isNotEmpty()) {
            manifestPlaceholders["adiRegistrationFragment"] = adiFragment
        }
    }

    // Google Play App Signing Configuration
    // The ADI registration fragment is loaded from adi-registration.properties
    // which is gitignored for security. See adi-registration.properties.example for setup.
    signingConfigs {
        create("release") {
            val adiFragmentRaw = adiRegistrationProperties.getProperty("adi.registration.fragment", "").trim()
            // Treat placeholder values as missing
            val adiFragment = if (adiFragmentRaw.isBlank() || adiFragmentRaw.startsWith("PASTE") || adiFragmentRaw == "YOUR_ADI_FRAGMENT_FROM_GOOGLE_PLAY_CONSOLE" || adiFragmentRaw == "YOUR_ADI_REGISTRATION_FRAGMENT_HERE") "" else adiFragmentRaw

            if (adiFragment.isNotEmpty()) {
                // Read keystore details from local.properties for open-source safety
                val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE", "")
                val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD", "")
                val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS", "")
                val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")

                if (releaseStoreFile.isNotEmpty()) {
                    val jksFile = file(releaseStoreFile)
                    storeFile = if (jksFile.isAbsolute) jksFile else rootProject.file(releaseStoreFile)

                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword

                    enableV2Signing = true
                    enableV3Signing = true
                    enableV4Signing = true
                } else {
                    logger.warn("⚠️  RELEASE_STORE_FILE not found in local.properties. Release signing will fail.")
                }
            } else {
                // Fallback: if no ADI fragment, will require manual signing
                logger.warn("⚠️  ADI registration fragment not found or is placeholder in adi-registration.properties")
                logger.warn("   Please follow the setup instructions in adi-registration.properties.example")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Apply signing configuration for Google Play App Signing
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
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
            jniLibs.srcDirs("src/main/jniLibs")
            // Only compile app code under net/; strongSwan sources come from the submodule
            java.setSrcDirs(listOf("src/main/java/net"))
            res.srcDirs("src/main/res")
        }

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

    // QR Code generation for 2FA
    implementation("com.google.zxing:core:3.5.2")

    // Networking
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.logging.interceptor) // For debugging HTTP requests
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.foundation)

    // Restore library dependency on ics-openvpn main module
    implementation(project(":ics-openvpn:main"))
    implementation("androidx.annotation:annotation:1.9.1") // fixes common errors

    // Google Sign-In
    implementation(libs.play.services.auth)

    // Google Play Billing
    implementation(libs.play.billing.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.mockwebserver)

    implementation(libs.androidx.preference)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.org.bouncycastle.bcprov.jdk15on)

    // Add strongSwan Android library module
    implementation(project(":strongswan-android"))

    // Adding Firebase integration for crash reporting and analytics
    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-crashlytics")

}
