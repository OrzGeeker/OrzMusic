# 隐私政策(草稿)

> 这是给商店后台填的**文案**,不是自动上线的东西。
> 填之前要:① 补上联系方式;② 放到一个可公开访问的 HTTPS 地址
> (GitHub Pages / 自建站点都可以);③ 把那个 URL 填回 [play.md](play.md) / [cn.md](cn.md)。
>
> 改代码里任何「会往外发数据」的行为(搜索、统计、崩溃上报、账号),都要同步改这份文案。
> 当前实现见 `app/src/main/AndroidManifest.xml` 与 `core/` 的 API 客户端 ——
> 权限只有 INTERNET / 前台服务 / 通知 / WAKE_LOCK,没有录音、定位、存储。

---

## OrzMusic 隐私政策

最后更新:2026-11-20

OrzMusic(以下称「本应用」)是一个音乐播放客户端。本政策说明本应用会处理哪些数据。

### 1. 我们不收集什么

- **没有账号系统**,不收集姓名、邮箱、手机号。
- **没有广告 SDK、没有第三方统计 SDK、没有崩溃上报 SDK。**
- **不申请麦克风权限。** 界面上的「声场」是从播放器的音频输出管线里取 PCM 做频谱,
  不录制环境声音。
- **不申请定位、通讯录、相机、存储权限。**
- 不读取设备上的音乐文件,曲库全部来自你连接的服务器。

### 2. 会发往服务器的数据

本应用必须连接一个 OrzMusic 服务器才能工作,地址由你决定:

- 默认使用官方实例 `https://music.jokerhub.cn`;
- 你可以在「设置」里改成自建服务器地址。

客户端会向该服务器发送:

| 数据 | 用途 |
|---|---|
| 搜索关键词 | 执行你发起的搜索 |
| 曲目 / 艺术家 / 歌单 ID、分页参数 | 拉取列表、详情与音频流 |
| 访问音频文件的请求 | 播放 |

服务器运营方(官方实例或你所配置的实例)可能按其自身政策记录 IP 地址与请求日志。
**这部分不由本应用控制**;使用自建服务器时,日志由部署者负责。

本应用自身不会把这些请求内容另发给第三方。

### 3. 本地存储的数据

- **设置**:服务器地址等偏好,存在应用私有目录。
- **缓存**:为播放而下载的原始文件与解码后的音频文件,存在应用缓存目录;
  你可以在系统设置里清除应用缓存,或卸载应用一并删除。

这些数据不会上传,卸载应用即删除。

### 4. 权限用途

| 权限 | 用途 |
|---|---|
| `INTERNET` | 访问你配置的服务器 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 退到后台后继续播放 |
| `POST_NOTIFICATIONS` | 显示播放控制通知(可拒绝,只影响通知栏控制) |
| `WAKE_LOCK` | 播放期间保持唤醒,避免被系统休眠打断 |

### 5. 儿童

本应用不面向 13 岁以下儿童,也不会有意收集儿童的个人信息。

### 6. 数据删除

本应用没有账号,服务器端不保存属于「你」的资料。要清除本地数据:
系统设置 → 应用 → OrzMusic → 清除数据 / 卸载。
如需服务器端日志的删除,请联系你所使用实例的运营方。

### 7. 变更

本政策更新后,会随应用版本一起发布在本页面。

### 8. 联系方式

TODO:填一个可收信的联系邮箱(例如 `support@example.com`)。

---

## 英文摘要(部分商店要求)

OrzMusic is a client for a music library server. There is no account system,
no ads, no analytics and no crash-reporting SDK. The app does not request
microphone, location, camera or storage permissions; the on-screen visualizer
reads PCM from the playback pipeline, not from the microphone.

The only data leaving the device are the requests you trigger (search terms,
song/artist/playlist IDs, audio requests) sent to the server address you
configure — by default `https://music.jokerhub.cn`. That server may log IP
addresses and requests under its own policy. Settings and playback caches are
stored locally and removed when you clear app data or uninstall.

Contact: TODO
