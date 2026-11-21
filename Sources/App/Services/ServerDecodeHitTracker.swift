import Foundation
import Vapor

/// 某个格式的 serverDecode 命中情况。
public struct ServerDecodeHit: Sendable, Codable {
    public var count: Int
    /// Unix 时间戳（秒）。用来判断“最近还有人要服务端转码吗”。
    public var lastHitEpoch: Double

    public init(count: Int = 0, lastHitEpoch: Double = 0) {
        self.count = count
        self.lastHitEpoch = lastHitEpoch
    }

    public var lastHit: Date { Date(timeIntervalSince1970: lastHitEpoch) }
}

/// 记录 `serverDecode` 命中次数的计数器。
///
/// 为什么需要它：`make warm-decode-cache` 过去只能按格式**全量**预热，即使某个格式
/// 早就没人点播了（客户端本地解码、或者曲库已下架），每次仍然会重新解码生成 WAV。
/// 有了命中统计，预热就可以只处理「最近真的被请求过」的格式：
///
/// ```bash
/// make warm-decode-cache RECENT=50 MIN_HITS=1 HITS_WINDOW_DAYS=14
/// ```
///
/// 计数是**进程内**累加 + 定期落盘（默认 `data/decode-hits.json`，
/// 可用 `DECODE_HIT_STATS_PATH` 覆盖），所以预热 CLI（独立进程）能读到同一份数据。
/// 落盘失败只打日志，不影响播放。
public actor ServerDecodeHitTracker {

    private var hits: [String: ServerDecodeHit]
    private let path: URL?
    private var dirty = false
    private var lastFlush = Date.distantPast

    /// 落盘间隔：命中很多时不要每条请求都写文件。
    private static let flushInterval: TimeInterval = 30

    public init(path: URL?) {
        self.path = path
        self.hits = path.map { Self.loadSnapshot(path: $0) } ?? [:]
    }

    /// 计一次 `serverDecode` 命中。
    public func record(format: String) {
        let key = format.lowercased()
        guard !key.isEmpty, key.count <= 16, key.allSatisfy({ $0.isASCII }) else { return }
        var entry = hits[key] ?? ServerDecodeHit()
        entry.count += 1
        entry.lastHitEpoch = Date().timeIntervalSince1970
        hits[key] = entry
        dirty = true
        if Date().timeIntervalSince(lastFlush) >= Self.flushInterval {
            flushIfNeeded()
        }
    }

    public func snapshot() -> [String: ServerDecodeHit] {
        hits
    }

    /// 满足「命中次数 ≥ minHits 且最近一次命中在 windowDays 天内」的格式集合。
    public func activeFormats(minHits: Int, windowDays: Double) -> Set<String> {
        let cutoff = Date().timeIntervalSince1970 - windowDays * 86_400
        return Set(
            hits.filter { $0.value.count >= minHits && $0.value.lastHitEpoch >= cutoff }.keys
        )
    }

    public func flushIfNeeded() {
        guard dirty else { return }
        flush()
    }

    public func flush() {
        guard let path else { return }
        dirty = false
        lastFlush = Date()
        do {
            let directory = path.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(hits)
            try data.write(to: path, options: .atomic)
        } catch {
            // 计数只是运维辅助，写不进去不能影响播放。
            lastFlush = Date.distantPast
        }
    }

    // MARK: - 文件读写（CLI 也用）

    public nonisolated static func loadSnapshot(path: URL) -> [String: ServerDecodeHit] {
        guard let data = try? Data(contentsOf: path) else { return [:] }
        return (try? JSONDecoder().decode([String: ServerDecodeHit].self, from: data)) ?? [:]
    }

    /// 供 CLI 读取（不启动 actor）。
    public nonisolated static func loadSnapshot(path: String) -> [String: ServerDecodeHit] {
        loadSnapshot(path: URL(fileURLWithPath: path))
    }

    /// 默认统计文件路径（服务端与 CLI 共用同一口径）。
    public nonisolated static var defaultPath: String {
        Environment.get("DECODE_HIT_STATS_PATH") ?? "data/decode-hits.json"
    }
}

private struct DecodeHitsKey: StorageKey {
    typealias Value = ServerDecodeHitTracker
}

extension Application {
    public var decodeHits: ServerDecodeHitTracker {
        if let stored = storage[DecodeHitsKey.self] {
            return stored
        }
        let tracker = ServerDecodeHitTracker(
            path: URL(fileURLWithPath: ServerDecodeHitTracker.defaultPath)
        )
        storage[DecodeHitsKey.self] = tracker
        return tracker
    }
}
