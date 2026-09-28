// swift-tools-version:6.0
import PackageDescription
import Foundation

let packageRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let serverSdkRoot = ProcessInfo.processInfo.environment["ORZ_AUDIO_CORE_SERVER_DIR"]
    ?? "\(packageRoot)/.audio-core-sdk/server"
let audioCoreLinkerSettings: [LinkerSetting] = [
    .unsafeFlags(["-L\(serverSdkRoot)/native/lib", "-Xlinker", "-rpath", "-Xlinker", "\(serverSdkRoot)/native/lib"]),
    .linkedLibrary("z", .when(platforms: [.linux]))
]

let package = Package(
    name: "MusicService",
    platforms: [
       .macOS(.v13)
    ],
    products: [
        // 名必须与 SDK 的 C 库 libOrzAudioCore.dylib 区分开：SwiftPM 的 library product 会生成
        // 同名产物 libOrzAudioCore.a，而它在链接搜索路径中排在 .audio-core-sdk/server/native/lib
        // 之前，会遮住真正的 C 库，导致全部 _orz_* 符号未定义。默认 swiftbuild 引擎（Swift 6.4 /
        // Xcode 27）把产物放在 .build/out/Products/Debug/ 才会撞上，旧的 native 引擎布局不同故未暴露。
        .library(name: "OrzAudioKit", targets: ["OrzAudioKit"]),
        .library(name: "OrzAudioCoreC", targets: ["OrzAudioCoreSDK"]),
        .executable(name: "OrzAudioCoreSmoke", targets: ["OrzAudioCoreSmoke"]),
        .executable(name: "OrzFingerprintAudit", targets: ["OrzFingerprintAudit"]),
        .executable(name: "OrzDurationBackfill", targets: ["DurationBackfill"]),
        .executable(name: "OrzDecodeCacheWarmup", targets: ["DecodeCacheWarmup"]),
        .executable(name: "OrzDecodeCacheMaintenance", targets: ["DecodeCacheMaintenance"]),
        .executable(name: "OrzMusicService", targets: ["Run"]),
    ],
    dependencies: [
        .package(url: "https://github.com/vapor/vapor.git", from: "4.122.0"),
        .package(url: "https://github.com/vapor/fluent.git", from: "4.13.0"),
        .package(url: "https://github.com/vapor/fluent-kit.git", from: "1.57.0"),
        .package(url: "https://github.com/vapor/fluent-postgres-driver.git", from: "2.12.0"),
        .package(url: "https://github.com/vapor/fluent-sqlite-driver.git", from: "4.9.0"),
        .package(url: "https://github.com/vapor/leaf.git", from: "4.5.2"),
    ],
    targets: [
        // ── OrzAudioCore SDK (system library from release artifact) ──
        // Installed and checksum-verified by script/update-audio-core-server.sh.
        .systemLibrary(
            name: "OrzAudioCoreSDK",
            path: "Sources/OrzAudioCoreSDK"
        ),

        // ── Audio Engine (Swift) ──
        // Calls OrzAudioCoreSDK via the stable ABI v1 Swift binding.
        .target(
            name: "OrzAudioKit",
            dependencies: [
                .target(name: "OrzAudioCoreSDK"),
            ],
            linkerSettings: audioCoreLinkerSettings
        ),

        .executableTarget(
            name: "OrzAudioCoreSmoke",
            dependencies: [.target(name: "OrzAudioKit")]
        ),

        .executableTarget(
            name: "OrzFingerprintAudit",
            dependencies: [.target(name: "OrzAudioKit")]
        ),

        // ── App ──
        .target(
            name: "App",
            dependencies: [
                .product(name: "Fluent", package: "fluent"),
                .product(name: "FluentSQL", package: "fluent-kit"),
                .product(name: "FluentPostgresDriver", package: "fluent-postgres-driver"),
                .product(name: "Leaf", package: "leaf"),
                .product(name: "Vapor", package: "vapor"),
                .target(name: "OrzAudioKit"),
            ],
            swiftSettings: [
                .unsafeFlags(["-cross-module-optimization"], .when(configuration: .release))
            ]
        ),
        .executableTarget(name: "DurationBackfill", dependencies: [.target(name: "App")]),
        .executableTarget(name: "DecodeCacheWarmup", dependencies: [.target(name: "App")]),
        .executableTarget(name: "DecodeCacheMaintenance", dependencies: [.target(name: "App")]),
        .executableTarget(name: "Run", dependencies: [.target(name: "App")]),
        .testTarget(name: "AppTests", dependencies: [
            .target(name: "App"),
            .target(name: "OrzAudioKit"),
            .target(name: "OrzAudioCoreSDK"),
            .product(name: "VaporTesting", package: "vapor"),
            .product(name: "FluentSQLiteDriver", package: "fluent-sqlite-driver"),
            .product(name: "FluentSQL", package: "fluent-kit"),
        ], exclude: [
            "release-smoke-test.sh",
            "performance-smoke-test.sh",
            "db-backup-test.sh",
            "release-scripts-test.sh",
            "native-scripts-test.sh",
        ], linkerSettings: audioCoreLinkerSettings)
    ]
)
