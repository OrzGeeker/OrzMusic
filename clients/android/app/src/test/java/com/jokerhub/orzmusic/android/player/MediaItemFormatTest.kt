package com.jokerhub.orzmusic.android.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 回归测试：格式 id 必须能活着穿过 `MediaController` 的 Binder 序列化。
 *
 * 0.8.1 真机上的故障链：
 *   `PlaybackController` 把格式 id 只写在 `MediaItem` 的 `tag` 上 →
 *   Media3 1.5 的 `MediaItem.LocalConfiguration.toBundle()` 压根不写 tag →
 *   `OrzPlaybackPlayer` 认为「本机解不了」→ 把 `xm/mod/sc68` 丢给 ExoPlayer →
 *   `UnrecognizedInputFormatException: Source error`，模块音乐全部播不出来。
 *
 * 这里用 `MediaItem.toBundle()/fromBundle()` 模拟那一跳（MediaSession 传的就是 Bundle）。
 */
@RunWith(AndroidJUnit4::class)
class MediaItemFormatTest {

    /** 与 `PlaybackController.toMediaItem()` 里 `AudioSource.Local` 分支保持同构。 */
    private fun localItem(format: String, withExtras: Boolean = true): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle("XP Repair Pro")
            .setArtist("UPLiNK")
            .setGenre(format.uppercase())
        if (withExtras) formatExtras(format)?.let { metadata.setExtras(it) }
        return MediaItem.Builder()
            .setUri("file:///data/user/0/app/cache/raw/A5392C70-007E-4F4D-BFDC-1F0C640B78D0.$format")
            .setMediaId("A5392C70-007E-4F4D-BFDC-1F0C640B78D0")
            .setTag(format.lowercase())
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun roundTrip(item: MediaItem): MediaItem = MediaItem.fromBundle(item.toBundle())

    @Test
    fun formatSurvivesSessionBundleRoundTrip() {
        assertEquals("mod", mediaItemFormat(localItem("mod")))
        // 关键断言：过一趟 Bundle 之后仍然读得到 —— 这是 0.8.1 崩掉的那条路。
        assertEquals("mod", mediaItemFormat(roundTrip(localItem("mod"))))
    }

    @Test
    fun allDecoderFormatsSurviveRoundTrip() {
        for (format in listOf("sc68", "ym", "sid", "sap", "ahx", "mod", "xm", "it")) {
            assertEquals(format, mediaItemFormat(roundTrip(localItem(format))))
        }
    }

    @Test
    fun extrasAloneAreEnough() {
        // tag 在真机上必然丢，所以「只有 extras」也必须解得出来。
        assertEquals("mod", mediaItemFormat(roundTrip(localItem("mod", withExtras = true))))
    }

    @Test
    fun uriExtensionIsTheLastResort() {
        // 老播放项（没有 extras / genre）也能靠 MediaCache 的 raw/<id>.<format> 兜底。
        val bare = MediaItem.Builder()
            .setUri("file:///data/cache/raw/abc.mod")
            .setMediaId("abc")
            .build()
        assertEquals("mod", mediaItemFormat(bare))
    }

    @Test
    fun returnsNullWhenNothingIdentifiesTheFormat() {
        val remote = MediaItem.Builder()
            .setUri("https://music.jokerhub.cn/api/songs/abc/stream")
            .setMediaId("abc")
            .build()
        assertNull(mediaItemFormat(remote))
        assertNull(mediaItemFormat(null))
    }
}
