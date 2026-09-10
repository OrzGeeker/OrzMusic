# Changelog

## [Unreleased]

### 修复
- 官方 Compose 默认给 `app` / `db` 容器加 `restart: unless-stopped`：宿主或 Docker 重启、
  容器崩溃后自动恢复，不再出现“静默宕机 12 小时”；一次性 `cas-init` 仍为 `restart: "no"`（#2）。
- `db` 不再向宿主全网卡发布 5432：`docker-compose.yml` 收敛为 `127.0.0.1:5432:5432`
  （仅供本机调试），叠加 `docker-compose.production.yml` 后不向宿主发布端口（#4）。
- `ADMIN_API_TOKEN` 缺失时服务启动打印明确 WARN（覆盖所有启动路径），`/api/health`
  的 `adminApi` 字段保持不变；`release-smoke` 在调用方提供了令牌、服务端却报告
  `adminApi: disabled` 时判定失败，避免管理 API 被静默关闭（#3）。

### 文档
- 部署文档与 README 明确 `ADMIN_API_TOKEN` 没有隐式回退来源、缺失时的告警位置与
  健康检查字段，并补充容器自愈与数据库端口暴露口径。

## [0.0.7] — 2026-08-05

### 优化
- 前端按服务端管理 API 状态自适应：`/api/health` 新增 `adminApi` 字段
  （`enabled`/`disabled`）。服务端未配置 `ADMIN_API_TOKEN` 时，播放器隐藏"导入"
  入口，`I` 快捷键不再触发、也不在快捷键帮助中展示。

### 说明
- 中间架构 tag（`<version>-amd64/-arm64`）会保留在 GHCR：GitHub 不提供容器镜像
  tag 级删除，且 per-arch 版本是合并后多架构 index 的依赖。发布工作流不再尝试
  清理这些 tag（它们无害，`vX.Y.Z` 才是规范引用）；无 tag 版本由定时清理工作流处理。

## [0.0.6] — 2026-08-05

### 新增
- 发布镜像同时支持 `linux/amd64` 与 `linux/arm64`（原生 runner 并行构建后合并
  多架构 manifest），Apple Silicon 生产机无需额外配置即可直接拉取对应架构。
- 新增发布后自动化验证工作流 `verify-release.yml`：发布后对部署包执行真实
  PostgreSQL 升级、冒烟检查与管理 API 门控验证，确保 Release 制品可正常工作。
- 新增 GHCR 保留策略工作流 `ghcr-cleanup.yml`：每周清理 untagged 镜像版本
  （保留 7 天），避免发布产生的中间架构镜像持续占用存储。

### 优化
- 发布工作流改为在 `ubuntu-24.04` 与 `ubuntu-24.04-arm` 原生 runner 上并行构建
  amd64/arm64，不再使用 QEMU 仿真，显著缩短 arm64 构建时间。
- 部署脚本测试 `make script-test` 接入发布工作流 CI。
- `docker-compose.yml` 钉死 `name: orzmusic`：Compose 不再随部署包目录名变化
  创建全新项目与 volume，按版本目录升级时数据可靠复用；仍可用 `COMPOSE_PROJECT_NAME`
  覆盖实现多实例隔离。

### 修复
- `db-backup.sh` 与 `release-upgrade.sh` 的 Compose 配置参数语义统一为
  `COMPOSE_BASE`（空格分隔的 `-f` 参数），修复设置 `COMPOSE_FILE` 时数据库备份
  必然失败的问题。
- 数据库备份文件名改用部署包 `VERSION` 文件，digest 镜像引用下不再产生晦涩的
  64 位十六进制文件名。

### 文档
- 部署文档补充：Compose 项目名由 `name: orzmusic` 兜底（早期版本需手动保持一致）、
  arm64 镜像支持、发布制品生命周期与 untagged 清理说明。

## [0.0.5] — 2026-08-04

### 新增
- 前端"导入本地目录"支持实时上传进度（按字节加权），导入面板样式优化，新增 `I` 快捷键。
- 上传面板的"选择目录 / 选择文件"直接用按钮标题区分，移除多余标签；隐藏的原生文件输入保留可访问性。
- 新增生产扫描脚本和部署包内 `make scan` 入口，支持
  `MUSIC_DIR=/absolute/path/to/music make scan` 一键临时启动 scanner 并触发扫描。
- Native 部署脚本加固：端口占用检测、子进程启动失败快速退出、托管/未托管服务状态区分；新增 `make script-test` 运行部署脚本测试。
- 服务端管理 API 错误的中文提示：`503 admin_api_disabled` 提示需配置 `ADMIN_API_TOKEN`，`401 unauthorized` 提示管理令牌不正确。

