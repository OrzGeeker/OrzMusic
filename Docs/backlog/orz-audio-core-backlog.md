# OrzAudioCore 后续任务清单

> 最后更新：2026-07-22

这份清单记录 OrzAudioCore 下沉计划中尚未完成、但不阻塞 OrzMusic 当前功能的工作。任务按可独立推进的粒度拆分，默认适合交给 5.5 轻度模型逐项处理。

## 当前基线

- OrzMusic 已消费外置 OrzAudioCore SDK v1.3.0（含 Android 产物）。
- 内嵌 decoder 源码与旧构建脚本已从 OrzMusic 移除。
- C ABI v1、Swift 封装、Web/WASM 封装、manifest、full/lite WASM、缓存 fingerprint 和基础 CI 已落地。
- OrzMusic 当前播放功能不依赖以下剩余任务；这些任务主要提升 SDK 独立发布质量、平台覆盖、安全和可观测性。

## 执行原则

- 每次只领取一个任务，避免把验证范围扩大到不可控。
- 优先在 OrzAudioCore 独立仓库完成 SDK 侧工作，再按需更新 OrzMusic 的锁文件、文档或回归测试。
- 不改变 OrzMusic 播放策略，除非任务明确要求消费新的 SDK 能力。
- 涉及 Release artifact、CI、平台矩阵或缓存 fingerprint 的改动，必须同时更新文档和验证命令。
- 若只做调研或 CI 配置，可以不发布 SDK；若修改 decoder 行为或公共 ABI，必须发布新版本并在 OrzMusic 中锁定消费。

## 优先级 P1

### P1-1 建立 SDK ABI 兼容性报告

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore。
- 目标：在 CI 中生成公共 `orz_*` 符号和 `orz_audio_core.h` 结构布局报告，作为 release artifact 保存。
- 范围：新增脚本、CI job、README/Release 文档；不修改 ABI。
- 验收：
  - macOS 或 Linux CI 能输出 ABI 报告。
  - 报告只包含公共 `orz_*` 符号。
  - OrzMusic 无需改动，除非文档引用报告位置。
- 风险：低。注意不要把第三方库内部符号误判为公共 ABI。

### P1-2 增加 SDK 消费者示例工程

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore。
- 目标：提供最小 C、CMake 和 TypeScript/Web 消费示例，用于验证 SDK 制品能被外部项目独立使用。
- 范围：`examples/`、CI smoke、文档。
- 验收：
  - C 示例能加载本地 SDK artifact，probe 并 render 少量 PCM。
  - Web 示例能加载 npm/WASM 包并完成一次 decode smoke。
  - 示例不依赖 OrzMusic 目录结构。
- 风险：低到中。重点防止示例偷偷引用仓库内部源码。

### P1-3 安全构建：ASan 与 UBSan

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore。
- 目标：为核心 decoder conformance 增加 AddressSanitizer 和 UndefinedBehaviorSanitizer CI。
- 范围：CMake preset、CI job、故障文档。
- 验收：
  - sanitizer job 能跑完核心样本和截断/损坏输入测试。
  - 发现的问题单独修复，不在同一任务里做大范围重构。
- 风险：中。第三方库可能触发已有 UB，需要先分类为可修复、可抑制或上游问题。

## 优先级 P2

### P2-1 性能基线与回退阈值

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore，必要时同步 OrzMusic 文档。
- 目标：记录每类 decoder 的加载时间、首帧延迟、实时倍速和峰值内存，先报告，后续再设阻断阈值。
- 范围：benchmark 脚本、样本选择说明、CI artifact。
- 验收：
  - 输出 JSON/Markdown 报告。
  - 至少覆盖 `openmpt`、`sid`、`ym`、`v2m`、`sc68`、`adplug`、`bp`。
  - 浏览器/WASM 路径能记录解码倍速或主线程长任务指标。
- 风险：中。不同 CI 机器波动较大，首轮只做趋势记录，不直接阻断 release。

### P2-2 Fuzz 最小语料与入口

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore。
- 目标：建立 libFuzzer 或 AFL 可用的 `probe/create/render` fuzz 入口和最小 corpus。
- 范围：fuzz target、corpus README、可选 CI 手动任务。
- 验收：
  - 本地可运行短时 fuzz。
  - corpus 包含合法、截断、随机和边界长度输入。
  - fuzz target 不泄漏资源，能覆盖所有公开长度参数。
- 风险：中。不要把大型音乐样本直接提交进仓库。

### P2-3 SBOM 与第三方许可证包

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore。
- 目标：Release 中自动包含 SBOM、第三方组件版本和许可证清单。
- 范围：manifest 扩展、生成脚本、release workflow、文档。
- 验收：
  - Release artifact 包含 `THIRD_PARTY_NOTICES` 或等价文件。
  - manifest 中的 decoder 依赖版本与许可证可追溯。
  - OrzMusic 文档能指向 SDK release 的许可证包。
- 风险：低到中。主要是许可证文本归集要准确。

