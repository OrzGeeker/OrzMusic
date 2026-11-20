# 客户端

服务端(`Sources/`)与 Web 播放器(`Resources/`)之外的**各平台客户端**。
每个客户端是独立的构建单元,只通过 HTTP API 与服务端交互。

| 目录 | 平台 | 状态 | 构建 |
|---|---|---|---|
| [`android/`](android/README.md) | Android(手机 / 平板 / TV / VR 2D 面板) | ✅ 可用 | Gradle(`./gradlew` 在 `android/` 下) |
| `web/` | 浏览器 | 仍在 `Resources/Public` + `Resources/Views` | — |
| `apple/` | iOS / macOS(预留包名 `com.jokerhub.orzmusic.apple`) | 计划中 | — |

## 单一事实来源

客户端的实现可以各自写,但这些口径**只有一个地方**:

| 口径 | 在哪 |
|---|---|
| HTTP API / 播放策略 / 分页与错误 | [`Docs/api.md`](../Docs/api.md)(服务端维护) |
| 支持格式、格式数量、缓存策略 | [`Docs/format-support.md`](../Docs/format-support.md) |
| 品牌(母版 SVG、512 图标、favicon) | [`Resources/Public/brand/`](../Resources/Public/brand/) |
| 解码 SDK 版本与校验 | [`audio-core-sdk.lock.json`](../audio-core-sdk.lock.json) |

客户端**不要**在本地复制这几份内容;需要差异时写「本平台的不同点」并链接过去。

## 版本与发布

- **服务端**:根目录 `VERSION` + `vX.Y.Z` tag → `.github/workflows/release.yml`。
- **Android**:`android/version.properties` + `android-vX.Y.Z` tag →
  `.github/workflows/android-release.yml`。两条线独立推进,互不阻塞。
- 变更日志也分开:服务端 `CHANGELOG.md`,Android `android/CHANGELOG.md`。

## CI

- `.github/workflows/android.yml` —— 每次 PR / main 推送都跑 Android 单元测试、
  JVM 截图断言、debug 打包和生成物一致性检查(刻意不设 `paths` 过滤,原因见文件内注释)。
- `.github/workflows/android-release.yml` —— 推 `android-v*` 时出签名 APK/AAB 并建 Release。

## 工具边界

**设备流程不在本仓库**:TCL 电视侧载、Pico 唤醒/定向注入、应用自截图、
签名凭据生成与恢复演练,统一在
[EasyIndie/EasyAndroid](https://github.com/EasyIndie/EasyAndroid) 的 `tools/` 与 `docs/`。
那里的脚本吃的是 APK 路径,对任意仓库的产物都适用,不需要复制一份进来。
