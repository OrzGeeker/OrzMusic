import Fluent
import FluentPostgresDriver
import Leaf
import Vapor

// configures your application
public func configure(_ app: Application) throws {
    // Compress text, JSON and other known-compressible responses. Vapor's
    // media-type policy excludes audio/video and already-compressed assets.
    app.http.server.configuration.responseCompression = .enabledForCompressibleTypes

    // CORS — 允许前端跨域访问
    let corsConfig = CORSMiddleware.Configuration(
        allowedOrigin: .all,
        allowedMethods: [.GET, .POST, .PUT, .DELETE, .OPTIONS, .PATCH],
        allowedHeaders: [.accept, .authorization, .contentType, .origin, .xRequestedWith]
    )
    app.middleware.use(CORSMiddleware(configuration: corsConfig))
    app.middleware.use(CrossOriginIsolationMiddleware())

    // 统一错误响应格式
    app.middleware.use(ErrorResponseMiddleware())

    // 静态文件 — Public 目录（用于前端 JS/CSS，不再用于音乐文件）
    app.directory.publicDirectory = "\(app.directory.resourcesDirectory)Public/"
    app.middleware.use(CachePolicyMiddleware())
    app.middleware.use(FileMiddleware(publicDirectory: app.directory.publicDirectory))

    // CAS（Content-Addressed Storage）初始化
    let casRoot = Environment.get("CAS_ROOT") ?? "./data/music"
    app.casStorage = CasStorageService(root: casRoot)
    app.initializeDecodeCacheCoordinator()

    // 管理 API 依赖显式配置的 ADMIN_API_TOKEN，缺失时按设计保持 fail-closed。
    // 但“健康检查依旧 ready、只有 adminApi: disabled”极易被忽略，因此启动时
    // 必须打印醒目的告警，覆盖所有启动路径（docker compose、native、自定义脚本）。
    if app.adminAPIToken == nil {
        app.logger.warning("ADMIN_API_TOKEN is not set: admin API disabled (scan/upload/delete return 503 admin_api_disabled). Set ADMIN_API_TOKEN in the deployment environment to enable administrative endpoints.")
    } else {
        app.logger.notice("ADMIN_API_TOKEN is set: admin API enabled.")
    }

    app.databases.use(
        .postgres(
            configuration: .init(
                hostname: Environment.get("DATABASE_HOST") ?? "localhost",
                port: Environment.get("DATABASE_PORT").flatMap(Int.init(_:)) ?? SQLPostgresConfiguration.ianaPortNumber,
                username: Environment.get("DATABASE_USERNAME") ?? "vapor_username",
                password: Environment.get("DATABASE_PASSWORD") ?? "vapor_password",
                database: Environment.get("DATABASE_NAME") ?? "vapor_database",
                tls: .disable)
        ),
        as: .psql
    )

    // Migrations
    app.migrations.add(CreateArtist())
    app.migrations.add(CreateAlbum())
    app.migrations.add(CreateSong())
    app.migrations.add(CreateSongListIndexes())
    app.migrations.add(CreateSearchTrigramIndexes())
    app.migrations.add(CreatePlaylist())
    app.migrations.add(CreatePlaylistSongPivot())
    // 注意：MigrateSongToCas 仅用于从旧 schema 升级，新 DB 由 CreateSong 直接创建正确 schema

    app.views.use(.leaf)

    // register routes
    try routes(app)
}
