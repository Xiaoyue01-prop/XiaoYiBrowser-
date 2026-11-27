import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val googleWebClientId = providers.gradleProperty("summitGoogleWebClientId")
    .orElse(providers.environmentVariable("SUMMIT_GOOGLE_WEB_CLIENT_ID").filter { it.isNotBlank() })
    .orElse("630194752242-s02r0s4kp7dl0uktp0iprkgac4i50nf4.apps.googleusercontent.com")
    .get()
    .trim()
require(googleWebClientId.isEmpty() || googleWebClientId.matches(Regex("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))) {
    "Set summitGoogleWebClientId to the Google OAuth Web Client ID."
}

abstract class GenerateReleaseEdgeScript : Exec() {
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty
}

val minifierDir = rootProject.layout.projectDirectory.dir("tools/edge-release")
val installEdgeMinifier = tasks.register<Exec>("installEdgeMinifier") {
    workingDir(minifierDir.asFile)
    inputs.files(minifierDir.file("package.json"), minifierDir.file("package-lock.json"))
    outputs.dir(minifierDir.dir("node_modules"))
    val npm = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
    commandLine(npm, "ci", "--ignore-scripts", "--no-audit", "--no-fund")
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val generate = tasks.register<GenerateReleaseEdgeScript>("generate${variant.name.replaceFirstChar { it.uppercase() }}EdgeScript") {
        dependsOn(installEdgeMinifier)
        val template = layout.projectDirectory.file("src/main/assets/edge_script.js")
        val script = minifierDir.file("minify.cjs")
        inputs.files(template, script, minifierDir.file("package-lock.json"))
        assetsDir.set(layout.buildDirectory.dir("generated/edge/${variant.name}"))
        // AGP supplies the final output directory when registering this source.
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(script.asFile.absolutePath, template.asFile.absolutePath,
                assetsDir.file("edge_script.js").get().asFile.absolutePath)
        })
        executable = "node"
    }
    variant.sources.assets?.addGeneratedSourceDirectory(generate, GenerateReleaseEdgeScript::assetsDir)
}

android {
    namespace = "com.example.webedge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.webedge"
        minSdk = 36
        targetSdk = 36
        testInstrumentationRunner = "com.example.webedge.SecurityInstrumentation"
        versionCode = 3
        versionName = "1.1"
        resValue("string", "google_web_client_id", googleWebClientId)
    }

    buildFeatures {
        resValues = true
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
    // API 36+ already provides the native Credential Manager.
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    // Google Identity Services: authorization for the app-private Drive scope.
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    // Credential Manager's biometric library still requests Fragment 1.2.5.
    implementation("androidx.fragment:fragment:1.8.9")
    // Jetpack PiP: tự lo sourceRectHint, đổi cỡ liền mạch cho video, tự vào PiP khi rời app (alpha03 ra 07/2026)
    implementation("androidx.core:core-pip:1.0.0-alpha03")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("com.github.Dimezis:BlurView:version-3.2.0")
    // Coroutines: xử lý mạng không tạo Thread thô
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // lifecycleScope: tự hủy tác vụ khi đóng app
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    // Media3 (bản ổn định mới nhất): MediaSessionService + MediaSession cho media của trang web
    implementation("androidx.media3:media3-session:1.11.1")
    // WebViewCompat.addDocumentStartJavaScript: chạy script ở mọi khung của trang
    implementation("androidx.webkit:webkit:1.18.0")
}
