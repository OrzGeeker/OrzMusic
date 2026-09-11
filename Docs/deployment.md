# OrzMusic 生产部署说明

生产环境推荐使用 GitHub Release 中的轻量部署包，不需要在生产机拉取完整 Git 仓库，也不需要在生产机编译 Swift 服务。

## 发布制品

每个正式版本的 GitHub Release 应包含：

| 制品 | 用途 |
|:-----|:-----|
| Docker 镜像 | 服务运行制品，例如 `ghcr.io/orzgeeker/orzmusic:0.0.2`。 |
| 镜像 Digest | 推荐生产部署使用的不可变镜像引用，例如 `ghcr.io/orzgeeker/orzmusic@sha256:...`。 |
| 部署包 | `orzmusic-deploy-<version>.tar.gz`，包含生产 Compose、发布脚本和部署文档。 |

部署包只包含生产机需要的运维文件，不包含源码、Swift 构建产物、测试、SDK 下载缓存或样例音乐。

镜像按架构分别构建后合并为多架构 manifest。中间架构 tag（如 `0.0.7-amd64`、`0.0.7-arm64`）会保留在 GHCR——GitHub 不提供容器镜像 tag 级删除（Packages REST `DELETE /tags/{tag}` 返回 404，registry `DELETE` 返回 405），且这些 per-arch 版本是合并后 index 的依赖、删除会破坏多架构镜像；它们无害，`vX.Y.Z` 才是规范引用。无 tag 的版本由仓库定时工作流（`ghcr-cleanup.yml`，每周）在保留 7 天后清理。

## Native 一键部署（源码仓库）

Native 模式适合开发机或已有 PostgreSQL/systemd 管理体系的轻量部署。以下命令适用于源码仓库；GitHub Release 的生产部署包只包含 Docker 运行所需文件，不包含 Swift 源码和 Native 编译脚本。

```bash
cp .env.native.example .env.native
# 编辑 .env.native，至少设置数据库密码、SCAN_ROOT 和 ADMIN_API_TOKEN
make native-install   # 安装 SDK、编译 release、执行迁移
make native-up        # 后台启动并等待 /api/health ready
make native-status    # 查看 PID 和健康状态
make native-down      # 安全停止 Native 进程
```

`native-install` 不会安装或初始化 PostgreSQL，也不会替用户创建系统服务；数据库、音频工具链和目录权限必须先准备好。生产环境可将 `make native-up` 包装进 systemd、launchd 或 supervisor。

Native 运行时使用 `.env.native`，也可以通过 `NATIVE_ENV_FILE=/path/to/env make native-up` 指定其他环境文件。脚本默认将 PID 和日志写入 `.orzmusic/`，该目录不应提交到 Git。

生成管理员令牌可以直接运行：

```bash
make generate-admin-token
```

命令只输出一个由 OpenSSL 生成的 32 字节随机令牌，不会自动写入配置文件。将输出值填入 `.env.native`，或作为 `ADMIN_API_TOKEN` 环境变量传给 Docker 命令；令牌不应提交到 Git、写入 URL 或日志。

## Docker 一键首次部署

源码仓库中的 Docker 首次部署可以由一个命令完成：

```bash
MUSIC_DIR=/absolute/path/to/music \
ADMIN_API_TOKEN=<a-long-random-secret> \
make docker-install
```

该命令依次校验 Docker/Compose 和音乐目录、检查 Compose 配置、启动 PostgreSQL 与 CAS 初始化、等待数据库、执行迁移、启动 app，并运行 release smoke check。它适合本地或自建主机的首次 Compose 部署；生产版本升级仍使用后文的 `release-preflight`、`release-upgrade` 和 `release-rollback` 流程。

## 部署包内容

```text
orzmusic-deploy-<version>/
├── DEPLOYMENT.txt
├── VERSION
├── CHANGELOG.md
├── README.md
├── Makefile                 # 生产专用命令入口
├── docker-compose.yml
├── docker-compose.production.yml
├── Docs/
│   ├── deployment.md
│   └── migration.md
└── script/
    ├── db-backup.sh
    ├── release-preflight.sh
    ├── release-upgrade.sh
    ├── release-rollback.sh
    ├── release-smoke.sh
    ├── release-scan.sh
    └── generate-admin-token.sh
```

## 首次部署

1. 在生产机安装 Docker 和 Docker Compose。
2. 从 GitHub Release 下载对应版本的部署包。
3. 解压部署包：

```bash
tar -xzf orzmusic-deploy-0.0.2.tar.gz
cd orzmusic-deploy-0.0.2
```

4. 指定镜像并设置管理令牌。生产环境推荐使用 Release 页面里的 digest；令牌可运行 `make generate-admin-token` 生成：

