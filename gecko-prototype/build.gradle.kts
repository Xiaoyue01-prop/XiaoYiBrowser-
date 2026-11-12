import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.summitgecko"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.example.summitgecko"
        minSdk = 36
        targetSdk = 36
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 11
        versionName = "0.16-stable-parity"
    }

    buildTypes {
        release {
            // App dùng cá nhân: ký bằng khóa debug để cài trực tiếp như :app.
            // Không bật minify: GeckoView cần bộ proguard rules riêng.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    // Media3 (cùng bản với :app): MediaSessionService + MediaSession cho media của trang web
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("org.mozilla.geckoview:geckoview:159.0.20261022150525")
}
