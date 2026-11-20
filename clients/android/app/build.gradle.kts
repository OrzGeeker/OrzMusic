// ⚠️ 这两个 import 必须在 plugins {} 之前:否则 `java.io.File` / `java.util.Properties`
// 里的 `java` 会被 Gradle 的 java 插件扩展(java { })遮蔽,报 Unresolved reference: io。
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ─────────────────────────────────────────────────────────────
// 向上查找仓库根的配置文件
//
// 本工程是独立 Gradle 构建(rootDir = apps/<Name>),所以不能写死 "../.." 这种
// 层级 —— 抽成函数往上找,拆工程 / 挪目录都不会坏。
// (写成 `while (dir != null && !File(dir, ...)) dir = dir.parentFile` 会踩 Kotlin
//  的智能转换限制:不能对可能被闭包捕获的 var 做 smart cast。)
// ─────────────────────────────────────────────────────────────
fun findUpward(name: String): File? {
    var d: File? = rootDir
    while (d != null) {
        val f = File(d, name)
        if (f.isFile) return f
        d = d.parentFile
    }
    return null
}

// 判断是不是绝对路径(Unix `/...` 或 Windows `C:\...` / `C:/...`)。
// ⚠️ 为什么不能直接 File(parent, child):Java 的 File(File, String) 在 child 是
//    绝对路径时做的是**拼接**而不是替换 ——
//      File("/a/b", "/x/y") == "/a/b/x/y"
//    踩过:release-apk.sh 在临时 worktree 里把 storeFile 写成绝对路径后,
//    AGP 去找 <worktree>/mnt/e/.../release.jks,报 Keystore file not found。
fun isAbsolutePath(p: String): Boolean =
    p.startsWith("/") || p.startsWith("\\") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(p)

// ─────────────────────────────────────────────────────────────
// 版本号:唯一来源是本工程(客户端)根目录的 version.properties。
// 客户端版本与服务端 VERSION 相互独立 —— 服务端由 vX.Y.Z tag 发布,
// 客户端由 android-vX.Y.Z tag 发布。
// ─────────────────────────────────────────────────────────────
val versionFile: File = findUpward("version.properties")
    ?: error("找不到 version.properties —— 它是客户端版本号的唯一来源,应该在 clients/android/ 下")

val appVersion: String = run {
    val props = Properties()
    versionFile.inputStream().use { props.load(it) }
    val v = props.getProperty("version")?.trim().orEmpty()
    if (v.isEmpty()) error("${versionFile.path} 里缺少 version=<MAJOR.MINOR.PATCH>")
    v
}

// SemVer -> versionCode:MAJOR*10000 + MINOR*100 + PATCH(每段 0..99)
// Android 要求 versionCode 单调递增。由 SemVer 推导就不会出现「改了 versionName 忘记改 versionCode」。
val appVersionCode: Int = run {
    val parts = appVersion.split(".")
    if (parts.size != 3) error("version=$appVersion 不是严格 SemVer(MAJOR.MINOR.PATCH)")
    parts.forEach {
        if (it.isEmpty() || (it.length > 1 && it.startsWith("0"))) {
            error("version=$appVersion 的段 '$it' 不合法(SemVer 不允许空段或前导零)")
        }
    }
    val nums = parts.map { p -> p.toIntOrNull() ?: error("version=$appVersion 里有非数字段: $p") }
    require(nums.all { it in 0..99 }) {
        "version=$appVersion 每段只能是 0..99(versionCode = MAJOR*10000 + MINOR*100 + PATCH)"
    }
    nums[0] * 10000 + nums[1] * 100 + nums[2]
}

// ─────────────────────────────────────────────────────────────
// 发布签名:同样向上找 keystore.properties。
//
// **找不到就不配 signingConfig** —— release 仍能构建(产出 app-release-unsigned.apk),
// 保证任何人 clone 下来都能跑 assembleRelease;只是那种包装不上设备。
// 本地发版:把 keystore.properties + release.jks 放到 clients/android/(两者都 gitignore),
// CI 由 .github/workflows/android-release.yml 从 secrets 还原。
// ─────────────────────────────────────────────────────────────
// 存绝对路径:构建可能在临时 worktree 里进行(发版脚本就是这么做的),
// 那时 worktree 到密钥库的相对关系和工作区不同。
val keystoreProps: Properties? = findUpward("keystore.properties")?.let { f ->
    Properties()
        .apply { f.inputStream().use { load(it) } }
        .also { it.setProperty("__file", f.absolutePath) }
}

