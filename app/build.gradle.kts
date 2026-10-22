plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bonoco.audioxtract"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.breno.audioxtract"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // A biblioteca FFmpeg só vem para arm64 (celulares atuais)
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // FFmpeg (fork mantido do ffmpeg-kit) - variante "audio": MP3, Opus, Vorbis, AAC, FLAC, WAV
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:6.0.3")
    implementation("com.arthenica:smart-exception-java:0.2.1")
}
