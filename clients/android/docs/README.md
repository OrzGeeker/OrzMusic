# OrzMusic 文档

客户端文档放在这里。两类内容**不在**本目录:

- **服务端契约**(HTTP API / 播放策略 / 分页与错误)→ [`Docs/api.md`](../../../Docs/api.md);
- **通用 Android 流程**(环境、设备安装/验收、签名策略、上架)→
  [EasyIndie/EasyAndroid](https://github.com/EasyIndie/EasyAndroid) 的 `docs/`。

| 文档 | 内容 |
|---|---|
| [backend-api.md](backend-api.md) | 客户端怎么消费三种播放策略、原生解码约束、客户端结构与实机验收记录(契约见 `Docs/api.md`) |
| [architecture.md](architecture.md) | 跨端架构:C/C++ / Swift / Web / Android 的 SDK 形态、`:core` / `:audio-kit` / `:app` 分层、server/clients 拆分与迁移计划 |
| [ui-parity.md](ui-parity.md) | 参考 Web 播放器的界面/功能映射、自适应规则、有意未搬的功能与验收方式 |
| [playback.md](playback.md) | MediaSession/后台播放/通知栏、PCM 声场、Pico BACK 焦点问题的定位与兜底 |
| [migration-upstream.md](migration-upstream.md) | 迁入 `OrzGeeker/OrzMusic` 的目录结构、CI/发版设计、改造清单与验收判据 |

相关:

- [../README.md](../README.md) —— 构建 / 安装 / 功能 / 已知限制
- [../store/play.md](../store/play.md) / [../store/cn.md](../store/cn.md) —— 商店人工清单
- [../audio-kit/README.md](../audio-kit/README.md) —— Android 解码 SDK(AAR / JNI / manifest)
- [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) —— 第三方许可证
