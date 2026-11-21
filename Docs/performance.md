# 性能测量

本文定义 OrzMusic 首屏、API 和播放 HTTP 链路的稳定测量入口。历史模型分工、实施过程和验收记录见已归档的 [全链路性能优化计划](archived/performance-optimization-plan.md)。

## 快速开始

服务启动后运行：

```bash
SERVICE_URL=http://127.0.0.1:8080 \
PERF_REPEATS=3 \
PERF_CACHE_MODE=warm \
./script/performance-smoke.sh
```

默认结果写入 `/tmp/orzmusic-performance-<UTC timestamp>/`：

- `requests.jsonl`：每个请求的原始 curl timing。
- `summary.json`：机器可读的全部样本和分组中位数。
- `summary.md`：便于审阅的中位数表格。

脚本失败时返回非零状态。某个请求失败不会阻止其余样本采集。

## 环境变量

| 变量 | 默认值 | 说明 |
|:-----|:-------|:-----|
| `SERVICE_URL` | `http://localhost:8080` | 待测服务根 URL |
| `PERF_REPEATS` | `3` | 每个请求重复次数，必须为正整数 |
| `PERF_TIMEOUT` | `30` | 单个请求超时秒数 |
| `PERF_CACHE_MODE` | `unspecified` | 建议填 `cold` 或 `warm`，只作结果标签 |
| `PERF_OUTPUT_DIR` | `/tmp/orzmusic-performance-...` | 输出目录 |
| `PERF_RANGE` | `bytes=0-0` | 媒体首字节采样 Range；可按需扩大 |
| `DIRECT_SONG_ID` | 空 | `directFile` 固定样本 ID |
| `WASM_SONG_ID` | 空 | 使用完整 WASM profile 的样本 ID |
| `BUILTIN_WASM_SONG_ID` | 空 | BP/MID/YM builtin WASM 样本 ID |
| `SERVER_SONG_ID` | 空 | `serverDecode` 固定样本 ID |

未提供歌曲 ID 时，脚本仍采集首页和三个初始化 API，并明确省略相应媒体项。

## 样本选择

每次对比必须使用相同歌曲，并记录：

- 歌曲 ID、格式、播放策略、文件大小和时长。
- 应用版本和 commit。
- 服务设备、浏览器版本、网络位置和曲库总量。
- serverDecode 缓存是否存在。

不要根据标题猜测播放策略，以 `/api/songs` 返回的 `playStrategy` 为准。WAV 需要额外确认文件头，因为 PCM WAV 可以由服务端优化为 `directFile`。

## 冷缓存与热缓存

`PERF_CACHE_MODE` 只标记结果，不会替用户清理浏览器、代理、系统页缓存或 WAV 文件。

- 首屏冷缓存：使用新的浏览器 profile 或禁用缓存后采集。
- 首屏热缓存：正常刷新并保留浏览器/代理缓存。
- serverDecode 冷缓存：只在明确确认目标 cache key 后按运维流程处理；不要删除整个 CAS 或 volume。
- serverDecode 热缓存：先成功播放/预热同一首歌，再运行脚本。

HTTP Range 采样可测媒体端点的 TTFB 和部分传输，但不能替代浏览器真实的点击到首帧指标。WASM 下载、编译、Worker ready、underruns 和实际首帧由后续 `PERF-018` 统一采集。

## 历史时长回填

歌曲列表与搜索请求不会再顺带探测或写入缺失时长。需要修复历史数据时，在连接目标 PostgreSQL、且 `CAS_ROOT` 指向同一份音频存储的环境中显式运行：

```bash
# 先预览候选数量，不读取音频、不写数据库
make backfill-durations DRY_RUN=1

# 小批量验证；确认结果后再逐步扩大 LIMIT
make backfill-durations LIMIT=100 BATCH_SIZE=25 CONCURRENCY=2
```

