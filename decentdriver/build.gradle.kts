plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// USB Audio Class 2.0 用户态驱动（vendor 自 Ma145/decent-player，MIT）。
// 原库要求 AGP 9.1 / Kotlin jvmToolchain(21) / minSdk 29；
// 本项目沿用 AGP 8.5.2 + Kotlin 1.9.24 + Java17，minSdk 23。
// 库源码只用 Kotlin 1.9 特性与带兼容处理的 Android API（RECEIVER_NOT_EXPORTED 等），
// 因此下调工具链版本即可，无需 JDK 21。
android {
    namespace = "com.decent.usbaudio"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        externalNativeBuild {
            cmake { cppFlags("") }
        }
        ndk {
            // 只构建真机常用 ABI，减小包体并加快原生编译
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake { path("src/main/jni/CMakeLists.txt") }
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
    implementation("androidx.core:core-ktx:1.13.1")
}
