# OrzMusic API 契约

> **由服务端维护。** 这里是 HTTP API、三种播放策略、分页与错误的唯一口径;
> 路由、DTO 字段、播放策略变了就同步改这里。
> 客户端各自的实现差异写在 `clients/<平台>/docs/` 下,不要往本文件里加客户端细节。
>
> 机器可读版本由服务端提供:`GET /api/openapi.json`。

## 1. 后端是什么

一个 KeyGen Music / 芯片音乐 / 模块音乐曲库 + Web 播放器:

```
OrzAudioCore SDK(外置 C/C++ 解码器)
  ├── Native library(服务端)
  └── WASM bundle(浏览器)
        ↓
OrzAudioKit(Swift ABI 封装)
        ↓
Vapor API + 扫描 + CAS + 播放策略
        ↓
Web Player / 各平台客户端
```

线上库快照(2026-11):**5533 首 / 713 位艺术家 / 25 种格式 / 约 793 MB**。
`totalAlbums = 0`、`totalPlaylists = 0` —— 专辑页暂时没有真实数据;
歌单由客户端创建、加载、删除(Android 端在队列面板里)。

### API 速查

OpenAPI 由后端自己提供:`GET /api/openapi.json`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康与版本 |
| GET | `/api/stats` | 曲库统计 |
| GET | `/api/songs/formats` | 每种格式的数量 |
| GET | `/api/songs?page=&per=&format=` | 歌曲分页 |
| GET | `/api/songs/search?q=&format=` | 搜索(歌曲名 + 艺术家名),最多 50 条 |
| GET | `/api/songs/{id}` | 单曲 |
| GET | `/api/songs/{id}/stream` | 播放流(策略见下) |
| GET | `/api/songs/{id}/raw` | 原始文件 |
| GET | `/api/songs/{id}/location?per=50` | 曲目在默认排序中的页码 / 位置(用于「定位」) |
| GET | `/api/artists?page=&per=` | 艺术家分页 |
| GET | `/api/artists/{id}/songs?page=&per=` | 某位艺术家的歌 |
| GET | `/api/albums?page=&per=` | 专辑分页(当前库为空) |
| GET | `/api/playlists` | 歌单列表(数组,不分页) |
| POST | `/api/playlists` | 新建歌单(`name` / `description` / `songIds`) |
| GET | `/api/playlists/{id}` | 歌单详情(含 `songs`) |
| DELETE | `/api/playlists/{id}` | 删除歌单 |

⚠️ **分页参数是 `page` / `per`,不是 `per_page`。** 传 `per_page` 会被忽略,
响应 `metadata.per` 一直是 10 —— 实测踩过。

响应模型(`Sources/App/Models/DTOs/`):

```jsonc
// SongResponse
{"id":"…","title":"…","artist":{"id":"…","name":"…","songCount":0},
 "fileFormat":"mod","fileSize":15630,"duration":76.8,
 "streamUrl":"/api/songs/…/stream","rawUrl":"/api/songs/…/raw",
 "playStrategy":"wasmDecode"}

// 分页
{"items":[…],"metadata":{"page":1,"per":10,"total":5533}}
```

`streamUrl` / `rawUrl` 是**相对路径**,客户端要自己拼服务器地址。

Android 的「保存当前队列」调用 `POST /api/playlists`,「定位当前曲目」调用
`GET /api/songs/{id}/location`。这两个接口在 Web 端也在用,但后端 OpenAPI
快照不一定列出 `location`,以实际路由为准。

---

## 2. 三种播放策略(最重要的一节)

| 策略 | 格式 | `/stream` 返回 | 浏览器 | 原生 Android |
|---|---|---|---|---|
| `directFile` | mp3 / ogg / flac / m4a / aac / PCM WAV | CAS 原始文件 | `<audio>` 直接放 | ExoPlayer ✅ |
| `serverDecode` | sc68、压缩 WAV | **PCM WAV**(首次请求转码并缓存) | `<audio>` 放 | ExoPlayer ✅ |
| `wasmDecode` | xm / mod / it / s3m / mo3 / mtm / nsf / spc / sid / ym / sap / ahx / mid / v2m / bp / rad / d00 / hsc / amd / fc13 / fc14 | **原始模块文件** | Worker + WASM 解码到 AudioWorklet | ✅ 原生解码已集成(部分格式) |

也就是说:**库里的 96% 是 `wasmDecode`**(xm 3396 + mod 1093 + it 254 + …),
而服务端按设计只给原始文件。所以原生端必须自带解码器 —— 这一条已经落地。

## 3. 客户端怎么消费

各平台客户端的实现(缓存位置、降级提示、UI)不在本文件:
Android 见 [`clients/android/docs/backend-api.md`](../clients/android/docs/backend-api.md)。
