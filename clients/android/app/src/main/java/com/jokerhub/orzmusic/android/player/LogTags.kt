package com.jokerhub.orzmusic.android.player

/**
 * logcat tag 集合。用字符串常量而不是散落的字面量，是因为这些日志就是
 * 真机排查的入口：
 *
 * ```
 * adb logcat -s OrzPlayback OrzSeek
 * ```
 */
/** 播放路由（native / exo）与解码器选择。 */
const val PLAYBACK_TAG = "OrzPlayback"

/** seek 全链路：界面手势 → ViewModel → MediaController → 播放器 → 解码器。 */
const val SEEK_TAG = "OrzSeek"
