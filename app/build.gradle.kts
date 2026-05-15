import org.gradle.api.GradleException
import java.util.Base64

fun env(name: String): String? = providers.environmentVariable(name).orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

fun envOrDefault(name: String, defaultValue: String): String = env(name) ?: defaultValue

fun isPlaceholderAdiFragment(value: String?): Boolean {
    val candidate = value?.trim().orEmpty()
    return candidate.isBlank() ||
        candidate.startsWith("PASTE") ||
        candidate == "YOUR_ADI_FRAGMENT_FROM_GOOGLE_PLAY_CONSOLE" ||
        candidate == "YOUR_ADI_REGISTRATION_FRAGMENT_HERE"
}

val googleWebClientId = env("GOOGLE_WEB_CLIENT_ID").orEmpty()
val googleAndroidClientId = env("GOOGLE_ANDROID_CLIENT_ID").orEmpty()
val googlePlayProductId = envOrDefault("GOOGLE_PLAY_PRODUCT_ID", "libreguard_vpn")
val googlePlayBackendSubscriptionId = envOrDefault(
    "GOOGLE_PLAY_BACKEND_SUBSCRIPTION_ID",
    "libreguard_vpn"
)
val adiRegistrationFragment = env("ADI_REGISTRATION_FRAGMENT")
    ?.takeUnless(::isPlaceholderAdiFragment)
    .orEmpty()
val releaseStoreFileEnv = env("RELEASE_STORE_FILE")
val releaseStorePasswordEnv = env("RELEASE_STORE_PASSWORD")
val releaseKeyAliasEnv = env("RELEASE_KEY_ALIAS")
val releaseKeyPasswordEnv = env("RELEASE_KEY_PASSWORD")
val googleServicesJsonEnv = env("GOOGLE_SERVICES_JSON")
val googleServicesJsonB64Env = env("GOOGLE_SERVICES_JSON_B64")
val googleServicesJsonFile = layout.projectDirectory.file("google-services.json").asFile

val generateGoogleServicesJson by tasks.registering {
    inputs.property("googleServicesJson", googleServicesJsonEnv ?: "")
    inputs.property("googleServicesJsonB64", googleServicesJsonB64Env ?: "")
    outputs.file(googleServicesJsonFile)

    doLast {
        val jsonContent = when {
            !googleServicesJsonB64Env.isNullOrBlank() -> {
                try {
                    String(Base64.getDecoder().decode(googleServicesJsonB64Env), Charsets.UTF_8)
                } catch (error: IllegalArgumentException) {
                    throw GradleException(
                        "GOOGLE_SERVICES_JSON_B64 is not valid Base64.",
                        error
                    )
                }
            }

            !googleServicesJsonEnv.isNullOrBlank() -> googleServicesJsonEnv
            else -> throw GradleException(
                "Missing Firebase configuration. Set GOOGLE_SERVICES_JSON_B64 " +
                    "(recommended) or GOOGLE_SERVICES_JSON."
            )
        }.trim()

        if (!jsonContent.startsWith("{")) {
            throw GradleException(
                "The Firebase configuration provided via environment variables is not valid JSON."
            )
        }

        googleServicesJsonFile.parentFile.mkdirs()
        googleServicesJsonFile.writeText(jsonContent + System.lineSeparator())
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

tasks.matching { it.name.matches(Regex("process.+GoogleServices")) }.configureEach {
    dependsOn(generateGoogleServicesJson)
}

tasks.matching { it.name == "clean" }.configureEach {
    doFirst {
        if (googleServicesJsonFile.exists()) {
            googleServicesJsonFile.delete()
        }
    }
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

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", "\"$googleAndroidClientId\"")
        buildConfigField("String", "GOOGLE_PLAY_PRODUCT_ID", "\"$googlePlayProductId\"")
        buildConfigField(
            "String",
            "GOOGLE_PLAY_BACKEND_SUBSCRIPTION_ID",
            "\"$googlePlayBackendSubscriptionId\""
        )

        // Select variants from ics-openvpn (library has flavorDimensions: implementation, ovpnimpl)
        missingDimensionStrategy("implementation", "skeleton")
        missingDimensionStrategy("ovpnimpl", "ovpn23")

        if (adiRegistrationFragment.isNotEmpty()) {
            manifestPlaceholders["adiRegistrationFragment"] = adiRegistrationFragment
        }
    }

    // Google Play App Signing Configuration
    // Sensitive values are read from environment variables so they do not live in VCS.
    signingConfigs {
        create("release") {
            if (releaseStoreFileEnv != null) {
                val jksFile = file(releaseStoreFileEnv)
                storeFile = if (jksFile.isAbsolute) jksFile else rootProject.file(releaseStoreFileEnv)

                storePassword = releaseStorePasswordEnv
                keyAlias = releaseKeyAliasEnv
                keyPassword = releaseKeyPasswordEnv

                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            } else {
                logger.warn("⚠️  RELEASE_STORE_FILE environment variable not set. Release signing will fail.")
            }

            if (adiRegistrationFragment.isEmpty()) {
                logger.warn("⚠️  ADI_REGISTRATION_FRAGMENT environment variable not set or still uses a placeholder.")
            } else {
                logger.lifecycle("ADI registration fragment loaded from environment variable.")
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
