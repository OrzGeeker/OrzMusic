# 国内应用商店上架清单 —— OrzMusic

> 机器能查的项由 `bash scripts/store-check.sh --target cn` 检查。
> 标 ⚠️ 的项需要人工补齐。

## 共同资质

> ⚠️ 下面标「需人工」的四项是**账号/备案类**信息,只有你能提供;
> 其余素材都已生成,在 [`assets/`](assets/)。

| 项 | 状态 |
|---|---|
| 开发者账号实名认证 | ⚠️ 需人工:个人 / 企业 |
| 软件著作权证书(软著) | ⚠️ 需人工:证书号;应用名需与软著一致或能说明关系 |
| 移动互联网应用程序备案(APP 备案) | ⚠️ 需人工:备案号 |
| ICP 备案 | ⚠️ 需人工:应用有联网行为(访问自建/官方服务器),通常需要 |
| 隐私政策 URL | 文案已备好([`privacy-policy.md`](privacy-policy.md)),**需人工**托管到公开地址 |
| 应用图标 / 截图 / 简介 | ✅ `assets/icon-512.png`、`assets/screenshots/*`、简介直接用 [play.md](play.md#完整说明草稿可直接粘) 的那份 |
| TV banner 320×180 | ✅ `app/src/main/res/drawable/banner.png` |
| release 签名 APK | `./gradlew :app:assembleRelease`(签名配置见 README「上架」) |

## 开源许可(GPLv3)

本客户端按 **GPL-3.0-or-later** 分发(`liborzaudio.so` 静态链接了 GPL 系解码器)。
对国内商店同样适用:提交材料里要给出对应源码地址,并在应用内提供许可入口。

- 对应源码:`https://github.com/OrzGeeker/OrzMusic/tree/android-v<version>`
- 第三方组件与许可证全文:`clients/android/THIRD_PARTY_NOTICES.md`
- 不得加入签名校验 / 完整性校验阻止安装修改版(GPLv3 §6)

## 内容版权(音乐类商店会重点看)

- [ ] 曲库权利:官方实例的 KeyGen / 芯片音乐是否有分发权
- [ ] 商店描述里说明「客户端 + 可配置服务器」的定位
- [ ] 如果商店要求版权证明,准备好曲库来源说明

## 技术门槛(机器会查)

| 项 | 要求 | 当前 |
|---|---|---|
| 包名 | 不能 `com.example.*` | `com.jokerhub.orzmusic.android` ✅ |
| `targetSdk` | 建议 ≥ 30;华为/小米新应用常见 33+ | 36 ✅ |
| 32 位支持 | TCL 等 TV 商店 | 含 `armeabi-v7a` ✅ |
| 64 位支持 | 手机商店 | 含 `arm64-v8a` ✅ |
| 权限 | `INTERNET` + 前台媒体播放 + 通知(`POST_NOTIFICATIONS`);不含录音/定位/存储 | ✅ |
| APK 体积 | 多数商店 ≤ 100 MB | 约 27 MB(debug)/ 更小(release) ✅ |
| 签名 | release,不是 debug | `alias.OrzMusic` ✅ |

## 各商店差异

### TCL 应用商店(电视)

- TV 场景优先;banner、横屏截图、D-pad 全流程可操作
- 电视上只有 `armeabi-v7a`,本包已包含
- 审核会实测安装/启动;可用 EasyAndroid 的 `tools/tv-install.sh` 预演

### 华为 AppGallery

- 新应用通常要 `targetSdk ≥ 33`(已 36)
- 权限说明填「网络访问、后台音频播放、媒体通知」;不含录音/定位/存储权限
- 可能需要 64 位包(已含)

### 小米 / OPPO / vivo

- 独立后台,资料项类似
- 音乐/音频类可能要求版权说明;准备好曲库来源

## 提交流程(通用)

1. `./gradlew :app:assembleRelease`(APK)或 `:app:bundleRelease`(AAB)
2. `bash scripts/store-check.sh --target cn`
3. 后台创建应用 → 传 APK → 填资料 → 提审
4. 审核通过后,用真机从商店装一次,确认与本地包行为一致

## 备注

- 同一 `applicationId` + 同一签名才能覆盖升级
- 不要为过审改包名/签名 —— 等于换应用
- 本应用无广告、无账号、无第三方统计 SDK;如果后续加统计,隐私政策和数据安全都要同步改
