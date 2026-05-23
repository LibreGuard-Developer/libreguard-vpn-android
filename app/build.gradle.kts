import org.gradle.api.GradleException
import org.gradle.api.tasks.PathSensitivity
import java.util.Base64

fun env(name: String): String? = providers.environmentVariable(name).orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

fun envOrDefault(name: String, defaultValue: String): String = env(name) ?: defaultValue

fun envBoolean(name: String, defaultValue: Boolean): Boolean {
    return when (env(name)?.lowercase()) {
        null -> defaultValue
        "1", "true", "yes", "y", "on" -> true
        "0", "false", "no", "n", "off" -> false
        else -> throw GradleException(
            "Environment variable $name must be a boolean value (true/false, 1/0, yes/no, on/off)."
        )
    }
}

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
val expectedAppSigningSha256 = env("APP_SIGNING_SHA256").orEmpty()
val appSigningSha256Allowlist = env("APP_SIGNING_SHA256_ALLOWLIST")
    ?.takeIf { it.isNotBlank() }
    ?: expectedAppSigningSha256
val enableReleaseSigningEnforcement = envBoolean("ENABLE_RELEASE_SIGNING_ENFORCEMENT", false)
val adiRegistrationFragment = env("ADI_REGISTRATION_FRAGMENT")
    ?.takeUnless(::isPlaceholderAdiFragment)
    .orEmpty()
val adiRegistrationPropertiesEnv = env("ADI_REGISTRATION_PROPERTIES")
val releaseStoreFileEnv = env("RELEASE_STORE_FILE")
val releaseStorePasswordEnv = env("RELEASE_STORE_PASSWORD")
val releaseKeyAliasEnv = env("RELEASE_KEY_ALIAS")
val releaseKeyPasswordEnv = env("RELEASE_KEY_PASSWORD")
val googleServicesJsonEnv = env("GOOGLE_SERVICES_JSON")
val googleServicesJsonB64Env = env("GOOGLE_SERVICES_JSON_B64")
val googleServicesJsonFile = layout.projectDirectory.file("google-services.json").asFile
val adiRegistrationPropertiesLocalFile = rootProject.file("adi-registration.properties")
val adiRegistrationPropertiesLocalFileExists = adiRegistrationPropertiesLocalFile.exists()
val generatedAdiRegistrationFile = layout.buildDirectory.file("generated/assets/adi/main/adi-registration.properties")

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

val generateAdiRegistrationProperties by tasks.registering {
    inputs.property("adiRegistrationPropertiesEnv", adiRegistrationPropertiesEnv ?: "")
    inputs.property("adiRegistrationFragment", adiRegistrationFragment)
    inputs.property("adiRegistrationPropertiesLocalFileExists", adiRegistrationPropertiesLocalFileExists)
    if (adiRegistrationPropertiesLocalFileExists) {
        inputs.file(adiRegistrationPropertiesLocalFile)
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    outputs.file(generatedAdiRegistrationFile)

    doLast {
        val localFileContent = if (adiRegistrationPropertiesLocalFile.exists()) {
            adiRegistrationPropertiesLocalFile.readText(Charsets.UTF_8).trim()
        } else {
            ""
        }
        val fileContent = when {
            !adiRegistrationPropertiesEnv.isNullOrBlank() -> adiRegistrationPropertiesEnv.trim()
            localFileContent.isNotBlank() -> localFileContent
            adiRegistrationFragment.isNotEmpty() -> adiRegistrationFragment
            else -> "# Placeholder generated at build time.\n# Set ADI_REGISTRATION_PROPERTIES, or provide /adi-registration.properties locally."
        }

        val outputFile = generatedAdiRegistrationFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(fileContent + System.lineSeparator(), Charsets.UTF_8)
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

tasks.matching { it.name.matches(Regex("merge.+Assets")) }.configureEach {
    dependsOn(generateAdiRegistrationProperties)
}

tasks.matching { it.name.matches(Regex("generate.+Lint.*Model")) }.configureEach {
    dependsOn(generateAdiRegistrationProperties)
}

tasks.matching { it.name.contains("lintVital", ignoreCase = true) }.configureEach {
    dependsOn(generateAdiRegistrationProperties)
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
        versionCode = 10880
        versionName = "1.8.8"

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
        buildConfigField("String", "APP_SIGNING_SHA256", "\"$expectedAppSigningSha256\"")
        buildConfigField("String", "APP_SIGNING_SHA256_ALLOWLIST", "\"$appSigningSha256Allowlist\"")
        buildConfigField(
            "boolean",
            "ENABLE_RELEASE_SIGNING_ENFORCEMENT",
            enableReleaseSigningEnforcement.toString()
        )
        buildConfigField(
            "String",
            "GOOGLE_PLAY_BACKEND_SUBSCRIPTION_ID",
            "\"$googlePlayBackendSubscriptionId\""
        )

        // Select variants from ics-openvpn (library has flavorDimensions: implementation, ovpnimpl)
        missingDimensionStrategy("implementation", "skeleton")
        missingDimensionStrategy("ovpnimpl", "ovpn23")
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

            if (adiRegistrationPropertiesEnv.isNullOrBlank() && !adiRegistrationPropertiesLocalFile.exists() && adiRegistrationFragment.isEmpty()) {
                logger.warn("⚠️  No ADI registration value supplied. A placeholder asset will be generated.")
            } else {
                logger.lifecycle("ADI registration asset source configured (environment variable, local file, or fragment).")
            }

            if (enableReleaseSigningEnforcement) {
                if (appSigningSha256Allowlist.isBlank()) {
                    logger.warn("⚠️  ENABLE_RELEASE_SIGNING_ENFORCEMENT is enabled but APP_SIGNING_SHA256_ALLOWLIST is empty. Runtime will fall back to warn-only mode.")
                } else {
                    logger.lifecycle("Release signing enforcement enabled with configured signing digest allowlist.")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
            assets.srcDirs(
                "src/main/assets",
                layout.buildDirectory.dir("generated/assets/adi/main").get().asFile
            )
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
