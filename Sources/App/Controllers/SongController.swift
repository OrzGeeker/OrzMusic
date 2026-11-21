import Foundation
import Vapor
import Fluent
import FluentSQL
import OrzAudioKit

struct SongController: RouteCollection {

    func boot(routes: any RoutesBuilder) throws {
        let songs = routes.grouped("api", "songs")
        songs.get(use: index)
        songs.get("formats", use: formats)
        songs.get("search", use: search)
        songs.group(":id") { song in
            song.get(use: show)
            song.get("stream", use: stream)
            song.get("raw", use: raw)
            song.get("location", use: location)
        }
    }

    struct FormatCount: Content {
        let format: String
        let count: Int
    }

    struct FormatSummary: Content {
        let total: Int
        let formats: [FormatCount]
    }

    /// GET /api/songs/formats — total and per-format counts for library navigation.
    @Sendable
    func formats(req: Request) async throws -> FormatSummary {
        struct AggregateRow: Decodable {
            let format: String
            let count: Int
        }

        guard let sql = req.db as? any SQLDatabase else {
            throw Abort(.internalServerError, reason: "Configured database does not support SQL aggregation")
        }
        let rows = try await sql.raw(
            "SELECT LOWER(file_format) AS format, COUNT(*) AS count " +
            "FROM songs GROUP BY LOWER(file_format) ORDER BY format"
        ).all(decoding: AggregateRow.self)

        return FormatSummary(
            total: rows.reduce(0) { $0 + $1.count },
            formats: rows.map { FormatCount(format: $0.format, count: $0.count) }
        )
    }

    /// 统一默认排序：createdAt DESC, id DESC（index 与 location 共用）
    private func defaultSort(_ query: QueryBuilder<Song>) -> QueryBuilder<Song> {
        query.sort(\.$createdAt, .descending).sort(\.$id, .descending)
    }

    /// GET /api/songs — 歌曲列表（分页，支持 &format= 过滤）
    @Sendable
    func index(req: Request) async throws -> Page<SongResponse> {
        var query = Song.query(on: req.db)
            .with(\.$artist)
            .with(\.$album)

        query = defaultSort(query)

        if let format = req.query[String.self, at: "format"], !format.isEmpty {
            query = query.filter(\.$fileFormat == format.lowercased())
        }

        let page = try await query.paginate(for: req)

        return .init(
            items: page.items.map { SongResponse(song: $0) },
            metadata: page.metadata
        )
    }

    /// GET /api/songs/search?q= — 搜索（歌曲名 + 艺术家名）
    @Sendable
    func search(req: Request) async throws -> [SongResponse] {
        guard let rawQuery = req.query[String.self, at: "q"], !rawQuery.trimmingCharacters(in: .whitespaces).isEmpty else {
            return []
        }

        let normalizedQuery = rawQuery.trimmingCharacters(in: .whitespaces)
        guard !normalizedQuery.isEmpty else { return [] }
        let pattern = "%\(normalizedQuery.lowercased())%"
        let searchFilter: SQLQueryString = """
            id IN (
                SELECT id FROM songs WHERE LOWER(title) LIKE \(bind: pattern)
                UNION
                SELECT songs.id FROM songs
                 JOIN artists ON artists.id = songs.artist_id
                 WHERE LOWER(artists.name) LIKE \(bind: pattern)
            )
            """

        var query = Song.query(on: req.db)
            .with(\.$artist)
            .with(\.$album)
        if let format = req.query[String.self, at: "format"]?.trimmingCharacters(in: .whitespacesAndNewlines), !format.isEmpty {
            query = query.filter(\.$fileFormat == format.lowercased())
        }
        query = query
            .filter(.sql(embed: searchFilter))
        let songs = try await query.limit(50).all()
        return songs.map { SongResponse(song: $0) }
    }

    /// GET /api/songs/:id — 歌曲详情
    @Sendable
    func show(req: Request) async throws -> SongResponse {
        guard let song = try await Song.find(req.parameters.get("id"), on: req.db) else {
            throw Abort(.notFound)
        }
        try await song.$artist.load(on: req.db)
        try await song.$album.load(on: req.db)

        return SongResponse(song: song)
    }

    /// GET /api/songs/:id/stream — 音频流
    @Sendable
    func stream(req: Request) async throws -> Response {
        guard let song = try await Song.find(req.parameters.get("id"), on: req.db) else {
            throw Abort(.notFound)
        }

        guard let format = AudioFormat(rawValue: song.fileFormat) else {
            throw Abort(.badRequest, reason: "Unknown format: \(song.fileFormat)")
        }

        let cas = req.application.casStorage
        let fullPath = cas.resolve(sha256: song.sha256, format: song.fileFormat)

        guard FileManager.default.fileExists(atPath: fullPath) else {
            throw CasError.fileNotFound(sha256: song.sha256, format: song.fileFormat)
        }

        let engine = AudioEngine()
        let requestedSubsong = req.query[Int.self, at: "subsong"] ?? 0
        guard requestedSubsong >= 0, requestedSubsong <= Int(Int32.max) else {
            throw Abort(.badRequest, reason: "Invalid subsong")
        }
        let subsong = requestedSubsong
        let strategy = engine.resolveStreamStrategy(filePath: fullPath, format: format)

        switch strategy {
        case .directFile(let path, let mime):
            let res = try await req.fileio.asyncStreamFile(at: path)
            res.headers.replaceOrAdd(name: .contentType, value: mime)
            return res

        case .wasmDecode(let path, _):
            return try await req.fileio.asyncStreamFile(at: path)

        case .serverDecode(let path, let fmt):
            // 命中统计：`make warm-decode-cache MIN_HITS=…` 据此收敛预热范围。
            // 用 detached task 计数，不把写文件放进请求路径。
            let hits = req.application.decodeHits
            Task.detached(priority: .utility) {
                await hits.record(format: fmt.rawValue)
            }
            // 尝试从转码缓存读取（避免重复 ffmpeg）
            let cachePath = try await cachedDecode(
                originalPath: path,
                sha256: song.sha256,
                format: fmt,
                subsong: subsong,
                cas: req.application.casStorage,
                coordinator: req.application.decodeCacheCoordinator,
                logger: req.logger
            )
            let lease = try DecodedCacheFileLease.acquireShared(for: cachePath)
            let res = try await req.fileio.asyncStreamFile(at: cachePath, onCompleted: { _ in
                lease.release()
            })
            res.headers.replaceOrAdd(name: .contentType, value: "audio/wav")
            return res
        }
    }