```bash
export IMAGE_REF=ghcr.io/orzgeeker/orzmusic@sha256:<digest>
export ADMIN_API_TOKEN=<a-long-random-secret>
```

`ADMIN_API_TOKEN` 在容器启动时读取，且没有隐式回退来源（不会读取 shell 历史或任何本机遗留文件）。未配置时管理写接口按设计 fail-closed（返回 `503 admin_api_disabled`），同时：

- 服务启动日志打印明确的 `WARN`（`ADMIN_API_TOKEN is not set: admin API disabled ...`），所有启动路径（`docker compose`、native、自定义脚本）都会出现；
- `/api/health` 返回 `adminApi: "disabled"`，已配置时为 `"enabled"`；
- `release-upgrade` 在缺失时直接中止，`release-smoke` 在调用方提供了令牌而服务端报告 `disabled` 时判定失败。

它虽然不阻塞服务启动，但扫描、上传、删除等管理功能会全部不可用，生产环境应视为必填。令牌不要提交到 Git、写入 URL 或日志。

> **项目名已由 Compose 文件兜底**：`docker-compose.yml` 钉死了 `name: orzmusic`（自 v0.0.6 起的部署包生效），无论部署包解压到哪个目录，所有版本都共享同一组 `db_data`/`cas_data` volume，升级能真正复用数据。早期版本（v0.0.5 及以前）没有这个兜底，必须手动 `export COMPOSE_PROJECT_NAME=orzmusic` 并在首次部署与每次升级中保持一致，否则按目录切换版本会新建空数据库。需要同时运行多套独立实例时，可用 `COMPOSE_PROJECT_NAME` 或 `--project-name` 覆盖（两者优先级都高于 `name` 字段）。

5. 启动数据库并执行升级流程：

```bash
docker compose -f docker-compose.yml -f docker-compose.production.yml up -d db
make release-preflight
make release-upgrade
EXPECTED_VERSION=0.0.2 make release-smoke
```

## 日常升级

日常升级不需要拉仓库，只需要下载新版本部署包并指定新镜像。管理令牌同样在容器启动时读取，所以新 shell 里需要连同令牌一起导出；更换令牌也要重新执行 `release-upgrade`：

```bash
tar -xzf orzmusic-deploy-0.0.3.tar.gz
cd orzmusic-deploy-0.0.3

export IMAGE_REF=ghcr.io/orzgeeker/orzmusic@sha256:<new-digest>
export ADMIN_API_TOKEN=<与首次部署相同的令牌>
make release-preflight
make release-upgrade
EXPECTED_VERSION=0.0.3 make release-smoke
```

项目名无需重复设置：`docker-compose.yml` 已钉死 `name: orzmusic`（v0.0.6 起），所有版本自动共享同一组 volume。使用早期部署包时仍需手动设置并保持 `COMPOSE_PROJECT_NAME` 一致。

`release-upgrade` 会校验 `IMAGE_REF` 与 `ADMIN_API_TOKEN` 均已设置，缺失时立即中止，避免静默部署出管理 API 被关闭的服务。

`release-upgrade` 会按固定顺序执行：

1. 前置检查
2. 拉取镜像
3. 数据库备份
4. 停止 `app`
5. 执行数据库迁移
6. 启动 `app`

## 回滚

回滚时使用上一版本镜像：

```bash
export IMAGE_REF=ghcr.io/orzgeeker/orzmusic@sha256:<previous-digest>
make release-rollback
EXPECTED_VERSION=0.0.2 make release-smoke
```

注意：回滚脚本不会自动恢复数据库。若失败原因是数据库迁移不兼容，应先根据备份恢复数据库，再启动上一版本镜像。

## 扫描音频文件

扫描由主服务处理，不再启动独立 scanner 容器。部署或重建主服务前，将宿主机音乐目录通过 `MUSIC_DIR` 只读挂载到容器内 `/sources/music`；服务使用固定的 `SCAN_ROOT=/sources/music`，扫描请求不能指定其他服务器路径。

```bash
export IMAGE_REF=ghcr.io/orzgeeker/orzmusic@sha256:<digest>
export MUSIC_DIR=/absolute/path/to/music
export ADMIN_API_TOKEN=<a-long-random-secret>
make release-upgrade
make release-scan
```

示例：

```bash
export IMAGE_REF=ghcr.io/orzgeeker/orzmusic@sha256:32db66e1e7c0d0b0301c0ed31647a3f8d9b9d568439c9377399c2baaf9fb8c9a
export MUSIC_DIR=/mnt/music
export ADMIN_API_TOKEN=<a-long-random-secret>
make release-upgrade
make release-scan
```

参数说明：

