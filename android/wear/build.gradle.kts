// P3 B9: the Wear OS companion. A Tile and a complication showing the next flight, fed by the phone
// over the Wearable Data Layer (`/next_flight`, written by :app's NextFlightPublisher).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Kotlin package and R class. The applicationId differs on purpose, see below.
    namespace = "app.flytrack.wear"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        // The Data Layer only connects apps with the same package name and the same signing key, and
        // Google Play pairs a phone app with its watch app by package name. So the watch app ships as
        // "app.flytrack" (same listing, Wear OS form factor), not "app.flytrack.wear".
        applicationId = "app.flytrack"
        // Wear OS 3+ (API 30): every current watch, and what Tiles and the Data Layer need today.
        minSdk = 30
        targetSdk = 36
        // Same package as the phone app, so the codes must never collide: watch builds live in the 1xxxxx range.
        versionCode = 100_001
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.complications.data.source.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