    /// GET /api/songs/:id/raw — 原始文件下载
    @Sendable
    func raw(req: Request) async throws -> Response {
        guard let song = try await Song.find(req.parameters.get("id"), on: req.db) else {
            throw Abort(.notFound)
        }

        let cas = req.application.casStorage
        let fullPath = cas.resolve(sha256: song.sha256, format: song.fileFormat)

        guard FileManager.default.fileExists(atPath: fullPath) else {
            throw Abort(.notFound)
        }

        return try await req.fileio.asyncStreamFile(at: fullPath)
    }

    /// GET /api/songs/:id/location?per=50 — 曲目在默认排序（createdAt DESC, id DESC）中的位置
    @Sendable
    func location(req: Request) async throws -> SongLocationResponse {
        guard let songId = req.parameters.get("id", as: UUID.self) else {
            throw Abort(.notFound)
        }
        guard try await Song.find(songId, on: req.db) != nil else {
            throw Abort(.notFound)
        }

        // 验证 per 参数：1…100，缺省 50
        let per: Int
        if let rawPer = req.query[String.self, at: "per"], !rawPer.isEmpty {
            guard let parsed = Int(rawPer), parsed >= 1, parsed <= 100 else {
                throw Abort(.badRequest, reason: "per must be an integer between 1 and 100")
            }
            per = parsed
        } else {
            per = 50
        }

        struct PositionRow: Decodable {
            let position: Int
        }
        guard let sql = req.db as? any SQLDatabase else {
            throw Abort(.internalServerError, reason: "Configured database does not support SQL position queries")
        }

        // Use the same database ordering expression as /api/songs. This avoids
        // backend-specific timestamp bind formatting differences when comparing
        // `created_at` directly, especially in SQLite tests.
        let query: SQLQueryString = """
            SELECT position FROM (
                SELECT id, ROW_NUMBER() OVER (
                    ORDER BY created_at DESC, id DESC
                ) - 1 AS position
                FROM songs
            ) ranked
            WHERE id = \(bind: songId)
            """
        guard let row = try await sql.raw(query).first(decoding: PositionRow.self) else {
            throw Abort(.notFound)
        }
        let position = row.position

        let page = (position / per) + 1

        return SongLocationResponse(
            songId: songId,
            index: position,
            page: page,
            per: per
        )
    }

    // MARK: - Helpers

    /// 服务端解码，并将结果缓存到 CAS 缓存目录
    /// 首次请求转码（AudioEngine → C 解码器 / ffmpeg），后续直接读缓存
    private func cachedDecode(
        originalPath: String,
        sha256: String,
        format: AudioFormat,
        subsong: Int,
        cas: CasStorageService,
        coordinator: DecodeCacheCoordinator,
        logger: Logger
    ) async throws -> String {
        let outcome = try await DecodedAudioCacheService(cas: cas, coordinator: coordinator)
            .prepare(
                originalPath: originalPath,
                sha256: sha256,
                format: format,
                subsong: subsong,
                onFailure: { queueMilliseconds, decodeMilliseconds, error in
                    logger.error("server_decode_failed", metadata: [
                        "cache_hit": "false",
                        "format": "\(format.rawValue)",
                        "queue_ms": "\(queueMilliseconds)",
                        "decode_ms": "\(decodeMilliseconds)",
                        "reason": "\(Self.safeFailureReason(error))",
                    ])
                }
            )
        logger.info("server_decode", metadata: [
            "cache_hit": "\(outcome.cacheHit)",
            "format": "\(format.rawValue)",
            "output_bytes": "\(outcome.outputBytes)",
            "queue_ms": "\(outcome.queueMilliseconds)",
            "decode_ms": "\(outcome.decodeMilliseconds)",
        ])
        return outcome.path
    }

    private static func safeFailureReason(_ error: any Error) -> String {
        switch error {
        case AudioError.decoderNotImplemented: return "decoder_not_implemented"
        case AudioError.decodeFailed: return "decode_failed"
        case AudioError.unsupportedFormat: return "unsupported_format"
        case AudioError.fileNotFound: return "file_not_found"
        case AudioError.invalidPCMData: return "invalid_pcm_data"
        default: return String(reflecting: type(of: error))
        }
    }
}
