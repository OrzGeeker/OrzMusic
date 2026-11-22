# Android 流式 PCM 播放（已实现；真机验收状态见文末）

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

## 已知坑：Compose 的 onKeyEvent 必须写在 focusable 之前

进度条「点不动、左右键也没反应」的根因不是 Pico，而是 modifier 顺序：

```kotlin
// ✗ 永远收不到按键：处理器在焦点节点内部，而按键只会冒泡给祖先
Modifier.focusable().onKeyEvent { ... }
// ✓
Modifier.onKeyEvent { ... }.focusable()
```

再叠加一层：Pico 没有真实触摸，扳机是「射线 hover → `DPAD_CENTER`」
（`MainActivity.confirmAtPointer`），所以 `detectTapGestures` 那条路在头显里是死的。
`SeekBar` 现在用 `RayPointer`（窗口坐标，每次 hover 更新）换算落点比例，
`onGloballyPositioned` 记录控件位置，确认键直接跳到落点。

排查入口：`adb logcat -s OrzSeek` 能看到 界面手势 → `vm.seekTo(duration=…)` →
`controller.seekTo(connected=…)` → `player.handleSeek native(… applied=…)`。

## seek 的能力边界（2026-11-22 真机确认）

`orz_decoder_seek` 只有 openmpt 系解码器实现了（`mod/xm/it/s3m/mo3/mtm`），
`sc68 / sid / gme(NSF·SPC·GBS…)` 这些的 `orz_stream_info.capabilities` 里
**没有** `ORZ_CAP_SEEK` 位，调用直接返回 `ORZ_ERROR_UNSUPPORTED_OPERATION`
（真机日志：`player.handleSeek native(90000) applied=false`，进度条弹回原位）。

现在 `NativePcmPlayback.seekTo()` 两条腿：

1. `stream.isSeekable`（= `caps & ORZ_CAP_SEEK`）为真 → `orz_decoder_seek`；
2. 否则**重开解码器把 PCM 渲染丢弃到目标位置**（`seekByRendering`）。
   这类解码器渲得极快，实测 sc68 跳 90 秒只要 ~22ms（约 4000x 实时），
   所以用户视角没有区别。真机验证：`applied=true skipRender=true now=90000`。

回归：`PlaybackEngineBehaviourTest.seekFallsBackToRenderSkipWhenDecoderCannotSeek`
（用 `forceSkipSeek` 强制走兜底路径，不需要网络）。

## 进度条交互的最终形态（0.8.10 定案）

支持的三种操作，全部走指针，不依赖 Compose 焦点：

| 操作 | 实现 |
|---|---|
| 点击轨道 | `detectTapGestures` → `onSeek(落点比例)` |
| 拖动 | 拖动中只更新本地预览（跟手），**松手才 seek 一次**；位置追上后交回真实值 |
| 射线悬停 + 扳机 | `onKeyEvent` 里的 `DirectionCenter/Enter` → `RayPointer.fractionX()` 换算落点 |

**左右键调进度已下线，不要再加回来**：Pico 的十字键是以 `SOURCE_JOYSTICK`
轴值送进来的，`MainActivity.dispatchGenericMotionEvent` 会把它合成成 DPAD 键
并用 `sendTapKey()` 直接 `super.dispatchKeyEvent(...)`——绕过 Activity 层的任何判定；
而 Compose 那条路要求进度条持有焦点，真机上 hover 拿不到焦点（logcat 里没有
`focus@`）。两条路都试过（0.8.4–0.8.9），既要稳定又要不和拖动抢事件做不到，
用户已决定只保留拖动/点击/射线确认。要调 ±5s 请用正在播放页的 `−15s/−5s/+5s/+15s`
按钮（它们走 `vm.seekBy`）。

## 验收状态（2026-11-22 复核）

不是全部都在真机上量过；下表把「真机实测」「仪器化/代码层覆盖」「还没测」分开写，
避免再挂一排看不出进度的空 checkbox。

| 验收项 | 状态 | 证据 |
|---|---|---|
| `xm` / `mod` 首播 < 500ms | ✅ 真机 | 0.8.3 在 PICO A8110 量到 `play→playing=140ms`（[CHANGELOG](../../clients/android/CHANGELOG.md) 0.8.3 段）；当时未按格式分别记录，`xm`/`mod` 各自的首播值没有单独留档 |
| `sc68` 首播 < 1s | ⚠️ 未单独量测 | 真机只量了模块曲路径；`sc68` 只有 seek 的渲染跳过记录（`applied=true skipRender=true now=90000`，同 CHANGELOG）。原理上首播不再等全曲解码，但没有实测数字 |
| 不再写 `cache/pcm/` | ✅ 代码路径 | Native 路由只调 `MediaCache.rawFor()`（`AppViewModel.kt:468`）；写 WAV 的唯一调用点 `MediaCache.wavFor()`（内部 `decodeToWav`）现在**没有任何调用者** |
| seek 到 50% 后位置条与服务端 `duration` 一致 | ✅ 仪器化 + 真机 | `app/src/androidTest/.../PlaybackEngineBehaviourTest.kt` 的 `seekJumpsToRequestedPositionAndKeepsPlaying`（位置追上目标值后还继续前进）；0.8.3 真机 `1530 → 30000 → 30786ms` |
| 静音 → 后台 → 锁屏继续播；通知栏切歌正常 | ⚠️ 待真机复测 | 桥接层没问题（`MediaSession` 绑 `OrzPlaybackPlayer`），但 0.8.11–0.8.12 改过对外宣称的传输命令（去掉 `COMMAND_SEEK_BACK/FORWARD`），这次改动之后没有完整跑一遍后台/锁屏组合 |
| 切歌时 `PcmStream` 全部 `close()`，无残留线程 | ✅ 真机 | 0.8.3 真机日志：切歌后无 `orz-pcm*` / `orz-native*` 残留线程 |
| 未装 decoder 的格式仍走「引导到网页播放器」 | ✅ 代码路径 | `PlaybackRoute.Unsupported` → `AppViewModel.showUnsupported()` 提示「可以先在网页播放器里播放」；正在播放页有「在网页播放器打开」入口（`NowPlayingScreen.kt:336`） |
| 强制走旧路径（`decodeToWav`）仍可用作回退开关 | ❌ 已失效 | `OrzAudioCore.decodeToWav()` 便利层还在，但唯一调用点 `MediaCache.wavFor()` 已无调用者 —— 现在它是死代码，**不是**开关。要留就先接一个显式开关（如调试项），不要就删掉 `wavFor()` |

结论：只有 2 项还需要人：`sc68` 首播实测 + 改过 MediaSession 命令表后的一次后台/锁屏真机回归；
「回退开关」需要先决策要留还是删。
