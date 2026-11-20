# Android 客户端迁入 `OrzGeeker/OrzMusic` —— 目录结构与实施计划


> **实施状态(2026-11-20)**:已按本计划迁入,差异如实记在文末「实施记录」。

> 结论先行:**放 `clients/android/`,做成一个自带 Gradle 构建的独立子工程**;
> 本次是「加法式迁移」—— 不动 `Sources/`(server)与 `Resources/`(web),
> 也不做 [architecture.md](architecture.md#5-合并回-orzgeekerorzmusic-的目录计划) 里
> `server/` `sdk/` `core/` 的整体重排。那一步等 Android 先在新家跑通再谈。
>
> 工具与设备流程留在 **EasyAndroid**:它负责「通用流程」,客户端仓库只放客户端。

---

## 1. 为什么是 `clients/android/`

上游 `architecture.md` §5 已经定了终态目录(`server/` `clients/*` `sdk/*` `core/*`),
本计划只落地其中 **`clients/android` 这一格**,理由:

| 理由 | 说明 |
|---|---|
| 与已定计划一致 | 终态就是 `clients/{web,android,apple,desktop}`,现在先放 android,后面搬 web 时不用再改路径 |
| 不打断 server | `Package.swift` / `Sources/` / `Resources/` 一个字节不动,现有 CI、Docker、Release 全部不受影响 |
| 保留 Apple 的位置 | `com.jokerhub.orzmusic.apple` 是预留包名,`clients/apple` 顺理成章 |
| 大写/小写 | 跟随上游既有约定:源码目录大写(`Sources` `Resources` `Docs` `Tests`),**流程目录小写**(`script` `clients`);所以是 `clients/android`,不是 `Clients/Android` |
| 独立构建 | 客户端自带 `gradlew` + `settings.gradle.kts`,仓库根**不出现** Gradle 文件 —— 服务端开发者 `./gradlew` 不会被意外触发,Android 开发者也不依赖 Swift 工具链 |

---

## 2. 目标结构(阶段一)

```
OrzGeeker/OrzMusic/
├── Package.swift                 # 不动
├── Sources/                      # 不动(server)
├── Resources/                    # 不动(web player + brand)
│   └── Public/brand/orz-logo.svg # ★ 客户端图标从这里派生(单一来源)
├── Docs/                         # 服务端/协议文档
│   ├── api.md                    # ★ 新增:API 唯一口径(从客户端 backend-api.md 合并上来)
│   └── format-support.md         # 客户端改为引用,不再抄一份
├── script/                       # 不动(server)
├── Makefile                      # ★ 增加 android-* 目标(见 §5)
├── clients/                      # ★ 新增
│   ├── README.md                 # ★ 两端共用口径:API 版本、格式、播放策略、品牌
│   └── android/                  # ★ 从 EasyAndroid 的 apps/OrzMusic 迁入
│       ├── gradlew  gradlew.bat  # 必须是 100755(Linux CI 会 Permission denied)
│       ├── gradle/  gradle.properties  settings.gradle.kts  build.gradle.kts
│       ├── version.properties    # ★ 客户端版本唯一来源(与根 VERSION 无关)
│       ├── CHANGELOG.md          # ★ 客户端变更(与服务端 CHANGELOG 分开)
│       ├── signing-manifest.txt  # ★ 只放指纹;密钥走 CI secrets
│       ├── app/                  # :app
│       ├── core/                 # :core(纯 Kotlin;后续 KMP 见 §9)
│       ├── audio-kit/            # :audio-kit(JNI + jniLibs + native/)
│       ├── docs/                 # 客户端内文档:architecture / playback / ui-parity / 本文件
│       ├── store/                # play / cn / privacy-policy / assets/
│       ├── script/               # 客户端自己的脚本:build-native / store-assets / release
│       └── THIRD_PARTY_NOTICES.md
└── .github/workflows/
    ├── orzmusic.yml              # 不动(但注意:它**故意没有 paths 过滤**,见 §4)
    ├── android.yml               # ★ 新增:PR 门禁
    └── android-release.yml       # ★ 新增:android-v* tag → 出 APK/AAB
```

**终态预留**:等 `core/kotlin`(KMP)与 `sdk/audio-kit-android` 真做起来以后,
`clients/android` 用 Gradle **composite build**(`includeBuild("../../core/kotlin")`)
把模块接进来,不必把 Gradle 根提到仓库根。这样阶段一的布局不会白做。

---

## 3. 逐文件改造清单

迁移时**必须改**的耦合点(其余文件原样带过去):

| 文件 | 现在的耦合 | 迁移后 |
|---|---|---|
| `app/build.gradle.kts` | `findUpward("version.properties")`、`findUpward("keystore.properties")` 向上找 | **代码不改**:把 `version.properties` 放在 `clients/android/` 即可;`keystore.properties` 由 CI 写进 `clients/android/`(gitignore) |
| 仓库根 `version.properties` | 全仓唯一版本来源(所有 `apps/*` 共用) | `clients/android/version.properties`,客户端自己一份;根 `VERSION` 继续只表示 server |
| `README.md` | `../../version.properties`、`../../docs/03`、`../../docs/11` | 版本链接改 `../version.properties`;设备/上架操作**不再写命令路径**,改为「用 EasyAndroid 的工具,APK 传绝对路径」 |
| `docs/README.md` | 指向 `../../../docs/`(EasyAndroid) | 去掉强依赖;必须引用的通用知识用 EasyAndroid 的**绝对 URL**,并注明「工具不在本仓库」 |
| `audio-kit/scripts/build-android.sh` | `source ../../../../tools/_common.sh` | 内联所需 helper(平台判定 / `TMP` / `mktmp` / `run_timeout`),**不再跨仓 source**;产物路径改成相对 `clients/android` |
| `docs/backend-api.md` | 服务端 API 的**客户端副本**(13 KB) | 服务端侧新增 `Docs/api.md` 作唯一来源;客户端只保留客户端特有口径(缓存键、降级、错误映射)并链接过去 |
| `README.md` 格式表 | 与 `Docs/format-support.md` 重复 | 只留「Android 编入 / 未编入 native」这一列的差异,其余引用服务端文档 |
| `store/*` | `publish.properties` 的 `targets=github,play,cn` | 改成 `targets=play,cn`(GitHub Release 改由 `android-v*` 工作流产出);其余原样 |
| 图标 / banner | 迁移前是自造的「薄荷五柱」 | 从 `Resources/Public/brand/orz-logo.svg` 派生(近黑圆角底 + 暖白 O 环 + 薄荷五柱),生成脚本放 `clients/android/script/gen-brand-assets.sh` |
| `signing-manifest.txt` | 在 EasyAndroid 仓库根 | 复制 OrzMusic 那一段到 `clients/android/signing-manifest.txt`;EasyAndroid 侧同步裁掉 |
| 根 `.gitignore` | 已有 `dist/` `data/music/`,缺 Android 项 | 追加 `.gradle/` `.kotlin/` `build/` `local.properties` `*.jks` `keystore.properties` `.cxx/` |

**两个容易踩的坑**(迁移当天就会遇到):

1. **`gradlew` 的可执行位**:git 记的是 mode 位。WSL/Git Bash 本地文件永远是 777,
   看不出来;Linux CI 拿到 `100644` 就是 `Permission denied`。
   导入后立刻验:`git ls-files -s clients/android/gradlew` 必须是 `100755`。
2. **换行**:Fork/拷贝时若经过 Windows 工具可能带成 CRLF,`gradlew` / `*.sh` 会跑不起来。
   导入后 `file clients/android/gradlew` 确认是 `ASCII text`(不含 `CRLF`)。

---

## 4. CI 设计

上游 `orzmusic.yml` 有一条写在注释里的硬约束:**刻意不设 `paths` 过滤** ——
required status check 与 paths 同时存在时,没触及路径的 PR 会永远停在 `Expected`。
所以 Android 门禁只能是**常开 job**,不能只在 `clients/android/**` 变更时跑。

### 4.1 PR 门禁:`android.yml`(或并入 `orzmusic.yml` 作为第三个 job)

```yaml
  android-client:
    runs-on: ubuntu-24.04            # 与既有 job 一致,不用 -latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: {distribution: temurin, java-version: '17', cache: gradle}
      - run: |
          cd clients/android
          ./gradlew --no-daemon --console=plain --max-workers=2 \
            :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
            -Proborazzi.test.record=false
```

- 截图测试在 CI 上**只断言不出图**(`-Proborazzi.test.record=false`),
  出图是本地迭代用的;这两件事已经在 app 的 `testOptions` 里分流好了。
- 与另外两个 job 并行,门禁总时长基本不涨;代价是每个 PR 多一台 runner。
- 若哪天想省资源,正确做法是让**整个 workflow** 拆分并重新设计 required checks,
  而不是给新 job 单独加 `paths`。

### 4.2 发布:`android-release.yml`

```yaml
on:
  push:
    tags: ["android-v[0-9]+.[0-9]+.[0-9]+"]
```

步骤:校验 tag == `clients/android/version.properties` → 从 secrets 还原
`keystore.properties` + `release.jks` → `:app:assembleRelease :app:bundleRelease`
→ `apksigner verify --print-certs` 比对 `signing-manifest.txt` 指纹 →
`gh release create` 挂 APK + AAB。

需要的 secrets:

| secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `release.jks` 的 base64(`base64 -w0`) |
| `ANDROID_STORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | 与 `keystore.properties` 对应 |

**不要**复用手写 tag 的 `vX.Y.Z`:那个 tag 的语义是 server 版本,且
`release.yml` 会硬校验 `VERSION` 相等。`android-v*` 是另一个命名空间,
两个版本线互不影响。

---

## 5. 版本、变更日志与发版命令

| 项 | 口径 |
|---|---|
| 客户端版本 | `clients/android/version.properties`,独立 SemVer;`versionCode = MAJOR*10000+MINOR*100+PATCH` |
| server 版本 | 根 `VERSION` + `vX.Y.Z` tag,不动 |
| 变更日志 | `clients/android/CHANGELOG.md`(不写进根 `CHANGELOG.md`) |
| tag | `android-vX.Y.Z`(前缀即命名空间) |
| 发版入口 | 根 `Makefile` 的 `android-build` / `android-test` / `android-release`;`main` 受保护,所以是「准备分支 → PR 合并 → `--tag`」两步 |

```make
android-build:            ## 构建 debug 包
	cd clients/android && ./gradlew :app:assembleDebug

android-release:          ## 发版:<version> 取自 version.properties,推 android-v* tag
	./scripts/android-release.sh
```

`scripts/android-release.sh` 是 EasyAndroid `tools/release.sh` 的**瘦身移植**:
按 `clients/android/**` 范围内的 Conventional Commits 推导段位 → 写
`version.properties` 的版本历史 + `CHANGELOG.md` → 提交到 `release/android-x.y.z` 分支并推送。
**打 tag 是第二步**:`main` 上有 `ai-gate` 规则集(rebase 合并 + required checks),
直接把发版提交推到 main 会被拒;合并之后在 main 上跑 `--tag` 才推 `android-v*`。

> 版本线建议:从 `0.6.1` 继续,迁移本身是 `refactor`(不涨版本);
> 第一次从新家发正式包时出 `0.7.0`(`android-v0.7.0`)。

---

## 6. EasyAndroid 侧要做的事(「通用流程」的边界)

迁移完成、新家验证通过之后:

1. **删掉 `apps/OrzMusic/`**(单个提交,提交信息里写明迁到哪个仓库/提交)。
   `apps/` 变空 → 后续新应用仍然可以用 `tools/new-app.sh` 在本地起,或者在各自仓库里起。
2. **保留 `tools/`**(设备操作 + 通用发布机器),并在
   `docs/` 新增一篇《客户端仓外置后怎么复用本仓库工具》:
   - `tv-install.sh` / `ui-dump.sh` / `pico-panel.sh` 本来就是**吃路径**的,
     对任意仓库构建出的 APK 都能用 → 设备流程不用复制一份;
   - 签名/上架/版本号约定继续以 `docs/06`、`docs/11` 为准;
   - `tools/gen-keystore.sh` 还是生成密钥的地方,CI secrets 从它导出的凭据包里取。
3. **`signing-manifest.txt`**:OrzMusic 那段随客户端迁走,本仓库不要再留「有效条目」以外的引用。
4. **AGENTS.md §1/§8** 更新:仓库定位从「业务应用容器」改为「Android 通用流程 + 模板 + 自检工程」,
   并指向客户端仓库。

> 结论:**EasyAndroid 不再承担 OrzMusic 的构建与发版**,只承担它装不上/验不了时的那套设备流程。

---

## 7. 迁移步骤(建议按这个顺序做,每步都能验)

```bash
# 0. 在 EasyAndroid 侧:确认工作区干净、当前版本
cd EasyAndroid && git status --short && grep ^version= version.properties

# 1. 导出(方案 A:单提交导入;方案 B:保留历史,见 §9)
git archive --format=tar HEAD:apps/OrzMusic > /tmp/android-client.tar

# 2. 在 OrzMusic 侧新建分支并解包
cd OrzMusic && git checkout -b feat/android-client
mkdir -p clients/android && tar -xf /tmp/android-client.tar -C clients/android

# 3. 改 §3 里那批耦合点(version.properties / 脚本 / 文档链接 / 图标)

# 4. 本地验收(不依赖 EasyAndroid)
cd clients/android
git ls-files -s gradlew                     # 期望 100755
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  -Proborazzi.test.record=false
./gradlew :app:lintDebug

# 5. 加 CI / Makefile 目标,推 PR,确认三个门禁都绿

# 6. 发 0.7.0:准备分支 → PR → 合并 → 在 main 上 --tag,确认 Release 里有签名 APK + AAB

# 7. 真机验收(仍用 EasyAndroid 的设备脚本,APK 传绝对路径)
bash EasyAndroid/tools/tv-install.sh /path/OrzMusic/clients/android/app/build/outputs/apk/debug/app-debug.apk
bash EasyAndroid/tools/pico-panel.sh com.jokerhub.orzmusic.android awake
bash EasyAndroid/tools/ui-dump.sh com.jokerhub.orzmusic.android

# 8. 回 EasyAndroid 删 apps/OrzMusic,更新 AGENTS/docs,跑一次 verify-all
```

`git archive` 会保留文件 mode;用 `cp -r` 从 WSL 拷则要看 §3 的坑 1。

---

## 8. 验收清单(迁移完成 = 以下全绿)

- [ ] 干净 clone 的 OrzMusic 仓库里,`cd clients/android && ./gradlew assembleDebug` 能过(**不 clone EasyAndroid**)
- [ ] `git grep -n 'tools/_common.sh\|apps/OrzMusic\|\.\./\.\./version.properties' -- clients/android` 零命中
- [ ] `git ls-files -s clients/android/gradlew` = `100755`;`file` 不是 CRLF
- [ ] PR 只改 `Docs/` 时,三个 job 仍全部上报并变绿(验证没有踩 paths 过滤)
- [ ] `android-v0.7.0` tag 产出 Release,APK 的 `versionName=0.7.0`、指纹与 `signing-manifest.txt` 一致
- [ ] APK 能装到 TCL 电视(走 `tv-install.sh`)与 Pico;后台播放/声场/输入法三项抽查通过
- [ ] EasyAndroid 删完 `apps/OrzMusic` 后 `bash tools/verify-all.sh` 全绿,文档无死链

---

## 9. 待决事项

| 事项 | 选项 | 建议 |
|---|---|---|
| 历史是否保留 | A 单提交导入(信息里写 `EasyAndroid@<sha>`);B `git subtree add` 保留文件历史 | **A**。客户端历史只有 6 个提交,EasyAndroid 仍公开可查;subtree 会把另一个仓库的提交图带进来,PR 噪音大 |
| `audio-kit` 的 4.9 MB `.so` | 继续入库 / 改为按 `audio-core-sdk.lock.json` 拉取 | **先继续入库**(保证离线可构建),随后移植一个 `script/update-audio-core-android.sh` 对齐 server/web 的锁文件机制。JNI 层长期应上移到 `OrzAudioCore`(其 backlog P3-1 已列) |
| `:core` 是否现在就 KMP | 现在搬 / 以后搬 | **以后**。用 composite build 接,不阻塞迁移 |
| 商店检查脚本 | vendor 一个瘦版 `script/store-check.sh` / 让 EasyAndroid 的 `store-preflight.sh` 支持 `--repo` | 先 vendor 瘦版(几十行,只查 badging/签名/ABI/debug 钩子),后续再收敛 |
| 是否把 `clients/README.md` 立起来 | 现在就写 / 以后 | **现在**。它是 client 侧的「单一事实来源」入口(API 版本、格式、策略、品牌),否则两端文档会各写一份 |
| 品牌图谁生成 | 客户端脚本从 SVG 派生 / 手工导出 | **脚本派生**,并把生成命令写进 `clients/android/store/README.md` |

---

## 10. 之后(不属于本次迁移)

1. ~~`clients/web`:把 `Resources/Public` + `Resources/Views` 挪进 `clients/web/`~~ —— **已评估,决定不做**:
   Web 与 Vapor 同进程、同镜像、服务端渲染,单拆目录拿不到收益,反而让部署路径多一层映射。
   触发条件与将来若做「真正前后端分离」的顺序见
   [Docs/backlog/web-client-split-evaluation.md](../../../Docs/backlog/web-client-split-evaluation.md)。
2. `core/kotlin`(KMP)+ `sdk/audio-kit-android`:Android 改为消费发布坐标,`clients/android` 用 composite build 接入。
3. `Docs/api.md` 由服务端拥有,`core/spec` 放 OpenAPI + 队列/策略测试向量,各端做 conformance。
4. `clients/apple`:SwiftUI 实现同一套 `OrzAudioPlayer` / `PlaybackQueue` / `PlaybackPolicy`。


---

## 11. 实施记录(实际落地时的差异)

| 计划 | 实际 | 原因 |
|---|---|---|
| 目录 `script/` | `scripts/` | 客户端原本就有 `scripts/gen-core-manifest.py`,不另起一个近似名字 |
| 单提交导入 | ✅ 单提交 | 提交信息注明来源 `EasyIndie/EasyAndroid@<sha>`,历史仍公开可查 |
| `audio-kit` 的 `.so` 转为按锁文件下载 | 暂缓,继续入库 | **上游没有 Android 产物**:OrzAudioCore v1.2.4 只发 linux/macos/xcframework/web,所以没有可锁定的 AAR。证据与解锁条件记在 [audio-kit/README.md](../audio-kit/README.md#为什么-so-还提交在本仓库2026-11-20-核实) |
| `scripts/store-preflight.sh` 移植 **瘦版** | `scripts/store-check.sh` | 只查包名 / 版本 / 签名指纹 / ABI / 非 debuggable / AAB 结构 / 人工 TODO |
| `publish.properties` 保留 `github` 渠道 | 改成 `targets=play,cn` | GitHub Release 由 `android-v*` 工作流产出,不走渠道配置 |
| 品牌图标「从 SVG 派生」 | ✅ 矢量按几何换算 + 512 图标直接拷贝 | 单一来源是 `Resources/Public/brand/`;`scripts/sync-brand-assets.sh` 一条命令同步 |
| 商店检查与发版的脚本目录 | `scripts/android-release.sh` | 提交信息用上游既有风格 `release: prepare X.Y.Z` |
