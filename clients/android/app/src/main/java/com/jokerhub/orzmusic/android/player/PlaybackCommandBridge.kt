package com.jokerhub.orzmusic.android.player

/**
 * MediaSession 通知栏的自定义“上一首/下一首” → ViewModel 队列。
 *
 * 通知栏按钮最终由 PlaybackService 的 SessionCallback 收到；
 * 这里把命令桥接给仍存活的 ViewModel（Activity 退到后台时 VM 仍然在）。
 * Activity 被销毁后回调为空，通知栏只能控制当前曲目。
 */
object PlaybackCommandBridge {
    @Volatile var onNext: (() -> Unit)? = null
    @Volatile var onPrevious: (() -> Unit)? = null

    fun next() {
        onNext?.invoke()
    }

    fun previous() {
        onPrevious?.invoke()
    }
}
