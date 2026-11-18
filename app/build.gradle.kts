import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 签名配置：仓库内不携带任何密钥。
// 需要打正式包时，在项目根目录放 keystore.properties（已被 .gitignore 忽略）：
//   storeFile=keystore/matrix-music.jks
//   storePassword=******
//   keyAlias=matrix-music
//   keyPassword=******
// 文件不存在时自动回退到 debug 签名，保证 fork 下来可直接构建运行。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    !keystoreProps.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "com.musicbox.nas"
    // Media3 1.5.1 requires compileSdk >= 35
    compileSdk = 35

    defaultConfig {
        applicationId = "com.musicbox.nas"
        minSdk = 21
        targetSdk = 34
        versionCode = 71
        versionName = "3.0.1"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        // 设置页标题 / 关于直接读 BuildConfig.VERSION_NAME，避免升级时漏改硬编码版本号
        buildConfig = true
    }

    // release 打包不跑 lintVital：离线环境下 lint-gradle 依赖常拉取不到，
    // 且 lint 只做静态检查，不影响产物功能。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Media3 1.5.1 (ExoPlayer + MediaSession + OkHttp datasource)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-decoder:1.5.1")

    // FFmpeg 软解码扩展（对应 Media3 1.5.x）。
    // 采用本地 AAR 而非 Maven 坐标：避免其 POM 把 media3 模块固定到 1.5.0 与本工程 1.5.1 混用。
    // 内含 4 个 ABI 的 libffmpegJNI.so，LOAD 段 0x4000(16KB) 对齐，原生兼容 Android 16；
    // 包含 alac/flac/aac/mp3/truehd 解码器，用于 MediaCodec 不支持的无损格式兜底。
    implementation(files("libs/media3-ffmpeg-decoder.aar"))

    // Networking / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 1.6.3 是兼容 Kotlin 1.9.24 的最后版本（1.7.x 起要求 Kotlin 2.0）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
