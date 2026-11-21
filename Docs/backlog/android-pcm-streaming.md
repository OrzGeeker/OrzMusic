# Android 流式 PCM 播放（已实现，待真机验收）

> 相关：[`Docs/android-decoder-packaging.md`](../android-decoder-packaging.md)

## 为什么改

现状是「整曲转 WAV」：

```
/api/songs/:id/raw → cache/raw/<id>.<fmt>
        ↓ JNI：全曲 render 一遍
   cache/pcm/<id>-<ns>.wav      ← 44.1kHz stereo ≈ 10MB/分钟
        ↓
     ExoPlayer（当普通文件播）
```

三个问题，随 `android-full` profile 一起放大：

1. **首播延迟 = 全曲解码时间**：`sc68`（120 首）与长 `xm` 尤其明显，`sid` 也多。
2. **磁盘**：`MAX_PCM_CACHE_BYTES` 已经是 300MB，全量本地化后会被反复淘汰重解。
3. **seek 只能落在 WAV 字节偏移**，用不上 `orz_decoder_seek`；subsong 完全没有入口。

## 已完成（audio-kit / 播放设备层）

| 文件 | 作用 |
|---|---|
| `audio-kit/.../PcmStream.kt` | 解码线程 + 有界 ring buffer（默认 2 秒）。**所有** `AudioDecoder` 调用都在解码线程上串行执行；`seekTo` / `selectSubsong` 投递请求并等回执 |
| `app/.../player/PcmAudioSink.kt` | `AudioTrack`（`ENCODING_PCM_FLOAT`）流式输出，250ms 硬件缓冲，暴露 `playedMs` / `flush()` |
| `app/.../player/MediaCache.rawFor()` | 只下载/缓存原始文件，不生成 WAV |
| `orz_jni.c` `nativeCancel` | 关闭顺序：`cancel()` → `join()` → `destroy()`，避免 render 与释放竞争 |
| `app/.../player/NativePcmPlayback.kt` | 会话：读 raw → `createDecoder` → `PcmStream` → `AudioTrack`，写线程 + 位置/seek/subsong/音量 |
| `app/.../player/OrzPlaybackPlayer.kt` | Media3 `SimpleBasePlayer`：本地解码与 ExoPlayer 两条腿，MediaSession 直接绑它 |
| `app/.../player/PlaybackService.kt` | 不再直接持有 ExoPlayer，改持有 `OrzPlaybackPlayer` |
| `app/.../ui/AppViewModel.kt` | `PlaybackRoute.Native` 只下载 raw，不再等整曲解码 |

`PcmStream.close()` 里如果解码线程 2 秒内没退出，就**不**销毁句柄（宁可泄漏一次，
也不要在 `renderF32` 还没返回时 `orz_decoder_destroy_v1`）。

## Media3 桥（已实现）

`app/.../player/OrzPlaybackPlayer.kt`：`SimpleBasePlayer` 子类，内部两条腿——
模块/芯片音乐走 `NativePcmPlayback`（`PcmStream` + `AudioTrack`），容器音频走内部
`ExoPlayer`。`PlaybackService` 的 `MediaSession` 直接绑它，所以通知栏 / 锁屏 /
耳机线控行为不变。

判定条件：`MediaItem.localConfiguration.tag` = 格式 id（`PlaybackController` 写入）
且 `OrzAudioCore.supports(format)`（运行时探测）且 URI 是 `file://`。
否则回落到 ExoPlayer —— 旧的服务端 WAV 路径仍然可用。

实现要点（与原计划一致，补几条落地细节）：

- `getState()` 每 250ms `invalidateState()` 刷新位置；位置取自 `AudioTrack` 播放头
  （暂停/恢复不跳），seek 后重置基准。
- `handleStop()` / 切歌先释放 native 会话（`PcmStream.close()` 内部 cancel → join → destroy）。
- 声场：`VisualizerAudioProcessor` 增加 `analyzeInterleaved(...)`，两条腿共用同一套
  FFT/平滑逻辑（线程安全）；ExoPlayer 那条腿仍走 AudioSink processor。
- 曲目结束：native 读到 EOF → `STATE_ENDED` → MediaSession `onEnded` → `playNext()`。
- 已知未覆盖：ENDED 之后直接 `play()` 不会自动回到开头（App 每次都先 `stop()`
  再 `setMediaItem`，所以不影响现有流程）。

