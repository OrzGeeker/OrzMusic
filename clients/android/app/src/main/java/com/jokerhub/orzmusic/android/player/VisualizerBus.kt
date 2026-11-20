package com.jokerhub.orzmusic.android.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放管线 → UI 的声场数据总线。
 *
 * [VisualizerAudioProcessor] 在 Media3 的 PCM 管线里采样，
 * 这里只负责把 0..1 的柱状电平推给 Compose。Activity 和
 * PlaybackService 在同一进程，所以不需要 Binder / 额外权限。
 */
object VisualizerBus {

    const val BAR_COUNT = 48

    private val _levels = MutableStateFlow(FloatArray(BAR_COUNT))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    internal fun publish(values: FloatArray) {
        _levels.value = values
    }

    fun reset() {
        _levels.value = FloatArray(BAR_COUNT)
    }
}
