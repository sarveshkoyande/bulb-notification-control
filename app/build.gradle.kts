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
// NOTE: must not be named `keyAlias` — inside signingConfigs{} the SigningConfig
// receiver shadows it, so `keyAlias = keyAlias` would assign the config's own null.
val signingKeyAlias = secret("KEY_ALIAS", "bulb")

android {
    namespace = "com.wipro.bulb.control"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wipro.bulb.control"
        minSdk = 23   // Thing App SDK requires 23+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Tuya/Thing App SDK credentials -> BuildConfig, used by ThingHomeSdk.init().
        buildConfigField("String", "TUYA_APP_KEY", "\"${secret("TUYA_APP_KEY")}\"")
        buildConfigField("String", "TUYA_APP_SECRET", "\"${secret("TUYA_APP_SECRET")}\"")

        ndk {
            // Thing SDK ships native libs; keep the common ABIs.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        if (keystoreFile != null && keystorePassword.isNotEmpty()) {
            create("app") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
        jniLibs {
            // Thing SDK modules each bundle libc++_shared.so.
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }
}

// Required by the Thing SDK integration guide.
configurations.all {
    exclude(group = "com.thingclips.smart", module = "thingsmart-modularCampAnno")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    // Thing (Tuya) Smart Life App SDK — per _sdk/7.8.0/thingsmart_home_sdk/dependencies.txt
    implementation("com.alibaba:fastjson:1.1.67.android")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:3.14.9")
    implementation("com.thingclips.smart:thingsmart:7.8.0")

    // App-specific security algorithm library (replaces the old t_s.bmp image).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