## 设计说明（原计划，与实现对照）

这是唯一还没落地的一块：ExoPlayer 不能喂 float PCM 流，而通知栏 / 锁屏 / 耳机线控
都挂在 `MediaSession` 上，所以不能简单再起一个第二个播放器。

做法：`PlaybackService` 里的 `ExoPlayer` 换成 `SimpleBasePlayer` 子类
（media3 1.2+ 提供，专门给自定义播放器接 MediaSession 用），内部两条腿：

```kotlin
class OrzPlaybackPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val remote = ExoPlayer.Builder(context).build()   // directFile / serverDecode
    private var pcm: PcmStream? = null                        // Native 路由
    private var sink: PcmAudioSink? = null

    fun playNative(file: File, format: String) {
        pcm?.close(); sink?.close()
        val decoder = OrzAudioCore.createDecoder(file.readBytes(), format)
        val stream = PcmStream(decoder).also { it.start() }
        val out = PcmAudioSink(stream.sampleRate, stream.channels)
        pcm = stream; sink = out
        out.play()
        // 独立线程：read() → write()，读到 0 且 isEnded 时结束并回调 onEnded
    }

    override fun getState(): State = /* 汇总 remote 或 pcm 的位置/时长/播放态 */

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
        /* Native：sink.flush(); pcm.seekTo(ms)；Remote：remote.seekTo()        */
}
```

要点：

- `SimpleBasePlayer.getState()` 必须是主线程安全的快照；`PcmStream` 的位置从
  `deliveredFrames` 读，不要读 `AudioTrack.playbackHeadPosition`（会回绕）。
- 写入线程不能是主线程，也不能是 `Dispatchers.Main`；用 `Dispatchers.IO` 或专用线程，
  `AudioTrack.write(..., WRITE_BLOCKING)` 自己会阻塞。
- `handleSetPlayWhenReady` / `handlePause` 要同步 `sink.pause()`；`sink.flush()` **只能**
  在 pause 之后调用。
- `VisualizerAudioProcessor` 目前挂在 ExoPlayer 的 `AudioSink` 上；走 Native 路由时
  需要改成在 `sink.write()` 之前跑同一套采样逻辑，否则声场在模块音乐上没有数据。
- 曲目结束：`PcmStream.isEnded && read() == 0` 时置 `STATE_ENDED`，让 MediaSession 触发
  `onEnded` → ViewModel `playNext()`。

## 已知坑：格式 id 过不了 MediaSession

原生路由的判定依赖 `OrzPlaybackPlayer` 从 `MediaItem` 读到的格式 id。**别把它只放在
`MediaItem.Builder.setTag()` 上**：Media3 的 `MediaItem.LocalConfiguration.toBundle()`
不序列化 tag（只写 uri/mimeType/drm/ads），而
`PlaybackController → MediaController → PlaybackService` 这一跳传的就是 Bundle。
0.8.1 就是因此在真机上把所有模块音乐判成「本机解不了」，`.mod`/`.sc68` 被丢给
ExoPlayer 报 `UnrecognizedInputFormatException: Source error`。

现行做法（0.8.2）见 `app/.../player/MediaItemFormat.kt`：格式 id 同时写进
`MediaMetadata.extras`，读取顺序 tag → extras → genre → URI 扩展名
（`MediaCache` 落盘是 `raw/<songId>.<format>`）。
回归覆盖：单测 `MediaItemFormatTest`（`toBundle()/fromBundle()` 复现那一跳）+
设备端 `PlaybackSessionNativeRoutingTest`（走生产链路播 `.mod`）。

## 验收清单（真机）

- [ ] `xm` / `mod` 首播 < 500ms（旧路径数秒）
- [ ] `sc68` 一首歌开始播放 < 1s，且不再写 `cache/pcm/`
- [ ] seek 到 50% 后位置条与服务端 `duration` 一致
- [ ] 静音 → 后台 → 锁屏继续播；通知栏切歌正常
- [ ] 切歌时 `PcmStream` 全部 `close()`，无残留线程（`adb shell ps -T`)
- [ ] 未装 decoder 的格式仍然走「引导到网页播放器」
- [ ] 强制走旧路径（`decodeToWav`）仍可用作回退开关
