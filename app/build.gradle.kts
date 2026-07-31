import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

/**
 * Signing / Tuya credentials.
 *
 * Locally  : read from _secrets/signing.properties (gitignored).
 * In CI    : read from environment variables fed by GitHub Secrets.
 *
 * The signing key must be STABLE — the Tuya App SDK authenticates against the
 * SHA256 of the signing certificate registered on iot.tuya.com. A per-build
 * debug key would change every run and fail SDK auth.
 */
val localProps = Properties().apply {
    val f = rootProject.file("_secrets/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String, default: String = ""): String =
    System.getenv(name) ?: localProps.getProperty(name) ?: default

val keystoreFile: File? = rootProject.file("_secrets/bulb.keystore").takeIf { it.exists() }
val keystorePassword = secret("KEYSTORE_PASSWORD")
val keyAlias = secret("KEY_ALIAS", "bulb")

android {
    namespace = "com.wipro.bulb.control"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wipro.bulb.control"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Tuya App SDK credentials, injected into AndroidManifest.
        manifestPlaceholders["TUYA_APP_KEY"] = secret("TUYA_APP_KEY")
        manifestPlaceholders["TUYA_APP_SECRET"] = secret("TUYA_APP_SECRET")
    }

    signingConfigs {
        if (keystoreFile != null && keystorePassword.isNotEmpty()) {
            create("app") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        val appSigning = signingConfigs.findByName("app")
        debug {
            if (appSigning != null) signingConfig = appSigning
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (appSigning != null) signingConfig = appSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
}
