plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Media3 AudioSink 包装层（vendor 自 Ma145/decent-player，MIT）。
// 用 UsbAudioSink 包装 DefaultAudioSink，把 PCM 直送 USB DAC，绕过 AudioFlinger。
// 按本项目工具链对齐：AGP 8.5.2 + Kotlin 1.9.24 + Java17，minSdk 23。
// 已裁掉上游的 SFTP/JSch 相关类（DecentDataSourceFactory / SftpDataSource），
// 本项目只有 http(s)/content/file 音源，不需要 SFTP。
android {
    namespace = "com.decent.usbaudio.media3"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":decentdriver"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-common:1.9.3")
    implementation("androidx.media3:media3-datasource:1.9.3")
}
