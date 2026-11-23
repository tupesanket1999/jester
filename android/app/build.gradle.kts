plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.tupesanket1999.jester"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.tupesanket1999.jester"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        // ML Kit OCR ships ~11 MB of native code per ABI; every current phone (and Apple-silicon emulators) is arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing reads JESTER_KEYSTORE, JESTER_KEYSTORE_PASSWORD, JESTER_KEY_ALIAS and JESTER_KEY_PASSWORD from the
    // environment or ~/.gradle/gradle.properties. Without them the release APK is built unsigned (never the debug key).
    fun signingProp(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
    val keystorePath = signingProp("JESTER_KEYSTORE")
    val releaseSigning = if (keystorePath != null && file(keystorePath).exists()) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = signingProp("JESTER_KEYSTORE_PASSWORD")
            keyAlias = signingProp("JESTER_KEY_ALIAS")
            keyPassword = signingProp("JESTER_KEY_PASSWORD")
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // On-device OCR for shared screenshots (bundled Latin model, works offline).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests (android.jar only has stubs)
}
