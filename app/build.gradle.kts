import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing lives in keystore.properties (gitignored, not committed) so the actual
// passwords never touch version control. Falls back to debug signing when that file is
// missing - e.g. a fresh checkout on another machine - so `assembleRelease` still works
// locally for the non-debuggable-flag testing this build type was originally added for.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// Sideload APKs (attached to GitHub releases by CI) are signed with their own key, never the
// Play upload key above. CI decodes it from secrets and passes these variables; without them
// the sideload build falls back to debug signing so it still builds anywhere.
val sideloadKeystoreFile: String? = System.getenv("SIDELOAD_KEYSTORE_FILE")

android {
    namespace = "com.evcompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.parkfour.evcompanion"
        minSdk = 26
        targetSdk = 36
        versionCode = 47
        versionName = "0.12.26"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
        if (sideloadKeystoreFile != null) {
            create("sideload") {
                storeFile = file(sideloadKeystoreFile)
                storePassword = System.getenv("SIDELOAD_STORE_PASSWORD")
                keyAlias = System.getenv("SIDELOAD_KEY_ALIAS")
                keyPassword = System.getenv("SIDELOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Installs beside the Play build (different signing key) instead of replacing it.
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "EV Companion (Debug)")
        }
        // A release build for direct download: its own application ID so it installs beside
        // the Play and debug builds (each is signed with a different key).
        create("sideload") {
            initWith(getByName("release"))
            applicationIdSuffix = ".sideload"
            resValue("string", "app_name", "EV Companion (Sideload)")
            signingConfig = signingConfigs.getByName(if (sideloadKeystoreFile != null) "sideload" else "debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.car.app)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json ships in the Android platform itself (no runtime dependency needed), but the
    // stub jar used for plain JVM unit tests throws on every call - this swaps in the real
    // reference implementation for tests only, so DashboardConfigParserTest can actually parse.
    testImplementation("org.json:json:20231013")
}
