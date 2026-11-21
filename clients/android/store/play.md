# Google Play 上架清单 —— OrzMusic

> 机器能查的项由 `bash scripts/store-check.sh --target play` 检查;
> 这份文件记录**只能人工确认**的材料。标 ⚠️ 的项在提交前必须补齐。

## 应用身份

| 项 | 值 |
|---|---|
| 包名 | `com.jokerhub.orzmusic.android` |
| 应用名(≤30 字符) | OrzMusic |
| 类别 | 音乐与音频(Music & Audio) |
| 定价 | 免费 |
| 广告 | 无 |
| 应用内购买 | 无 |
| 登录 | 无(后端本身无鉴权) |

## 商店资料

素材都在 [`assets/`](assets/),可用 `gen-store-assets.java` 重出(见 [README](../README.md#商店物料))。

| 项 | 状态 |
|---|---|
| 简短说明(≤80 字符) | ✅ 「浏览与播放 KeyGen / 芯片音乐:MOD、XM、IT、S3M、NSF,支持后台播放与 TV/VR。」 |
| 完整说明(≤4000 字符) | ✅ 见下方「完整说明」 |
| 应用图标 512×512 PNG(32 位) | ✅ [`assets/icon-512.png`](assets/icon-512.png) |
| 特色图片 1024×500 PNG | ✅ [`assets/feature-graphic-1024x500.png`](assets/feature-graphic-1024x500.png) |
| 手机截图(至少 2 张) | ✅ `assets/screenshots/phone-01-library.png` / `phone-02-playing.png` / `phone-03-nowplaying.png`(1080×1920) |
| 7 寸 / 10 寸平板截图 | ✅ `assets/screenshots/tablet-01-library.png`(1200×1600) |
| Android TV 截图(至少 1 张) | ✅ `assets/screenshots/tv-01-library.png` / `tv-02-nowplaying.png`(1920×1080) |
| TV banner 320×180 | ✅ `app/src/main/res/drawable/banner.png`(= `assets/banner-320x180.png`) |

### 完整说明(草稿,可直接粘)

```text
OrzMusic 是一个 KeyGen Music / 芯片音乐 / 模块音乐播放器的 Android 客户端。

曲库由 OrzMusic 服务器提供,应用内可按模块、复古主机、芯片合成等分组浏览,
支持搜索、艺术家、歌单与播放队列。默认连接官方实例,也可以在设置里填写自建服务器地址 ——
本应用不绑定任何单一服务端。

格式支持:
· 直接播放:mp3 / ogg / flac / m4a / aac / WAV
· 原生解码:MOD / XM / IT / S3M / MO3 / MTM / FC13 / FC14 及 YM / MID / BP
· 其余芯片格式会提示在网页播放器打开

特性:
· 后台播放与通知栏 / 锁屏控制(MediaSession)
· 实时声场:直接从播放管线取 PCM 做频谱,不申请录音权限
· 自适应:手机、平板、Android TV、VR 头显的 2D 面板共用同一个 APK
· TV / VR:遥控器与手柄可完整操作;界面按 10-foot 可读性设计

权限说明:
· 网络:访问你指定的曲库服务器
· 前台服务 + 通知:后台播放与媒体控制
· 不申请录音、定位、存储权限;无账号、无广告、无第三方统计 SDK
```

## 政策与表单

| 项 | 状态 |
|---|---|
| 隐私政策 URL | ⚠️ 文案已备好(见 [`privacy-policy.md`](privacy-policy.md)),**需要你托管到一个公开 URL** 再填进来 |
| 数据安全表单 | ⚠️ 需要判断:客户端无账号/无广告/无分析 SDK,但**搜索词与曲目 ID 会发往用户配置的服务器**。若官方实例会记录 IP/请求,建议如实勾「应用活动 · 应用内搜索历史」;自建服务器则由部署者负责 |
| 内容分级问卷 | 音乐类;无 UGC、无社交。按实际曲库内容填写 |
| 目标受众 | 建议 13+(音乐内容,无 UGC、无社交) |
| 应用访问权限 | 全部功能无需登录 |
| 政府/新闻/COVID | 否 |
| 数据删除请求 | 不适用(无账号) |

## 内容版权(重要)

这是一个**客户端**,曲库来自 OrzMusic 服务端(KeyGen / 芯片 / 模块音乐)。
Play 对音乐/流媒体类会关注版权。提交前确认:

- [ ] 官方实例的曲库有权分发,或
- [ ] 商店描述里明确「本应用是客户端,内容由用户配置的服务器提供」,并让用户自建

## 许可证与源码(GPLv3)

本应用按 **GPL-3.0-or-later** 分发(`liborzaudio.so` 静态链接了 GPL 系解码器)。
上架前必须满足:

- [ ] 商店描述里给出**对应源码**地址:
      `https://github.com/OrzGeeker/OrzMusic/tree/android-v<version>`
- [ ] GitHub Release 附上同 tag 的源码包(`android-release.yml` 自动带上)
- [ ] App 内可访问「开源许可 / 源码」入口(设置页)
- [ ] **没有**签名校验 / Play Integrity 这类防篡改(会违反 §6 反锁定)

机器能查的部分由 `bash scripts/store-check.sh --target play` 的「GPLv3 合规」小节校验。

## 技术门槛(机器会查)

- [x] `targetSdk` = 36
- [x] 包名不是 `com.example.*`
- [x] AAB 已签名(`jarsigner -verify`),含 `arm64-v8a`
- [x] 非 debuggable、无 debug 自截图钩子
- [x] 权限:网络、前台媒体播放、通知;不含 `RECORD_AUDIO` / 定位 / 存储

## 提交流程

1. `./gradlew :app:assembleRelease :app:bundleRelease`(本地签名包)
2. `bash scripts/store-check.sh --target play`
3. `bash scripts/android-release.sh --version <version>`(打 `android-v<version>` tag,CI 出 Release)
3. Play Console → 创建应用 → 填资料 → 上传 `.aab` 到**内部测试**
4. 内部测试装一遍:手机 + TV 各验一次(曲库 / 搜索 / mp3 或 ogg 播放)
5. 逐级 promoted:closed → open → production

> ⚠️ Play **没有 VR 轨道**。Pico 用同一个 APK,走 Pico 自己的商店/侧载。
