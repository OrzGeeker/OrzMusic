plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 纯 Kotlin / JVM,不依赖 Android。
// 目标:平台无关的客户端核心,以后可以平移到 KMP commonMain 或 monorepo 的 common/。
kotlin {
    jvmToolchain(17)
}

dependencies {
    // DTO 需要序列化;消费方(Android/Desktop)直接拿到同一套模型
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
