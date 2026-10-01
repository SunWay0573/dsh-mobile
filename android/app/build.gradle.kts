plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sunway0573.dshmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sunway0573.dshmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // WebView is what renders the conversation: DSH's own web client is loaded
    // from the host rather than bundled, so the transcript, tool-call cards and
    // composer stay identical to the desktop with no reimplementation to
    // maintain. AndroidX WebKit is not used for that; it is here for
    // WebViewCompat feature detection.
    implementation(libs.androidx.webkit)

    // The app holds a session cookie for a machine that can run arbitrary code.
    // A screen lock on the phone is the last line of defence if the phone is
    // handed to someone unlocked.
    implementation(libs.androidx.biometric)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)

    // On-device tests. The WebView behaviour that WP1 changed -- a late
    // onPageFinished erasing a failure, a changed URL not navigating -- cannot
    // be observed from the JVM at all, because there is no WebView there.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    // Compose UI assertions: the screens under test are Compose, so Espresso's
    // view matchers cannot see them.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // Supplies the empty activity the compose test rule launches into.
    debugImplementation(libs.androidx.ui.test.manifest)
}