命令读取与 Web 服务相同的 `DATABASE_HOST`、`DATABASE_PORT`、`DATABASE_USERNAME`、`DATABASE_PASSWORD`、`DATABASE_NAME` 和 `CAS_ROOT`。默认批次为 50、探测并发为 2；底层探测优先使用 OrzAudioCore，必要时回退到带 5 秒超时的 `ffprobe`。

每个 CAS 缺失、探测失败或超时的条目都会输出歌曲 ID、格式和原因，并保留 `duration = NULL`。因此命令可以中断后重跑；已经成功写入的歌曲会被自动排除。该维护命令不会随 Web 服务启动自动执行。

## PostgreSQL 搜索索引部署

`CreateSearchTrigramIndexes` migration 会执行 `CREATE EXTENSION IF NOT EXISTS pg_trgm`，部署数据库账号必须具备在目标数据库安装该扩展的权限。迁移随后为 `lower(songs.title)` 和 `lower(artists.name)` 创建 GIN trigram 索引，并为歌曲的 `artist_id` 建立 B-tree 连接索引。

回滚会删除这三个索引，但会保留 `pg_trgm` 扩展，因为同一数据库中的其他对象可能也依赖它。生产升级前应在备份后运行 migration；权限不足时 migration 会失败并保持未应用状态，需要由数据库管理员先安装 `pg_trgm`，再重试 migration。

## 静态资源交付

应用层启用 Vapor 的可压缩响应压缩，并按 URL 类型设置缓存策略：

- HTML 使用 `no-cache`，API 使用 `no-store`。
- 带 `v=` 内容版本的 JS/CSS/JSON/SVG/WASM 和固定版本 vendor 路径使用一年 `immutable`。
- 其他静态文本资源使用一小时并要求重新验证。
- 音频不动态压缩，Range、ETag、Last-Modified 和跨源隔离响应头保持不变。

本机真实曲库复测中，首屏关键资源从 identity 的 155,819 bytes 降至 gzip 的 46,940 bytes，传输体积减少 69.9%。XM 浏览器实播显示 WASM 实时解码且进度正常推进。该结果覆盖 `make run` 的 HTTP 应用入口；部署到 HTTPS 反向代理后仍需运行 `script/release-smoke.sh`，并在浏览器复验跨源隔离与 WASM 播放，确认代理没有覆盖响应头或再次压缩音频。

## 服务端解码并发

`SERVER_DECODE_CONCURRENCY` 控制不同冷缓存 key 的全局并行解码数，默认 1，配置范围为 1～32。相同 key 会先由 single-flight 合并，所以所有等待者只占一个解码槽；有效 WAV 热缓存会在进入 coordinator 前返回，不占 permit。

每次服务端解码输出 `server_decode` 结构化日志，字段包括 `cache_hit`、`format`、`queue_ms`、`decode_ms` 和 `output_bytes`。失败日志为 `server_decode_failed`，额外包含 `reason`，但不会记录完整源文件路径。单机应先保持默认值，用实际 CPU、内存和磁盘指标验证后再提高：

```bash
SERVER_DECODE_CONCURRENCY=1 make run
```

## 生产日志与数据库连接池

Compose 的生产默认日志级别为 `info`。这会保留启动、请求、迁移和
`server_decode` 等运维日志，同时抑制 debug 级 SQL 等高频诊断输出。临时排障可显式
设置 `LOG_LEVEL=debug`，完成后应恢复 `info`。

FluentPostgresDriver 当前默认每个 EventLoop 最多建立 1 条连接，请求等待连接的上限为
10 秒。不要把“每个 EventLoop”误认为整个进程只有一条连接；本机 8 个 EventLoop
实际稳定持有 8 条空闲连接。生产变更前可用以下只读查询核对 PostgreSQL 上限与连接状态：

```sql
SHOW max_connections;
SELECT state, count(*)
FROM pg_stat_activity
WHERE datname = current_database()
GROUP BY state
ORDER BY state;
```