### 优化
- 测试套件从 XCTest/XCTVapor 迁移到 swift-testing/VaporTesting：无 Xcode 环境
  （CLT SDK 无 `XCTest.framework`）下 `swift test` 可直接运行。
- 全面采用 async/await：中间件改用 `AsyncMiddleware`，测试与应用生命周期使用
  `Application.make` + `asyncShutdown`，移除全部同步 `EventLoopFuture` / `.wait()` 用法。
- 依赖升级到最新版本：Vapor 4.122.0、Fluent 4.13.0、FluentKit 1.57.0、
  FluentPostgresDriver 2.12.0、FluentSQLiteDriver 4.9.0、Leaf 4.5.2。

### 修复
- 修复未填写管理令牌时选择文件/文件夹后上传任务永久停留在"等待中"：现在标记为
  可重试失败并提示先填写令牌。
- 修正生产 Compose 覆盖配置中 scanner 服务丢失音乐源目录挂载的问题。
- 修正 `AppTests` 目标中 `native-scripts-test.sh` 未被声明为资源导致的 SPM 构建告警。

### 文档
- 补齐 `ADMIN_API_TOKEN` 在全部部署路径（Native、Docker 首次/日常、生产首次/日常
  升级、部署包）的配置说明；`release-upgrade` 在令牌缺失时 fail-closed 报错。
- 更新生产部署文档，明确扫描时宿主机路径与容器内 `/sources/keygen` 的对应关系。

## [0.0.4] — 2026-07-27

### 修复
- 修正 `GET /api/songs/:id/location` 的位置计算：改为使用与列表接口一致的
  `ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC)` 计算位置，避免
  SQLite 测试环境中直接比较绑定时间戳导致 index 偏移。

### 发布说明
- `v0.0.3` 已触发发布工作流，但在 Swift 测试阶段失败，未形成可用 Release 制品。
- `v0.0.4` 作为后续修复版本继续执行发布流程。

## [0.0.3] — 2026-07-27

### 新增
- GitHub Release 除 Docker 镜像外，新增轻量部署包
  `orzmusic-deploy-<version>.tar.gz`，生产机可不拉取完整仓库即可部署。
- 新增生产部署说明文档，明确部署包内容、首次部署、日常升级和回滚流程。
- 新增性能优化计划和任务拆分文档，便于后续按小粒度任务推进。

### 优化
- 优化初始加载和音频播放体验。
- 更新 OrzMusic 项目图标。
- 本机 ARM 主机默认使用 `linux/arm64` Docker 构建平台，避免 Apple Silicon 上
  amd64 模拟构建触发 Swift 依赖编译崩溃。
- Makefile 优先使用 PATH 中的 Docker CLI，提高不同本机 Docker 安装路径的兼容性。

### 修复
- 修正歌曲位置排序测试依赖随机 UUID 的问题，使用确定性 UUID 覆盖
  `createdAt DESC, id DESC` 次级排序，避免 Linux CI 偶发失败。
- 稳定播放状态图标显示，避免播放/暂停状态切换时图标表现不一致。

## [0.0.2] — 2026-07-23

### 修复
- 修正 Release workflow 的 GHCR 镜像名为全小写，避免 Docker buildx 拒绝 `ghcr.io/OrzGeeker/orzmusic`。

### 发布说明
- `v0.0.1` 已触发发布工作流但镜像构建阶段失败，未形成可用 Release 制品。
- `v0.0.2` 作为首个可用发布候选继续执行发布流程。

## [0.0.1] — 2026-07-23

### 新增
- 首次正式发布基础设施
- 版本单一来源：`VERSION` 文件 + `AppVersion` 结构（R01）
- 健康与版本接口：`GET /api/health`（R02）
- 镜像构建身份注入：`APP_VERSION` / `GIT_COMMIT` / `BUILD_TIME`（R03）
- Tag 发布工作流：推送 `vX.Y.Z` 标签自动构建、推送 GHCR、生成 Release（R04）
- 生产部署配置：`docker-compose.production.yml`（R05）
- 数据库备份命令：`make db-backup`（R06）
- 升级与回滚命令：`release-upgrade` / `release-rollback`（R07）
- 发布冒烟检查脚本：`release-smoke`（R08）
- 运维手册与演练计划（R09）

### 技术变更
- 迁移从 `swift:6.1-noble` 构建
- 运行时镜像基于 `swift:6.1-noble-slim`
- 正式镜像标签格式：`ghcr.io/<owner>/orzmusic:X.Y.Z`
