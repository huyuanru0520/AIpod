import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Kotlin 2.x 起 Compose Compiler 独立成插件，版本与 Kotlin 对齐，旧的 composeOptions 代码块已废弃。
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名配置：仓库内不携带任何密钥。
// 需要打正式包时，在项目根目录放 keystore.properties（已被 .gitignore 忽略）：
//   storeFile=keystore/aipod.jks
//   storePassword=******
//   keyAlias=aipod
//   keyPassword=******
// 文件不存在时自动回退到 debug 签名，保证 fork 下来可直接构建运行。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    !keystoreProps.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "com.aipod"
    // Media3 1.9.3 / USB bit-perfect 库要求 compileSdk >= 36
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aipod"
        // Media3 1.9.3 要求 minSdk >= 23；backdrop 液态玻璃在 API 31+ 走 RenderEffect，
        // 低版本由库自动降级（不崩），无硬性 minSdk 抬升要求。
        minSdk = 23
        targetSdk = 34
        versionCode = 148
        versionName = "5.2.3"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM 2025.12.00 → Compose 1.10.0，与 backdrop 1.0.2 + Kotlin 2.2.21 对齐。
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 液态玻璃：Kyant0/AndroidLiquidGlass（backdrop）——纯 Jetpack Compose 实现，
    // 用 RenderEffect/RuntimeShader 做背景采样 + vibrancy/blur/lens 折射 + 高光。
    // 无需 AndroidView 桥接，从根上避免 View 库与 Compose RenderNode 的重入崩溃。
    // 版本对齐：backdrop 1.0.2 依赖 Kotlin 2.2.21 + Compose 1.10.0（BOM 2025.12.00）。
    implementation("io.github.kyant0:backdrop:1.0.2")
    // Palette：从封面提取主色/鲜艳色/柔和色，用于生成「环境光」流光渐变背景。
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Media3 1.9.3 (ExoPlayer + MediaSession + OkHttp datasource)
    // 升级到 1.9.3 以便与 USB bit-perfect 库（decent-player，基于 1.9.3 的 AudioSink）对齐。
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-session:1.9.3")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.3")
    implementation("androidx.media3:media3-decoder:1.9.3")

    // FFmpeg 软解码扩展：改用 Jellyfin 维护的 1.9.0+1（旧本地 AAR 按 1.5.1 的 Decoder 接口编译，不兼容 1.9.3）。
    // 其 POM 依赖 media3 1.9.0，Gradle 会解析到本工程声明的 1.9.3，版本一致。
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    // USB 独占输出（实验性，默认关）：vendor 自 decent-player（MIT）的用户态 UAC2 驱动。
    // 仅在用户于设置页开启且检测到 USB DAC 时启用，失败自动回退到标准 ExoPlayer。
    implementation(project(":decentwrapper"))

    // Networking / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 1.6.3 是兼容 Kotlin 1.9.24 的最后版本（1.7.x 起要求 Kotlin 2.0）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