android {
    namespace = "com.jokerhub.orzmusic.android"
    // OrzMusic 是要上 Google Play 的正式应用;2026-08-31 起要求 target API 36。
    // 模板 / DualDemo 仍停在 34,它们不上 Play。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jokerhub.orzmusic.android"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    // ⚠️ signingConfigs 必须在 buildTypes 之前 —— buildTypes 里要用
    // `signingConfigs.getByName("release")`,而 DSL 是按书写顺序执行的。
    // 有密钥才建;没有就跳过(见上面 keystoreProps 的说明)
    if (keystoreProps != null) {
        signingConfigs {
            create("release") {
                // storeFile 默认是相对仓库根的路径,也支持绝对路径
                // (tools/release-apk.sh 在临时 worktree 里构建时会写成绝对路径;CI 同理)。
                // 路径拼接的坑见 isAbsolutePath 的注释。
                val rawStore = keystoreProps.getProperty("storeFile")
                val propsDir = File(keystoreProps.getProperty("__file")).parentFile
                storeFile = if (isAbsolutePath(rawStore)) File(rawStore) else File(propsDir, rawStore)
                storePassword = keystoreProps.getProperty("storePassword")
                // 每个应用用哪个别名:优先 alias.<工程名>,回落到 keyAlias。
                // 「一个密钥库文件 + 每应用一把密钥」靠这两行,不用改构建脚本。
                // 为什么要分开:同一签名下的应用可互信(能取得对方 signature 级权限
                // 保护的组件、共享 sharedUserId),共一把 = 共一个信任域。
                // ⚠️ 已发布过的应用不要改别名 —— 等于换签名,老用户升不了级。
                // 给新应用加别名: bash tools/gen-keystore.sh --add-alias <AppName>
                keyAlias = keystoreProps.getProperty("alias." + rootProject.name)
                    ?: keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        // 界面上显示的版本号来自 BuildConfig.VERSION_NAME,不再硬编码字面量
        buildConfig = true
    }

    // lint:现有问题先固化进基线,之后**新增**一条就失败。
    //
    // 当前基线约 60+ 条,集中在 tv-material 的 RestrictedApi(在 Activity 里覆写
    // dispatchKeyEvent / dispatchGenericMotionEvent)、硬编码中文字串这类
    // 「10-foot UI 必然要写」的地方。清掉一批后重跑:
    //   ./gradlew :app:updateLintBaseline
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }

    // Robolectric 需要能读到编译后的资源,否则截图里没有主题/字体
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Roborazzi 靠系统属性决定"出图还是只断言"。
            // 用 -P 传的 Gradle 属性不会自动进测试 JVM,必须在这里转发。
            // 默认出图(这是"看 UI"用的);CI 想只断言就传
            //   -Proborazzi.test.record=false
            all { test ->
                test.systemProperty(
                    "roborazzi.test.record",
                    project.findProperty("roborazzi.test.record")?.toString() ?: "true"
                )
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")

    // 平台无关的核心层(模型 / API 契约 / 播放策略 / 队列 / 解码清单)
    implementation(project(":core"))

    // 平台解码 SDK(OrzAudioCore JNI + WAV)
    implementation(project(":audio-kit"))

    // ── 网络 / JSON ──
    // OrzMusic 后端是 Vapor,返回 camelCase JSON;kotlinx.serialization 直接映射。
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // ── 播放 ──
    // directFile(mp3/ogg/flac/wav)与 serverDecode(服务端转 WAV,如 sc68)走 ExoPlayer。
    // wasmDecode 的模块音乐(mod/xm/it...)服务端只返回原始文件,ExoPlayer 解不了 ——
    // 客户端会明确提示并给出网页播放器入口(见 docs/12)。
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    // 后台播放 / 通知栏 / 锁屏控制：ExoPlayer 由 MediaSessionService 持有，
    // UI 通过 MediaController 连接（见 player/PlaybackService.kt）。
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media:media:1.7.0")

    // ---- JVM 截图测试(不需要设备/模拟器)----
    // 用 Robolectric 在 JVM 上渲染 Compose,Roborazzi 把结果落成 PNG。
    // 这是最快的 UI 迭代手段:几秒出图,能断言焦点态,也能在 CI 上跑。
    // 版本要和工程的 Kotlin 对齐:Roborazzi 1.66+ 是用 Kotlin 2.1+ 编的,
    // 元数据版本比编译器的 2.0 新,会直接编译不过。
    // 1.65.0 的 kotlin-stdlib 是 2.0.21,正好匹配。
    // 用法见 docs/08-jvm-screenshot-testing.md
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.30.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.30.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
