plugins {
    // AGP 8.6.1：Compose 1.10.0 / lifecycle 2.9 的最低要求（≥8.6.0）；Gradle 8.9 兼容。
    id("com.android.application") version "8.6.1" apply false
    // 依赖对齐：backdrop 1.0.2 官方基线为 Kotlin 2.2.21 + Compose 1.10.0。
    // Kotlin / serialization / Compose Compiler 三者版本必须一致，避免 ABI / 元数据兼容问题。
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
