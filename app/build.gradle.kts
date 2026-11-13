import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.webedge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.webedge"
        minSdk = 36
        targetSdk = 36
        versionCode = 3
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // App dùng cá nhân: ký bằng khóa debug để cài được trực tiếp, chưa cần keystore riêng.
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
    implementation("androidx.activity:activity-ktx:1.13.0")
    // Jetpack PiP: tự lo sourceRectHint, đổi cỡ liền mạch cho video, tự vào PiP khi rời app (alpha03 ra 07/2026)
    implementation("androidx.core:core-pip:1.0.0-alpha03")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("com.github.Dimezis:BlurView:version-3.2.0")
    // Coroutines: xử lý mạng không tạo Thread thô
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // lifecycleScope: tự hủy tác vụ khi đóng app
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // Media3 (bản ổn định mới nhất): MediaSessionService + MediaSession cho media của trang web
    implementation("androidx.media3:media3-session:1.11.1")
    // WebViewCompat.addDocumentStartJavaScript: chạy script ở mọi khung của trang
    implementation("androidx.webkit:webkit:1.18.0")
}
