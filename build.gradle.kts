// 视频下载助手 V1 —— 根构建文件
// 版本依据（2026-11-10 核对）：
// - Chaquopy 16.0.0：chaquo.com 官方文档 16.0 版，支持 AGP 7.0.x–8.8.x，Python 3.8–3.13
// - AGP 8.5.2：需 Gradle 8.7+，需 JDK 17
// - Kotlin 1.9.24：与 AGP 8.5.2 兼容
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.chaquo.python") version "16.0.0" apply false
}
