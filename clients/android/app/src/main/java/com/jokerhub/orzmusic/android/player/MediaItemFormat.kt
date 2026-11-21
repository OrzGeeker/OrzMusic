package com.jokerhub.orzmusic.android.player

import android.os.Bundle
import androidx.media3.common.MediaItem

/**
 * 播放项里的「格式 id」怎么带过去、怎么读回来。
 *
 * 背景（0.8.1 真机踩到）：`OrzPlaybackPlayer` 靠在 `MediaItem` 上带的格式 id
 * 判断走本地解码还是 ExoPlayer。但真实链路是
 * `AppViewModel → PlaybackController → MediaController(Binder) → PlaybackService`，
 * 而 Media3 1.5 的 `MediaItem.LocalConfiguration.toBundle()` **根本不写 `tag`**
 * （只写 uri / mimeType / drm / ads / subsampleOffsetUs），所以 String tag 在这一跳
 * 必然丢失。于是模块音乐全被判成「不可本地解码」，被丢给 ExoPlayer，报
 * `UnrecognizedInputFormatException: Source error`（`xm/mod/sc68` 全都放不出来）。
 *
 * 所以格式 id 以 `MediaMetadata.extras`（Bundle，能过 Binder）为准，
 * `tag` 只当同进程的快捷路径，最后用缓存文件名里的扩展名兜底
 * （`MediaCache` 落盘是 `raw/<songId>.<format>`）。
 */
internal const val MEDIA_EXTRA_FORMAT = "com.jokerhub.orzmusic.extra.FORMAT"

/** 写进 MediaMetadata：跨进程也能到。 */
internal fun formatExtras(format: String): Bundle? =
    format.takeIf { it.isNotBlank() }
        ?.let { Bundle().apply { putString(MEDIA_EXTRA_FORMAT, it.lowercase()) } }

/**
 * 读回格式 id。顺序：tag（同进程路径）→ extras（跨进程可靠）→ genre → URI 扩展名。
 *
 * `localConfiguration` 可能整体缺失（跨进程还原不完整时），所以每一步都要 null 安全。
 */
internal fun mediaItemFormat(item: MediaItem?): String? {
    item ?: return null
    (item.localConfiguration?.tag as? String)
        ?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
    item.mediaMetadata.extras?.getString(MEDIA_EXTRA_FORMAT)
        ?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
    // genre 是 CharSequence，不是 String。
    item.mediaMetadata.genre?.toString()
        ?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
    return item.localConfiguration?.uri?.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()
}
