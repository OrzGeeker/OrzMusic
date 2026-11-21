package com.jokerhub.orzmusic.core.playback

import com.jokerhub.orzmusic.core.api.OrzMusicTransport
import com.jokerhub.orzmusic.core.model.Album
import com.jokerhub.orzmusic.core.model.Artist
import com.jokerhub.orzmusic.core.model.FormatSummary
import com.jokerhub.orzmusic.core.model.Health
import com.jokerhub.orzmusic.core.model.Page
import com.jokerhub.orzmusic.core.model.Playlist
import com.jokerhub.orzmusic.core.model.PlaylistDraft
import com.jokerhub.orzmusic.core.model.Song
import com.jokerhub.orzmusic.core.model.SongLocation
import com.jokerhub.orzmusic.core.model.Stats
import com.jokerhub.orzmusic.core.util.UrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {

    private val transport = object : OrzMusicTransport {
        override val baseUrl = "https://music.example.com/"
        override fun updateBaseUrl(raw: String) = Unit
        override fun absolute(path: String) = UrlResolver.resolve(baseUrl, path)
        override suspend fun stats(): Stats = error("unused")
        override suspend fun health(): Health = error("unused")
        override suspend fun formats(): FormatSummary = error("unused")
        override suspend fun songs(page: Int, per: Int, format: String?): Page<Song> = error("unused")
        override suspend fun search(query: String, format: String?): List<Song> = error("unused")
        override suspend fun artists(page: Int, per: Int): Page<Artist> = error("unused")
        override suspend fun artistSongs(artistId: String, page: Int, per: Int): Page<Song> = error("unused")
        override suspend fun albums(page: Int, per: Int): Page<Album> = error("unused")
        override suspend fun playlists(): List<Playlist> = error("unused")
        override suspend fun playlist(id: String): Playlist = error("unused")
        override suspend fun songLocation(songId: String, per: Int): SongLocation = error("unused")
        override suspend fun createPlaylist(draft: PlaylistDraft): Playlist = error("unused")
        override suspend fun deletePlaylist(id: String) = Unit
    }

    private fun song(
        format: String,
        strategy: String,
        streamUrl: String = "/api/songs/1/stream",
        rawUrl: String = "/api/songs/1/raw"
    ) = Song(
        id = "1",
        title = "x",
        fileFormat = format,
        playStrategy = strategy,
        streamUrl = streamUrl,
        rawUrl = rawUrl
    )

    @Test
    fun directFileGoesToRemoteStream() {
        val route = PlaybackPolicy.route(song("mp3", "directFile"), transport)
        assertEquals(
            PlaybackRoute.Remote("https://music.example.com/api/songs/1/stream"),
            route
        )
    }

    @Test
    fun serverDecodeFallsBackToRemoteWhenLocalDecoderMissing() {
        // 压缩 WAV 不在 decoder 清单里 —— 只能让服务端转码。
        val route = PlaybackPolicy.route(song("wav", "serverDecode"), transport)
        assertEquals(
            PlaybackRoute.Remote("https://music.example.com/api/songs/1/stream"),
            route
        )
    }

    @Test
    fun serverDecodeGoesNativeWhenLocalDecoderPresent() {
        // native-first:本机能解 sc68 时不再让服务端转 WAV。
        val route = PlaybackPolicy.route(song("sc68", "serverDecode"), transport)
        assertEquals(
            PlaybackRoute.Native("https://music.example.com/api/songs/1/raw"),
            route
        )
    }

    @Test
    fun supportedWasmFormatGoesToNativeRaw() {
        val route = PlaybackPolicy.route(song("mod", "wasmDecode"), transport)
        assertEquals(
            PlaybackRoute.Native("https://music.example.com/api/songs/1/raw"),
            route
        )
    }

    @Test
    fun unsupportedWasmFormatIsReported() {
        // vgm 不在 OrzAudioCore 的 decoder 清单里 —— 只能回落网页播放器。
        val route = PlaybackPolicy.route(song("vgm", "wasmDecode"), transport)
        assertTrue(route is PlaybackRoute.Unsupported)
        assertEquals("vgm", (route as PlaybackRoute.Unsupported).format)
    }

    @Test
    fun manifestCoversSdkProfile() {
        // 清单是「这个 SDK 版本提供哪些格式」,不是「本机能不能解」。
        assertTrue(DecoderManifest.supports("mod"))
        assertTrue(DecoderManifest.supports("XM"))
        assertTrue(DecoderManifest.supports("nsf"))
    }

    @Test
    fun runtimeManifestFiltersMissingDecoders() {
        val installed = setOf("xm", "mod")
        val deviceManifest = object : DecoderManifestSource {
            override val formats = DecoderManifest.formats.filter { it.id in installed }
            override fun supports(format: String) = format.lowercase() in installed
        }

        assertEquals(
            PlaybackRoute.Native("https://music.example.com/api/songs/1/raw"),
            PlaybackPolicy.route(song("mod", "wasmDecode"), transport, deviceManifest)
        )
        assertTrue(
            PlaybackPolicy.route(song("nsf", "wasmDecode"), transport, deviceManifest)
                is PlaybackRoute.Unsupported
        )
    }
}
