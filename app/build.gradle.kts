plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.webedge"
    compileSdk = 36

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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("com.github.Dimezis:BlurView:version-3.2.0")
    // Coroutines: xử lý mạng không tạo Thread thô
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // lifecycleScope: tự hủy tác vụ khi đóng app
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Media3 (bản ổn định mới nhất): MediaSessionService + MediaSession cho media của trang web
    implementation("androidx.media3:media3-session:1.11.1")
}
