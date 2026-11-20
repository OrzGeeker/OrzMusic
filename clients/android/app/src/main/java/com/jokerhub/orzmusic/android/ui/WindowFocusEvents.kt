package com.jokerhub.orzmusic.android.ui

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Activity 窗口焦点回来时通知 Compose 重新选择焦点。
 *
 * Pico 的 VRSHELL 可能把 FocusedDisplayId 抢回 display 0；用户点回 App 后，
 * Activity 会收到 onWindowFocusChanged(true)，但 Compose 内部的焦点节点
 * 不会自动恢复。这个事件用于补一次 FocusRequester。
 */
object WindowFocusEvents {
    val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun notifyFocus() {
        events.tryEmit(Unit)
    }
}
