# 后台播放、通知栏、声场与 Pico 焦点

本文记录 Android 端三个和 Web 端差异最大的部分:后台播放链路、PCM 声场取样、
以及 Pico 上 BACK/焦点问题的定位与处理。

## 1. 后台播放 / MediaSession

```
Activity / ViewModel
  └─ PlaybackController(MediaController 代理)
        │  bind / 异步连接
        ▼
PlaybackService : MediaSessionService
  ├─ ExoPlayer(真正持有播放器)
  ├─ MediaSession(通知栏 / 锁屏 / 耳机线控)
  ├─ OrzMediaNotificationProvider(上一首 / 播放暂停 / 下一首)
  └─ OrzRenderersFactory
        └─ VisualizerAudioProcessor(PCM 取样)
```

- **ExoPlayer 归 service 所有**,Activity 销毁不会停止播放。
- `PlaybackController` 首次连接是异步的;连接前的命令会排队,连接后按顺序补发。
- 通知栏默认由 Media3 管理;自定义 `OrzMediaNotificationProvider` 额外提供
  上一首 / 下一首,通过 `SessionCommand` 转给 `PlaybackCommandBridge`,
  再调用 ViewModel 的 `playNext()` / `playPrevious()`。
- **权限**:
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_MEDIA_PLAYBACK`(Android 14+)
  - `POST_NOTIFICATIONS`(Android 13+;拒绝时播放继续,只是没有通知)
  - `WAKE_LOCK`
  - **不需要** `RECORD_AUDIO`
- 从最近任务划掉后:当前曲目继续播;通知栏的上一首/下一首需要 App 进程里的
  ViewModel,Activity 被销毁后这两个按钮会变成 no-op。

### 验证

```bash
# 播放后按 HOME,位置仍应前进
adb shell dumpsys media_session | grep 'state=PlaybackState'
adb shell dumpsys activity services com.jokerhub.orzmusic.android | grep -E 'isForeground|foregroundNoti'
```

实测通知：`category=transport actions=3`(上一首/播放暂停/下一首)。

## 2. 真实 PCM 声场

没有用 `android.media.audiofx.Visualizer`(那需要 `RECORD_AUDIO`),而是在
Media3 的播放管线里插入 `VisualizerAudioProcessor`:

- 通过 `DefaultRenderersFactory.buildAudioSink()` 注入;
- 在 `AudioTrack` 写入前拿到 PCM16 / float;
- 对最后 1024 帧做单声道混音 + Hann 窗 + radix-2 FFT，映射到 48 个对数频段；
- 频段做 dB → 0..1、轻微高频衰减和指数平滑，再推到 `VisualizerBus`(StateFlow)；
- Compose 的 `VisualizerBars` 用于**播放坞上方的独立声场面板**（对齐 Web `.viz-panel`）
  和全屏正在播放页。

暂停/切歌时 service 会调用 `VisualizerBus.reset()`,不会停在最后一帧。

> `AudioProcessor.queueInput()` 必须真正消费 `inputBuffer`（`inputBuffer.get(copy)`）。
> 只用 `duplicate()` 复制、不推进原 buffer，PCM 会被 AudioSink 重复处理，
> 听感是噪声/卡带而不是音乐。

## 3. Pico 手柄 / 指针

Pico 手柄在 2D 面板里的实际输入是 **触摸指针**：`getevent` 只能看到
`virtual_input_device` 的 `ABS_X/ABS_Y + BTN_TOUCH`，没有 `BTN_GAMEPAD/BTN_A`。
所以触摸点击是主路径，下面的按键映射只是兼容其他固件/外接手柄。

| 手柄事件 | App 处理 |
|---|---|
| `KEYCODE_BUTTON_A` | 先在落点合成 hover 让控件获得焦点，再发 `DPAD_CENTER`；无落点直接确认当前焦点 |
| `KEYCODE_BUTTON_B` | 走 `OnBackPressedDispatcher`，与 BACK 一致 |
| `SOURCE_JOYSTICK` 的 ABS_X/Y、HAT_X/Y | 死区 + 220ms 冷却，映射为方向键移动焦点 |
| 模拟扳机 AXIS_RTRIGGER/LTRIGGER/GAS | >0.65 且 320ms 冷却，走同一套落点 hover + center |
| 射线/鼠标指针 | 沿用 Pico 原生光标；记录落点，并让悬停控件同步 Compose 焦点 |
| 触摸 | `tapGesture`（`detectTapGestures`）显式触发 `onClick` |

相关实现：`MainActivity.dispatchKeyEvent()` / `dispatchGenericMotionEvent()`，
> **tv-material3 的 `Card` / `Button` 在 Pico 触摸下不触发 `onClick`**，但
> `Modifier.clickable` / `detectTapGestures` 正常。因此所有交互控件都额外挂了
> `Modifier.tapGesture { onClick() }`（定义在 `Components.kt`）；D-pad 仍走组件自带
> 的 `onClick`，两条路径互不重复。只加 `Card(onClick=...)` 在 Pico 上会表现为
> “能滚动、按钮没反应”。

落点记录在 `MainActivity.PointerBridge`，确认逻辑在 `confirmAtPointer()`：
先向落点合成 `ACTION_HOVER_MOVE`，等一帧让 `Components.focusOnHover` 把
Compose 焦点移过去，再发 `DPAD_CENTER`。

> **不要改成合成 `ACTION_DOWN/UP`。** 在 Pico 的 `pvr-virtual-input` 上实测
> `dispatchTouchEvent` 返回 `true`，但 Compose `clickable` 的 `onClick` 不触发；
> `handled=true` 不等于点击生效。

## 4. Pico BACK / 焦点问题

### 现象

Pico 上按 BACK 后,下一次按键可能完全不生效;`uiautomator` 里没有任何
`focused=true` 的节点。

### 实测原因

`dumpsys input` 在异常时是这样:

```
FocusedDisplayId: 0
FocusedWindows:
  displayId=64, name='...OrzMusic.../MainActivity'
  displayId=0,  name='Window{... PicoXR_CustomDialog}'
