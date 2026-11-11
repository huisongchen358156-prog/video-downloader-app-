plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.noah.videodownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.noah.videodownloader"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5.0"

        // Chaquopy 强制要求：不配 ndk.abiFilters 的话 Gradle 配置阶段直接报错
        // （Build APK 运行 #9 就是栽在这里）。只留 ARM 两个架构，覆盖真机。
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    ndkVersion = "26.1.10909125"
}

chaquopy {
    defaultConfig {
        // yt-dlp 2026.10.26 起最低要求 Python 3.11；Chaquopy 16.0.0 提供 3.11。
        version = "3.11"
        pip {
            // 固定版本：2026.10.26 为 2026-10-26 发布的稳定版（PyPI 可查）。
            install("yt-dlp==2026.10.26")
        }
    }
}

// 本项目刻意零第三方 Android 依赖：
// - 不用 appcompat / Jetpack Compose / RecyclerView，用原生 android.app.Activity + 系统 View；
// - JSON 用 Android 内置的 org.json；历史记录用内部 JSON 文件；
// - 图片加载用 BitmapFactory，不引图片库。
dependencies {
}