| 变量 | 说明 |
|:-----|:-----|
| `MUSIC_DIR` | 宿主机上的真实音频目录，必须是绝对路径。 |
| `IMAGE_REF` | 当前生产镜像引用，建议使用 Release 页面提供的 digest。 |
| `ADMIN_API_TOKEN` | 管理写操作使用的高强度随机 Bearer Token。未配置时扫描、上传和删除接口会返回 `503 admin_api_disabled`。 |

`MUSIC_DIR` 只在主服务容器启动时挂载。若要更换目录，更新环境变量后执行 `make release-upgrade` 重建 `app`，再触发扫描。

扫描完成后可检查格式统计：

```bash
curl -fsS "http://127.0.0.1:8080/api/songs/formats"
```

也可以直接调用主服务的扫描接口：

```bash
curl -fsS -X POST "http://127.0.0.1:8080/api/scan" \
  -H "Authorization: Bearer ${ADMIN_API_TOKEN}"
```

该接口没有请求体。它只会扫描启动时配置的 `SCAN_ROOT`，因此客户端无法选择或探测任意服务器目录。

## 从管理员浏览器导入本地目录

无需把管理员电脑上的目录挂载到服务器。打开播放器，使用右上角的“导入目录”，输入与服务器 `ADMIN_API_TOKEN` 相同的令牌后选择本地目录；浏览器在用户授权后读取文件内容并上传，服务端不能直接读取管理员电脑的路径。

- 目录选择使用浏览器目录选择能力，不能使用时可改为多文件选择；支持格式会以两个并发请求上传。
- 单个文件上限为 32 MiB。超限返回 `413 upload_too_large`，不会写入 CAS 或数据库；某个文件失败不影响同批其他文件，并可在面板中重试失败项。
- 每个请求发送可选 `relativePath`，用于在没有显式 `artist`、`title` 时推断元数据。新文件返回 `201 { status: "created", song }`；SHA-256 已存在时返回 `200 { status: "duplicate", song }`。
- 面板只把令牌保存在当前会话的 `sessionStorage`；不将令牌写入 URL 或长期存储。离开浏览器会话后需重新输入，也可使用“清除令牌”立即移除。

该导入方式不提供分片上传、断点续传、目录监听或关闭页面后的任务恢复。大文件或需要长期同步的曲库应先在服务器/NAS 上挂载为 `MUSIC_DIR`，再使用主服务扫描。

## 重要约束

- 不要在生产机执行源码构建。
- 不要执行 `docker compose down -v`，避免删除数据库和 CAS volume。
- 发布镜像优先使用 digest，而不是浮动标签。
- 升级前必须确认数据库备份成功。
- `ADMIN_API_TOKEN` 没有隐式回退来源，生产部署应显式配置；缺失时服务仍会 ready，但管理 API 关闭，只能靠启动 WARN 与 `/api/health` 的 `adminApi` 字段发现。
- `app` / `db` 默认 `restart: unless-stopped`，宿主或 Docker 重启、容器崩溃后自动恢复；`cas-init` 等一次性服务保持 `restart: "no"`。
- 生产叠加 `docker-compose.production.yml` 后不向宿主发布 PostgreSQL 端口；只使用 `docker-compose.yml` 时端口映射为 `127.0.0.1:5432:5432`，仅供本机调试。
- Windows（git-bash/MSYS）下为 `BACKUP_DIR` 使用宿主绝对路径（如 `E:/deploy/backups`）；`db-backup.sh` 已用 `sh -c` 传入容器内 `/tmp` 路径，不受 MSYS 参数路径转换影响。
- `release-preflight` / `release-smoke` 需要可用的 JSON 解析器（`jq` 优先，其次 `python3` / `python`）；缺失时会在升级前明确失败，不会把解析失败误报成「接口字段为空」。
- Windows（git-bash/MSYS）下脚本不把 `/dev/null` 传给 curl 的 `-o`（mingw 版 curl 会因写失败退出 23）：`release-smoke.sh` 与 native 脚本按平台改用 `NUL`，静态交付与端口占用检查不再出现假 FAIL。
- 项目名由 `docker-compose.yml` 的 `name: orzmusic` 兜底（v0.0.6 起），按目录切换版本不会新建空数据库。早期版本部署包仍需手动保持 `COMPOSE_PROJECT_NAME` 一致。
- 镜像同时发布 `linux/amd64` 与 `linux/arm64`（v0.0.6 起），Docker 按运行平台自动拉取对应变体，Apple Silicon 生产机无需额外配置。
- 自定义 Compose 配置统一用 `COMPOSE_BASE`（空格分隔的 `-f` 参数）；不要用 docker compose 原生语义的 `COMPOSE_FILE`（冒号分隔路径列表），两种语义混用会让 `db-backup` 失败。
