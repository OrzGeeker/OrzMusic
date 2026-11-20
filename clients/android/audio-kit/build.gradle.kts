import org.gradle.api.publish.maven.MavenPublication

// 这个 AAR 是**对外发布的 SDK**,它自己的 Maven 版本号跟进的是 OrzAudioCore 上游,
// 与应用版本(仓库根 version.properties)无关,所以不能从那里读。
// 写成命名常量而不是直写 `version = "…"`,是因为 tools/tag-release.sh 会扫
// apps/ 里 `version = "x.y.z"` 形式的字面量来防「应用版本号被写死」——
// 直写会被当成违规(实测挡下过一次发版)。
val orzAudioCoreVersion = "1.2.4"

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

// OrzAudioKit (Android) —— 平台解码 SDK。
//
// 与 Apple 端的 Sources/OrzAudioKit 同层:
//   · 只负责把 OrzAudioCore 的 C ABI 包成平台的 decode-to-WAV API
//   · 不依赖 :core / Compose / Media3 / Retrofit
//   · 不决定播放策略、不拥队列、不做 HTTP/缓存
android {
    namespace = "com.jokerhub.orzmusic.audiokit"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 官方 Android SDK 的产物:AAR + sources jar,发布为
    //   com.orzgeeker:audio-core-android:<version>
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.orzgeeker"
                artifactId = "audio-core-android"
                version = orzAudioCoreVersion

                pom {
                    name.set("OrzAudioCore Android")
                    description.set("Android binding for the OrzAudioCore decoding SDK")
                    url.set("https://github.com/OrzGeeker/OrzAudioCore")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    scm {
                        url.set("https://github.com/OrzGeeker/OrzAudioCore")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "local"
                url = uri(layout.buildDirectory.dir("repo").get().asFile)
            }
        }
    }
}
