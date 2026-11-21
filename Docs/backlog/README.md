# 待推进文档

本目录收纳已经形成方向、但尚未完成落地的计划和任务清单。上层 `Docs/` 保留稳定项目说明、运行迁移指南和能力边界文档。

| 文档 | 内容 |
|:-----|:-----|
| [orz-audio-core-backlog.md](orz-audio-core-backlog.md) | 不阻塞当前功能的 OrzAudioCore SDK 后续任务清单。 |
| [android-pcm-streaming.md](android-pcm-streaming.md) | Android 从「整曲转 WAV」改为 `PcmStream` + `AudioTrack` 流式播放。**已实现**（含 Media3 `SimpleBasePlayer` 桥），待真机验收。 |
| [web-client-split-evaluation.md](web-client-split-evaluation.md) | Web 与 Vapor 的目录/部署拆分评估。**结论:暂不做**(只拆目录拿不到收益,反而让部署变复杂);列了重开的触发条件。 |

推进约定：

- 每次只领取一个文档中的一个任务编号。
- 优先保持任务边界，避免把相邻任务合并成大改动。
- 完成后同步更新对应任务状态、验收记录和必要的上层文档引用。

已完成的计划移至 [`../archived/`](../archived/) 保存，不继续作为待办领取。
