import App
import Foundation
import Vapor

private struct CLIOptions {
    var ids: [UUID] = []
    var format: String?
    var recent: Int?
    var concurrency = 1
    var dryRun = false
    var minHits: Int?
    var hitsWindowDays: Double = 14
    var printHits = false
}

private enum CLIError: LocalizedError {
    case missingValue(String)
    case invalidValue(String)
    case unknownArgument(String)
    case missingSelector

    var errorDescription: String? {
        switch self {
        case .missingValue(let name): "Missing value for \(name)"
        case .invalidValue(let name): "Invalid value for \(name)"
        case .unknownArgument(let name): "Unknown argument: \(name)"
        case .missingSelector: "Select songs with --id, --ids, --format, or --recent; implicit full-library warmup is disabled"
        }
    }
}

private func parseOptions() throws -> CLIOptions {
    var options = CLIOptions()
    var index = 1
    let arguments = CommandLine.arguments

    func value(_ name: String) throws -> String {
        index += 1
        guard index < arguments.count else { throw CLIError.missingValue(name) }
        return arguments[index]
    }

    func positiveInt(_ name: String) throws -> Int {
        let raw = try value(name)
        guard let parsed = Int(raw), parsed > 0 else { throw CLIError.invalidValue(name) }
        return parsed
    }

    while index < arguments.count {
        switch arguments[index] {
        case "--id":
            let raw = try value("--id")
            guard let id = UUID(uuidString: raw) else { throw CLIError.invalidValue("--id") }
            options.ids.append(id)
        case "--ids":
            let raw = try value("--ids")
            let ids = raw.split(separator: ",").compactMap { UUID(uuidString: String($0)) }
            guard !ids.isEmpty, ids.count == raw.split(separator: ",").count else {
                throw CLIError.invalidValue("--ids")
            }
            options.ids.append(contentsOf: ids)
        case "--format": options.format = try value("--format").lowercased()
        case "--min-hits": options.minHits = try positiveInt("--min-hits")
        case "--hits-window-days":
            let raw = try value("--hits-window-days")
            guard let parsed = Double(raw), parsed > 0 else { throw CLIError.invalidValue("--hits-window-days") }
            options.hitsWindowDays = parsed
        case "--print-hits": options.printHits = true
        case "--recent": options.recent = try positiveInt("--recent")
        case "--concurrency": options.concurrency = try positiveInt("--concurrency")
        case "--dry-run": options.dryRun = true
        case "--help", "-h":
            print("""
            Usage: swift run OrzDecodeCacheWarmup [selector] [options]
              --id UUID          select one song (repeatable)
              --ids UUID,...     select an explicit comma-separated ID list
              --format FORMAT    select one file format
              --recent N         select the N most recently imported songs
              --concurrency N    maximum concurrent decodes (default 1)
              --dry-run          report selection without creating cache files
              --min-hits N       only warm formats with >= N recent serverDecode hits
              --hits-window-days D  hit window for --min-hits (default 14)
              --print-hits       print recorded serverDecode hit counts and exit
            At least one selector is required. Combined selectors are intersected.
            """)
            Foundation.exit(0)
        default: throw CLIError.unknownArgument(arguments[index])
        }
        index += 1
    }
    if options.printHits { return options }
    guard !options.ids.isEmpty || options.format != nil || options.recent != nil || options.minHits != nil else {
        throw CLIError.missingSelector
    }
    return options
}

@main
struct DecodeCacheWarmupCommand {
    static func main() async throws {
        let cli = try parseOptions()
        if cli.printHits {
            let path = Environment.get("DECODE_HIT_STATS_PATH") ?? "data/decode-hits.json"
            let hits = ServerDecodeHitTracker.loadSnapshot(path: path)
            print("decode-hits path=\(path) formats=\(hits.count)")
            for (format, hit) in hits.sorted(by: { $0.value.count > $1.value.count }) {
                let stamp = hit.lastHitEpoch > 0
                    ? ISO8601DateFormatter().string(from: hit.lastHit)
                    : "never"
                print("decode-hits format=\(format) count=\(hit.count) last=\(stamp)")
            }
            return
        }

        let app = try await Application.make(.production)
        do {
            try configure(app)
            let summary = try await DecodeCacheWarmupService(
                database: app.db,
                cas: app.casStorage
            ).run(options: .init(
                ids: cli.ids,
                format: cli.format,
                recent: cli.recent,
                concurrency: cli.concurrency,
                dryRun: cli.dryRun,
                minHits: cli.minHits,
                hitsWindowDays: cli.hitsWindowDays
            ))

            print(
                "decode-cache-warmup selected=\(summary.selected) eligible=\(summary.eligible) " +
                "warmed=\(summary.warmed) cache_hits=\(summary.cacheHits) " +
                "skipped=\(summary.skippedNonServerDecode) " +
                "skipped_no_recent_hits=\(summary.skippedNoRecentHits) " +
                "min_hits=\(cli.minHits.map { String($0) } ?? "off") " +
                "missing_files=\(summary.missingFiles) " +
                "failures=\(summary.failures.count) dry_run=\(cli.dryRun)"
            )
            for failure in summary.failures {
                print(
                    "decode-cache-warmup failure id=\(failure.id?.uuidString ?? "unknown") " +
                    "format=\(failure.format) reason=\(failure.reason)"
                )
            }
            try await app.asyncShutdown()
            if !summary.failures.isEmpty { Foundation.exit(2) }
        } catch {
            try? await app.asyncShutdown()
            throw error
        }
    }
}
