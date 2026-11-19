pluginManagement {
    repositories {
        google { url = uri("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AIpod"
include(":app")
// USB 独占输出（vendor 自 decent-player，MIT）：用户态 UAC2 驱动 + Media3 AudioSink 包装层
include(":decentdriver")
include(":decentwrapper")
