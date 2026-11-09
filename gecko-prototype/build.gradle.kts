import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.summitgecko"
    compileSdk = 37
    compileSdkExtension = 2

    defaultConfig {
        applicationId = "com.example.summitgecko"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-gecko-prototype"
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
    implementation("org.mozilla.geckoview:geckoview:159.0.20261022150525")
}
