import Foundation
import Testing
@testable import App

/// `ServerDecodeHitTracker` 是 `make warm-decode-cache MIN_HITS=…` 的数据源：
/// 命中统计错了，预热范围就会错（要么白解码，要么该预热的不预热）。
@Suite(.serialized) struct ServerDecodeHitTrackerTests {

    private func tempPath() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("orz-hits-\(UUID().uuidString).json")
    }

    @Test func recordAccumulatesPerFormat() async {
        let tracker = ServerDecodeHitTracker(path: nil)
        await tracker.record(format: "sc68")
        await tracker.record(format: "sc68")
        await tracker.record(format: "WAV")

        let snapshot = await tracker.snapshot()
        #expect(snapshot["sc68"]?.count == 2)
        // 统一小写，避免 "WAV"/"wav" 被算成两个格式。
        #expect(snapshot["wav"]?.count == 1)
        #expect(snapshot["WAV"] == nil)
    }

    @Test func recordRejectsUnusableKeys() async {
        let tracker = ServerDecodeHitTracker(path: nil)
        await tracker.record(format: "")
        await tracker.record(format: "this-format-id-is-way-too-long")
        await tracker.record(format: "中文")
        let snapshot = await tracker.snapshot()
        #expect(snapshot.isEmpty)
    }

    @Test func activeFormatsHonoursThresholdAndWindow() async {
        let path = tempPath()
        defer { try? FileManager.default.removeItem(at: path) }
        let tracker = ServerDecodeHitTracker(path: path)

        await tracker.record(format: "sc68")
        await tracker.record(format: "wav")

        #expect(await tracker.activeFormats(minHits: 1, windowDays: 14) == ["sc68", "wav"])
        #expect(await tracker.activeFormats(minHits: 2, windowDays: 14).isEmpty)
        // 窗口为 0 天时，“最近”已经过期。
        #expect(await tracker.activeFormats(minHits: 1, windowDays: 0).isEmpty)
    }

    @Test func snapshotSurvivesReopen() async {
        let path = tempPath()
        defer { try? FileManager.default.removeItem(at: path) }

        let tracker = ServerDecodeHitTracker(path: path)
        await tracker.record(format: "sc68")
        await tracker.flush()

        // 预热 CLI 是独立进程：它只读文件，不共享内存。
        let reloaded = ServerDecodeHitTracker.loadSnapshot(path: path)
        #expect(reloaded["sc68"]?.count == 1)
        #expect((reloaded["sc68"]?.lastHitEpoch ?? 0) > 0)
    }

    @Test func staleHitsFromFileAreFilteredByWindow() async throws {
        let path = tempPath()
        defer { try? FileManager.default.removeItem(at: path) }

        let old = Date().timeIntervalSince1970 - 40 * 86_400
        let seeded = ["sc68": ServerDecodeHit(count: 5, lastHitEpoch: old)]
        try JSONEncoder().encode(seeded).write(to: path)

        let tracker = ServerDecodeHitTracker(path: path)
        #expect(await tracker.activeFormats(minHits: 1, windowDays: 14).isEmpty)
        #expect(await tracker.activeFormats(minHits: 1, windowDays: 60) == ["sc68"])
    }
}
