package com.jokerhub.orzmusic.core.model

import kotlinx.serialization.Serializable

/**
 * OrzMusic 后端(Vapor)的响应模型。
 *
 * 口径来源:线上 `/api/openapi.json` + `Sources/App/Models/DTOs/` 下的 `*.swift`。
 * 后端字段是 camelCase,和这里一一对应;新增字段用默认值兜底,
 * 防止旧客户端被后端升级打挂(`ignoreUnknownKeys` 在 ApiFactory 里开着)。
 */

@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    val metadata: PageMeta = PageMeta()
)

@Serializable
data class PageMeta(
    val page: Int = 1,
    val per: Int = 50,
    val total: Int = 0
)

@Serializable
data class Stats(
    val totalSongs: Int = 0,
    val totalArtists: Int = 0,
    val totalAlbums: Int = 0,
    val totalPlaylists: Int = 0,
    val totalFileSize: Long = 0
)

@Serializable
data class FormatSummary(
    val total: Int = 0,
    val formats: List<FormatCount> = emptyList()
)

@Serializable
data class FormatCount(
    val format: String = "",
    val count: Int = 0
)

@Serializable
data class Song(
    val id: String = "",
    val title: String = "",
    val artist: Artist? = null,
    val album: Album? = null,
    val fileFormat: String = "",
    val fileSize: Long = 0,
    val duration: Double? = null,
    val streamUrl: String = "",
    val rawUrl: String = "",
    val playStrategy: String = "serverDecode"
) {
    val artistName: String get() = artist?.name?.takeIf { it.isNotBlank() } ?: "未知艺术家"

    val durationText: String
        get() {
            val total = duration?.toInt() ?: return "--:--"
            if (total <= 0) return "--:--"
            val m = total / 60
            val s = total % 60
            return "%d:%02d".format(m, s)
        }

    val sizeText: String
        get() = when {
            fileSize >= 1_048_576 -> "%.1f MB".format(fileSize / 1_048_576.0)
            fileSize >= 1024 -> "%d KB".format(fileSize / 1024)
            else -> "$fileSize B"
        }
}

@Serializable
data class Artist(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val songCount: Int = 0
)

@Serializable
data class Album(
    val id: String = "",
    val title: String = "",
    val year: Int? = null,
    val artworkUrl: String? = null,
    val artist: Artist? = null,
    val songCount: Int? = null
)

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val songCount: Int? = null,
    val songs: List<Song>? = null
)

@Serializable
data class Health(
    val status: String = "",
    val version: String = "",
    val commit: String = "",
    val database: String = "",
    val cas: String = "",
    val adminApi: String = ""
)

/**
 * `GET /api/songs/{id}/location?per=`。
 *
 * 网页端的“定位当前曲目”依赖它:先拿到默认排序里的页码,再加载那一页。
 * `index` 是零基位置,`page` 是一基页码。
 */
@Serializable
data class SongLocation(
    val songId: String = "",
    val index: Int = 0,
    val page: Int = 1,
    val per: Int = 50
)

/** `POST /api/playlists` 请求体。 */
@Serializable
data class PlaylistDraft(
    val name: String,
    val description: String? = null,
    val songIds: List<String> = emptyList()
)

/**
 * 播放策略 —— 后端 `playStrategy` 的三种值。
 *
 * - [DirectFile]:mp3/ogg/flac/m4a/aac、PCM WAV,服务端直接给原文件,ExoPlayer 能放。
 * - [ServerDecode]:sc68、压缩 WAV,服务端转成 PCM WAV 再给,ExoPlayer 也能放。
 * - [WasmDecode]:mod/xm/it/s3m/nsf/sid... 服务端**只给原始文件**,
 *   解码发生在浏览器 WASM 里。原生客户端没有解码器时放不了 —— 见 docs/12。
 */
enum class PlayStrategy(val wire: String) {
    DirectFile("directFile"),
    ServerDecode("serverDecode"),
    WasmDecode("wasmDecode");

    companion object {
        fun from(wire: String?): PlayStrategy =
            entries.firstOrNull { it.wire == wire } ?: ServerDecode
    }
}

val Song.strategy: PlayStrategy get() = PlayStrategy.from(playStrategy)
