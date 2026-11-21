import Fluent
import Foundation
import OrzAudioKit
import Vapor

public struct DecodeCacheWarmupOptions: Sendable {
    public var ids: [UUID]
    public var format: String?
    public var recent: Int?
    public var concurrency: Int
    public var dryRun: Bool
    /// 只预热「最近确实被 serverDecode 请求过」的格式（命中统计见 ServerDecodeHitTracker）。
    public var minHits: Int?
    /// 命中时间窗口（天）。默认 14 天。
    public var hitsWindowDays: Double

    public init(
        ids: [UUID] = [],
        format: String? = nil,
        recent: Int? = nil,
        concurrency: Int = 1,
        dryRun: Bool = false,
        minHits: Int? = nil,
        hitsWindowDays: Double = 14
    ) {
        self.ids = ids
        self.format = format
        self.recent = recent
        self.concurrency = concurrency
        self.dryRun = dryRun
        self.minHits = minHits
        self.hitsWindowDays = hitsWindowDays
    }
}

public struct DecodeCacheWarmupFailure: Sendable {
    public let id: UUID?
    public let format: String
    public let reason: String
}

public struct DecodeCacheWarmupSummary: Sendable {
    public var selected = 0
    public var eligible = 0
    public var warmed = 0
    public var cacheHits = 0
    public var skippedNonServerDecode = 0
    /// 因为没有近期命中统计而被跳过的曲目数（只在 minHits 生效时计数）。
    public var skippedNoRecentHits = 0
    public var missingFiles = 0
    public var failures: [DecodeCacheWarmupFailure] = []
}

public struct DecodeCacheWarmupService: Sendable {
    private let database: any Database
    private let cas: CasStorageService

    public init(database: any Database, cas: CasStorageService) {
        self.database = database
        self.cas = cas
    }

    public func run(options: DecodeCacheWarmupOptions) async throws -> DecodeCacheWarmupSummary {
        precondition(options.concurrency > 0)
        precondition(options.recent.map { $0 > 0 } ?? true)
        precondition(options.hitsWindowDays > 0)

        // 命中收敛：只保留最近真的被请求过的格式；minHits == nil 时不过滤。
        var allowedFormats: Set<String>? = nil
        if let minHits = options.minHits {
            let tracker = ServerDecodeHitTracker(
                path: URL(fileURLWithPath: Environment.get("DECODE_HIT_STATS_PATH") ?? "data/decode-hits.json")
            )
            allowedFormats = await tracker.activeFormats(
                minHits: minHits,
                windowDays: options.hitsWindowDays
            )
        }

        // Swift 6:并发闭包只能捕获不可变值,拷成 let 再传进去。
        let allowedFormatsFilter = allowedFormats

        var query = Song.query(on: database)
        if !options.ids.isEmpty {
            query = query.filter(\.$id ~~ options.ids)
        }
        if let format = options.format?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
           !format.isEmpty {
            query = query.filter(\.$fileFormat == format)
        }
        if let recent = options.recent {
            query = query.sort(\.$createdAt, .descending).limit(recent)
        } else {
            query = query.sort(\.$id, .ascending)
        }

        let songs = try await query.all()
        var summary = DecodeCacheWarmupSummary()
        summary.selected = songs.count
        let coordinator = DecodeCacheCoordinator(concurrencyLimit: options.concurrency)
        let cache = DecodedAudioCacheService(cas: cas, coordinator: coordinator)

        for start in stride(from: 0, to: songs.count, by: options.concurrency) {
            let batch = Array(songs[start..<min(start + options.concurrency, songs.count)])
            let results = await withTaskGroup(of: WarmupResult.self) { group in
                for song in batch {
                    group.addTask {
                        await Self.prepare(
                            song: song,
                            cache: cache,
                            cas: cas,
                            dryRun: options.dryRun,
                            allowedFormats: allowedFormatsFilter
                        )
                    }
                }
                var values: [WarmupResult] = []
                for await result in group { values.append(result) }
                return values
            }

            for result in results {
                summary.eligible += result.eligible ? 1 : 0
                switch result.status {
                case .dryRun: break
                case .warmed: summary.warmed += 1
                case .cacheHit: summary.cacheHits += 1
                case .skipped: summary.skippedNonServerDecode += 1
                case .skippedByHits: summary.skippedNoRecentHits += 1
                case .missing:
                    summary.missingFiles += 1
                    summary.failures.append(result.failure!)
                case .failed:
                    summary.failures.append(result.failure!)
                }
            }
        }
        return summary
    }

    private enum Status: Sendable {
        case dryRun, warmed, cacheHit, skipped, skippedByHits, missing, failed
    }

    private struct WarmupResult: Sendable {
        let eligible: Bool
        let status: Status
        let failure: DecodeCacheWarmupFailure?
    }

    private static func prepare(
        song: Song,
        cache: DecodedAudioCacheService,
        cas: CasStorageService,
        dryRun: Bool,
        allowedFormats: Set<String>?
    ) async -> WarmupResult {
        guard let format = AudioFormat(rawValue: song.fileFormat.lowercased()) else {
            return .init(eligible: false, status: .skipped, failure: nil)
        }
        // 命中收敛:该格式最近没人走 serverDecode,就不用再预热了。
        if let allowedFormats, !allowedFormats.contains(format.rawValue) {
            return .init(eligible: false, status: .skippedByHits, failure: nil)
        }
        let path = cas.resolve(sha256: song.sha256, format: song.fileFormat)
        guard FileManager.default.fileExists(atPath: path) else {
            return .init(
                eligible: false,
                status: .missing,
                failure: .init(id: song.id, format: song.fileFormat, reason: "CAS file not found")
            )
        }
        guard case .serverDecode = AudioEngine().resolveStreamStrategy(filePath: path, format: format) else {
            return .init(eligible: false, status: .skipped, failure: nil)
        }
        if dryRun {
            return .init(eligible: true, status: .dryRun, failure: nil)
        }

        do {
            let outcome = try await cache.prepare(
                originalPath: path,
                sha256: song.sha256,
                format: format
            )
            return .init(
                eligible: true,
                status: outcome.cacheHit ? .cacheHit : .warmed,
                failure: nil
            )
        } catch {
            return .init(
                eligible: true,
                status: .failed,
                failure: .init(
                    id: song.id,
                    format: song.fileFormat,
                    reason: safeReason(error)
                )
            )
        }
    }

    private static func safeReason(_ error: any Error) -> String {
        switch error {
        case AudioError.decoderNotImplemented: "decoder not implemented"
        case AudioError.decodeFailed: "decode failed"
        case AudioError.unsupportedFormat: "unsupported format"
        case AudioError.fileNotFound: "file not found"
        case AudioError.invalidPCMData: "invalid PCM data"
        default: String(reflecting: type(of: error))
        }
    }
}