2026-07-26 的真实本机基线为 PostgreSQL `max_connections=100`、应用空闲连接 8 条。
对 `/api/songs?limit=20` 执行 200 次、并发 32 的请求，平均 53.5ms、P50 40.2ms、
P95 137.5ms、最大 165.1ms，全部成功且未触发 10 秒连接等待。基于该结果没有覆盖
驱动默认 pool 参数；只有出现连接等待证据，或应用连接总数逼近 PostgreSQL 上限时才调参。

### 低峰期预热

预热命令必须显式限定范围，不提供选择器时会拒绝运行；不会在 Web 服务启动时自动扫描曲库。建议先 dry-run：

```bash
# 单曲或明确 ID 列表
make warm-decode-cache SONG_ID=<uuid> DRY_RUN=1
make warm-decode-cache SONG_IDS=<uuid1,uuid2> CONCURRENCY=1

# 按格式，或只取该格式最近导入的 N 首
make warm-decode-cache FORMAT=sc68 DRY_RUN=1
make warm-decode-cache FORMAT=sc68 RECENT=10 CONCURRENCY=1
```

选择器组合时取交集。命令只处理实际解析为 `serverDecode` 的曲目，跳过 direct/WASM；已有有效 PCM WAV 计为 `cache_hits` 并跳过解码。汇总包含选择数、可预热数、新生成数、cache hit、跳过数、缺失文件和失败明细；任一失败返回退出码 2。

### 按命中收敛预热范围

`serverDecode` 每次命中都会记一笔（`ServerDecodeHitTracker`，落盘在
`data/decode-hits.json`，可用 `DECODE_HIT_STATS_PATH` 覆盖）。先看哪些格式还有人要服务端转码：

```bash
make decode-hits
# decode-hits path=data/decode-hits.json formats=1
# decode-hits format=sc68 count=42 last=2026-11-20T10:12:03Z
```

然后只预热「最近 14 天至少被请求过 1 次」的格式：

```bash
make warm-decode-cache RECENT=50 MIN_HITS=1 HITS_WINDOW_DAYS=14
# 汇总里多一项 skipped_no_recent_hits=<被跳过的曲目数>
```

客户端能本地解码的格式（Android `android-full` profile 覆盖 23 个）本来就不会再走
`serverDecode`，命中统计会把它们自然淘汰掉——不需要手工维护格式白名单。


### 容量报告与安全清理

缓存治理命令默认只报告，不删除。清理策略也默认 dry-run，必须额外提供 `APPLY=1` 才会修改文件：

```bash
# 文件数、总字节及各 SDK fingerprint 占用
make maintain-decode-cache

# 预览旧 fingerprint 清理
make maintain-decode-cache REMOVE_OLD_FINGERPRINTS=1

# 预览将缓存收敛到 4 GiB；确认后显式执行
make maintain-decode-cache MAX_BYTES=4294967296
make maintain-decode-cache MAX_BYTES=4294967296 APPLY=1
```

删除只扫描 `CAS_ROOT/.cache/wav` 的直属 WAV 文件，拒绝符号链接路径逃逸，不递归进入其他目录。默认保护最近 300 秒生成的文件；Web 流式响应在整个发送期间持有跨进程共享文件锁，清理只有取得排他锁后才能删除，因此不会删除正在播放的缓存。未知命名文件只报告为 `unrecognized`，不参与自动选择。

## 浏览器首帧诊断

诊断默认关闭；关闭时页面不会设置上报回调，也不会请求诊断 endpoint。需要短期采样时显式启动：

```bash
PLAYBACK_DIAGNOSTICS_ENABLED=true make run
```

