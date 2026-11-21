plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.serabin1.tydl"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.serabin1.tydl"
        minSdk = 29
        targetSdk = 37
        // CI derives both from the v* release tag (major*10000 + minor*100 + patch),
        // so every published APK installs as an update over the previous one.
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("0.1.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key exists only in CI secrets. Every published APK must carry
    // the same signature or Android refuses to install it as an update, so it
    // can't be the debug key, which is generated per machine.
    val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // Locally, without the release key, sign with the debug key so the
            // optimised build is still installable on a connected phone.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // Full R8 with AGP 9's default keep rules. packageScope is
            // experimental and broke Kotlin stdlib reflection in v0.1.0.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}