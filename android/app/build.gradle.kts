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

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