每次成功首帧只上报一条 `playback_first_frame`：策略、格式、资源 fetch、WASM ready、Worker ready、first frame、点击到 playing、underruns 快照及是否 fallback。三种策略使用同一字段集合，不适用的阶段为 `null`。payload 不包含歌曲 ID、标题、URL/路径或用户标识；后端限制格式字符及所有数值范围，并只写结构化应用日志，不连接第三方分析服务。采样结束后移除环境变量并重启服务。

### WASM Worker 预热与复用

页面不会在首屏无条件下载 WASM。只有用户 hover、键盘聚焦或选择一首 `wasmDecode` 曲目后，才通过空闲回调预热对应 bundle：

- `bp`、`mid`、`ym` 使用 builtin bundle。
- 其他 WASM 格式使用 full bundle。

每个 bundle 最多保留一个空闲 Worker。切歌时旧 generation 先 cancel、destroy decoder 并回报 stopped，之后 Worker 才能回池；自然结束也会销毁 decoder 后回池。预热或运行失败的 Worker 会被终止，下一次交互可重新创建。该复用不跨 builtin/full bundle，也不复用 decoder handle。

## 基线报告规则

- 冷、热各运行至少三次，比较中位数。
- 保留原始 JSONL，不只复制摘要。
- 优化前后使用相同设备、服务版本之外的相同数据集和样本。
- 报告失败、超时和回退，不从中位数中静默删除异常请求。
- 首轮只记录趋势；性能预算在 `PERF-002` 根据真实部署基线确定。

## 2026-07-26 优化收口结果

环境为同一台 Apple Silicon MacBook Air、`make run` 的 Swift debug 服务、
PostgreSQL/CAS 本机连接、5,533 首相同曲库。固定 HTTP 样本为 MP3/direct、
MOD/full WASM 和 WAV/server。每组各三次，下表为中位数：

| 链路 | PERF-002 基线 | 收口 cold 标签 | 收口 warm 标签 | 结论 |
|:-----|--------------:|---------------:|---------------:|:-----|
| 首页 | 32.140ms | 5.261ms | 4.801ms | 改善 |
| songs API | 420.362ms | 12.357ms | 16.310ms | 显著改善 |
| formats API | 7,325.537ms | 6.981ms | 6.128ms | 显著改善 |
| playlists API | 199.195ms | 3.126ms | 2.602ms | 显著改善 |
| direct HTTP 首字节 | 149.643ms | 3.626ms | 5.114ms | 改善 |
| WASM 源文件首字节 | 57.013ms | 3.731ms | 3.584ms | 改善 |
| server warm 首字节 | 129.676ms | 4.875ms | 4.299ms | 改善 |

这里的 `cold`/`warm` 是采集标签；curl 每次直达源站，脚本不会删除浏览器缓存或
serverDecode WAV。为避免误导，不能把 4.875ms 解释为 server cold：PERF-002 的同机
自然冷样本仍是前两次 30 秒超时，说明“整曲解码后响应”本身没有改善。实施结果是通过
single-flight、全局并发 1、显式预热和容量治理让生产请求尽量命中约 4.3ms 的热缓存，
而不是把冷解码伪装成流式响应。

浏览器诊断的真实样本为：MP3/direct 点击到首帧 67.20ms、XM/WASM 72.73ms
（资源 fetch 9.94ms、Worker ready 67.37ms）、热 SC68/server 15.74ms。它们验证三条
播放链路和诊断字段，但各只有一次，不作为“三次中位数”宣称。WASM Worker 后续增加了
按交互预热和同 bundle 复用；第二首同 profile 不重复加载模块。

未改善或尚未覆盖的边界：

- server cold 仍需完整解码；当前选择预热，不实施复杂的流式转码。
- ABI v1 不支持增量输入，WASM 仍需完整下载源文件后创建 decoder。
- 本轮只覆盖本机 HTTP 应用入口；HTTPS 反向代理、真实远端网络和 ARM64 生产主机仍需
  按部署 smoke 复验，不能由本机结果替代。
- 首帧诊断默认关闭；没有建立持续遥测或第三方监控。