```

- App 的窗口在虚拟 display(64/66/68…),但 **`hasFocus=false`**;
- `PicoXR_CustomDialog`(`com.pvr.vrshell`, display 0)持有焦点;
- 它是 `canReceiveKeys=false` 的系统层窗口,所以按键既到不了 App,
  也不会被它自己处理 —— 表现为“BACK/方向键偶发失灵”;
- 触发点是 Pico 的系统 IME / VRSHELL 面板在 BACK 后把焦点收回 display 0。
  App 无法直接给别的 display 设置焦点；本版选择保留系统 IME，接受这一次焦点回收，
  由 `WindowFocusEvents` 做恢复。

### App 侧修复/兜底

1. App 收到 BACK 时由 `BackHandler` 处理覆盖层；只有 Pico 在 Activity
   `dispatchKeyEvent` 里提前消费，TV 保持系统 BACK / IME 的默认分发;
   (但 VRSHELL 仍可能在系统层把焦点收回 display 0,App 侧无法阻止。);
2. 覆盖层关闭后 `moveFocus` + 显式 `FocusRequester` 重选焦点;
3. 文本输入统一走**系统 IME**；不再在应用内自建键盘，也不在 `ON_RESUME` 主动
   hide / clearFocus（否则搜索框刚聚焦就被收起）；
4. 系统 IME / `PicoXR_CustomDialog` 仍可能短暂抢焦点，`WindowFocusEvents` 负责
   窗口焦点回来后重新请求当前层焦点；
5. 所有覆盖层都有屏幕上的「关闭/完成/返回」;`Esc` 也可以关闭。
   这些路径不会触发 VRSHELL 的 BACK 处理。

### 仍需知道的限制

Pico 把 BACK 作为面板级系统键处理,**无法保证 App 收到每一次 BACK**。
这是 VRSHELL 行为,不是 Compose 的 `BackHandler` 漏写。真机验收时
建议优先用屏幕上的关闭按钮 / `Esc`;TV 的 BACK 正常由 `BackHandler` 处理。
