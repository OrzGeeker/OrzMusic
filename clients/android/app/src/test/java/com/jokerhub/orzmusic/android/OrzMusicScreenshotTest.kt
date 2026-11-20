package com.jokerhub.orzmusic.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.jokerhub.orzmusic.android.ui.OrzTheme
import com.jokerhub.orzmusic.android.ui.QueueDrawer
import com.jokerhub.orzmusic.android.ui.screens.LibraryContent
import com.jokerhub.orzmusic.android.ui.screens.NowPlayingScreen
import com.jokerhub.orzmusic.core.model.Artist
import com.jokerhub.orzmusic.core.model.FormatCount
import com.jokerhub.orzmusic.core.model.Playlist
import com.jokerhub.orzmusic.core.model.Song
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM 截图测试 —— 不碰设备。
 *
 * 只渲染曲库页(无状态 composable),不碰网络 / ViewModel;
 * 这样 CI 和本地都能稳定出图。
 *
 * ```bash
 * ./gradlew testDebugUnitTest
 * # → app/build/outputs/roborazzi/orzmusic-tv.png / orzmusic-phone.png
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h720dp-240dpi")
class OrzMusicScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val songs = listOf(
        Song(
            id = "1",
            title = "Car Mechanic Simulator 2015",
            artist = Artist(id = "a", name = "UPLiNK"),
            fileFormat = "mod",
            fileSize = 15_630,
            duration = 76.8,
            playStrategy = "wasmDecode"
        ),
        Song(
            id = "2",
            title = "Half Side Products",
            artist = Artist(id = "a", name = "UPLiNK"),
            fileFormat = "xm",
            fileSize = 37_104,
            duration = 35.8,
            playStrategy = "wasmDecode"
        ),
        Song(
            id = "3",
            title = "Demo Track",
            artist = Artist(id = "b", name = "Nelly"),
            fileFormat = "mp3",
            fileSize = 3_200_000,
            duration = 201.0,
            playStrategy = "directFile"
        )
    )

    private val formats = listOf(
        FormatCount("xm", 3396),
        FormatCount("mod", 1093),
        FormatCount("it", 254),
        FormatCount("mp3", 15),
        FormatCount("ogg", 41)
    )

    private fun render(shellWide: Boolean? = null) {
        rule.setContent {
            OrzTheme {
                LibraryContent(
                    songs = songs,
                    formats = formats,
                    selectedFormat = null,
                    totalSongs = 5533,
                    totalArtists = 713,
                    loading = false,
                    isSearching = false,
                    searchQuery = "",
                    currentId = "1",
                    isPlaying = true,
                    locatedSongId = null,
                    onFormat = {},
                    onSong = { _, _ -> },
                    onLoadMore = {},
                    onClearSearch = {},
                    shellWide = shellWide,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF080C10))
                )
            }
        }
    }

    @Test
    fun rendersOnTvSize() {
        render()
        rule.onNodeWithText("Car Mechanic Simulator 2015").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/orzmusic-tv.png")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun rendersOnPhoneSize() {
        render()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/orzmusic-phone.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-240dpi")
    fun rendersOnTabletPortrait() {
        render(shellWide = false)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/orzmusic-tablet.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-240dpi")
    fun rendersOnTabletLandscape() {
        render(shellWide = true)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/orzmusic-tablet-landscape.png")
    }

    @Test
    fun rendersQueueDrawer() {
        rule.setContent {
            OrzTheme {
                QueueDrawer(
                    queue = songs,
                    currentId = "1",
                    playlists = listOf(
                        Playlist(id = "p1", name = "Chip Mix", songCount = 3)
                    ),
                    onClose = {},
                    onPlay = {},
                    onRemove = {},
                    onSavePlaylist = {},
                    onLoadPlaylist = {},
                    onDeletePlaylist = {}
                )
            }
        }
        rule.onNodeWithText("播放队列").assertIsDisplayed()
        rule.onNodeWithText("Chip Mix").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/orzmusic-queue.png")
    }

    private fun renderNowPlaying() {
        val focus = FocusRequester()
        rule.setContent {
            OrzTheme {
                NowPlayingScreen(
                    song = songs.first(),
                    isPlaying = true,
                    decoding = false,
                    notice = null,
                    positionMs = 42_000,
                    durationMs = 76_800,
                    volume = 0.7f,
                    levels = FloatArray(48) { 0.25f + (it % 7) * 0.08f },
                    queueCount = 3,
                    serverUrl = "https://music.example.com/",
                    onBack = {},
                    onToggle = {},
                    onPrevious = {},
                    onNext = {},
                    onSeek = {},
                    onSeekBy = {},
                    onVolume = {},
                    onMute = {},
                    onQueue = {},
                    onLocate = {},
                    focusRequester = focus,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF080C10))
                )
            }
        }
    }

    @Test
    fun rendersNowPlaying() {
        renderNowPlaying()
        rule.onNodeWithText("Car Mechanic Simulator 2015").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/orzmusic-nowplaying.png")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun rendersNowPlayingOnPhoneSize() {
        renderNowPlaying()
        rule.onNodeWithText("Car Mechanic Simulator 2015").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/orzmusic-nowplaying-phone.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-240dpi")
    fun rendersNowPlayingOnTablet() {
        renderNowPlaying()
        rule.onNodeWithText("Car Mechanic Simulator 2015").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/orzmusic-nowplaying-tablet.png")
    }
}