### P2-4 iOS Simulator 消费验证

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore。
- 目标：补齐 iOS simulator 下 Swift Package 或 XCFramework 消费 smoke。
- 范围：CI job、最小 Swift/iOS 测试宿主、文档。
- 验收：
  - CI 能在 iOS simulator 上链接 SDK 并运行最小 decode/probe。
  - 不要求引入 UI，也不要求音频设备播放。
- 风险：中。Xcode 版本和 simulator runtime 容易造成 CI 波动。

## 优先级 P3

### P3-1 Android JNI 原型

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore。
- 目标：验证 C ABI 能通过 Kotlin/JNI 消费，输出 AAR 原型。
- 范围：Android CMake preset、JNI wrapper、最小 Kotlin API、文档。
- 验收：
  - 至少支持 `probe`、`create`、`streamInfo`、`render`、`destroy`。
  - Java/Kotlin 层使用 `Closeable` 管理 native handle。
  - PCM 输出优先使用 direct buffer，避免每块数据重复拷贝。
- 风险：中到高。该任务是平台扩展，不影响 OrzMusic 当前功能。

### P3-2 Windows 构建预研

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore。
- 目标：补齐 Windows MSVC 或 MinGW 构建预研，确认第三方 decoder 的兼容问题清单。
- 范围：CMake preset、GitHub Actions matrix 草案、问题记录。
- 验收：
  - 至少能产出明确的可行路径或阻塞清单。
  - 不要求首轮完成所有 decoder full profile。
- 风险：中到高。第三方库和符号导出规则可能需要后续专项处理。

### P3-3 TSan 与多实例压力测试

- 建议模型：5.5 轻度。
- 仓库：OrzAudioCore。
- 目标：在 ThreadSanitizer 下运行多实例交错渲染和 12 路并行解码测试。
- 范围：测试用例、CI 手动任务、潜在 suppression 文档。
- 验收：
  - 同格式双实例交错 render 通过。
  - 至少 12 路不同实例并行 decode 通过。
  - 若第三方库存在已知全局状态，记录隔离策略或限制。
- 风险：中。可能揭示真实线程安全问题，修复应拆成后续单独任务。

## 优先级 P4

### P4-1 OrzMusic SDK 升级流程演练

- 建议模型：5.5 轻度。
- 仓库：OrzMusic。
- 目标：把 SDK 升级、校验、回滚和缓存失效流程整理成可重复 runbook。
- 范围：文档、可选 smoke 命令说明；不发布 SDK。
- 验收：
  - 文档包含升级前检查、更新锁文件、运行测试、观察缓存、回滚版本。
  - 明确哪些文件通常会变化，哪些生成物不应提交。
- 风险：低。适合作为熟悉项目的入门任务。

### P4-2 OrzMusic Web 回归样本清单

- 建议模型：5.5 轻度。
- 仓库：OrzMusic。
- 目标：把 directFile、wasmDecode、serverDecode 的代表曲目和预期行为固化到文档或测试夹具索引。
- 范围：文档、测试数据引用，不移动 CAS 原文件。
- 验收：
  - 每条样本包含格式、策略、是否应有声、是否支持 seek、历史问题备注。
  - 覆盖 YM 连续切歌、V2M 有声、SC68 服务端缓存、AdPlug HSC。
- 风险：低。注意不要提交大型音频文件。

### P4-3 文档同步检查

- 建议模型：5.5 轻度。
- 仓库：OrzMusic 与 OrzAudioCore。
- 目标：增加一个轻量脚本，检查 README、AGENTS、lock file 中的 SDK 版本是否一致。
- 范围：脚本、CI 或本地检查说明。
- 验收：
  - 当 `audio-core-sdk.lock.json` 升级后，文档版本遗漏会被提示。
  - 不阻断普通开发，建议先作为 warning。
- 风险：低。

## 推荐推进顺序

1. P4-1 OrzMusic SDK 升级流程演练。
2. P4-2 OrzMusic Web 回归样本清单。
3. P1-1 SDK ABI 兼容性报告。
4. P1-2 SDK 消费者示例工程。
5. P2-3 SBOM 与第三方许可证包。
6. P1-3 安全构建：ASan 与 UBSan。
7. P2-1 性能基线与回退阈值。
8. P2-2 Fuzz 最小语料与入口。
9. P2-4 iOS Simulator 消费验证。
10. P3-3 TSan 与多实例压力测试。
11. P3-1 Android JNI 原型。
12. P3-2 Windows 构建预研。

## 交接模板

后续可以直接把下面模板交给轻度模型：

```text
请领取 Docs/backlog/orz-audio-core-backlog.md 中的任务 <任务编号>。
先阅读 README.md、Docs/orz-audio-core.md、Docs/backlog/orz-audio-core-backlog.md 和相关脚本。
只实现该任务范围内的内容，保留无关工作区改动。
完成后运行与任务相关的最小验证，并更新任务文档中的状态或备注。
不要发布 SDK，除非该任务明确要求并已通过本地验证。
```
