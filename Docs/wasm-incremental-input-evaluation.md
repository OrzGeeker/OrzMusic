# WASM 增量输入可行性评估

> 结论日期：2026-07-26  
> SDK：OrzAudioCore 1.3.0，ABI v1.0  
> 结论：停止 OrzMusic 生产实现；若未来指标达到门槛，先在 OrzAudioCore 建立新 ABI backlog。

## 结论

ABI v1 不支持增量输入。唯一创建入口是：

```c
orz_decoder_create_memory(const uint8_t *data, size_t size, ...)
```

没有 `feed`、`read_at`、`need_data` 或流回调入口。浏览器 Worker 必须先完成 `response.arrayBuffer()`，再把完整输入交给 SDK。Range 请求即使更快拿到前 64/256 KiB，也无法用这些字节创建当前 decoder，因此不能改善当前生产路径首帧。

本任务不修改 OrzMusic 播放代码，也不在应用仓库私自扩展 ABI。候选工作转为 SDK backlog `AUDIOCORE-INCREMENTAL-001`，且只有达到文末门槛才启动。

## 当前输入与随机访问边界

对 OrzMusic 而言，当前 23 个 WASM decoder 都“必须完整输入”，因为它们全部经过同一个 `create_memory` ABI。这是当前实现约束，不代表每个上游库理论上都无法流式化。

| 解码族 | 格式 | 当前结论 | 增量化难点 |
|:-------|:-----|:---------|:-----------|
| openmpt | XM/MOD/IT/S3M/MO3/MTM/FC | 完整输入 | 模块头、pattern、sample 可分散在文件中；seek/subsong 需要稳定索引。libopenmpt 有同步 seek/read callback API，但 WASM callback 不能同步等待异步 HTTP Range。 |
| gme / sidplayfp | NSF/SPC/SID | 完整输入 | 当前 adapter 从内存装载整个镜像；文件本身很小，Range 往返通常得不偿失。 |
| sc68/asap/ahx2play | SC68/SAP/AHX/THX | 完整输入 | 容器/子歌索引与随机 seek 需先解析；SC68 生产策略目前仍是 serverDecode。 |
| MIDI/V2M | MID/V2M | 完整输入 | 时间线、事件及 synth 数据需建立全局状态；seek 需要可重复恢复。 |
| YM/SoundMon/AdPlug | YM/BP/RAD/D00/HSC/AMD | 完整输入 | 当前均为 memory adapter；多数文件只有数 KiB 到数十 KiB，没有分片收益。 |

libopenmpt 官方 C API确实提供 seek/read/tell stream callbacks，并说明输入流需要支持 seek 到文件尾；这支持“可做随机访问虚拟文件”的理论方向，但不是顺序 feed，也不能直接跨越浏览器同步 WASM 与异步 fetch 的边界。官方文档：[libopenmpt C API](https://lib.openmpt.org/doc/group__libopenmpt__c.html)。

## 数据与 Range 实验

真实曲库样本统计（bytes）：

| 格式 | 数量 | 中位数 | P95 | 最大 |
|:-----|-----:|-------:|----:|-----:|
| XM | 3396 | 43,783 | 769,439 | 5,506,935 |
| MOD | 1093 | 29,670 | 276,204 | 1,409,542 |
| IT | 254 | 97,204 | 1,120,975 | 4,449,067 |
| V2M | 130 | 67,948 | 502,099 | 1,524,976 |
| MID | 110 | 35,220 | 91,816 | 552,559 |
| SID/YM/SAP/AHX | 164 | 3,028～5,488 | 5,103～18,016 | 7,753～30,857 |

本机 HTTP 对最大 XM（5,506,935 bytes）各运行 5 次：

| 请求 | 下载字节 | 总耗时中位数 |
|:-----|---------:|-------------:|
| 完整文件 | 5,506,935 | 16.14 ms |
| Range 0～64 KiB | 65,536 | 3.96 ms |
| Range 0～256 KiB | 262,144 | 3.82 ms |

Range 能减少首批字节的传输时间，但 ABI v1 无法消费分片，所以实际可播放时间仍必须等待完整文件。对绝大多数曲目，输入文件还小于已经按交互预热的 full WASM bundle（约 2.8 MiB）；因此 PERF-019 的模块预热/复用优先级高于增量输入。

## 内存与语义要求

当前大文件至少同时经历浏览器 `ArrayBuffer`、Worker `Uint8Array` 视图以及 SDK/WASM 内部输入拷贝。Transferable 会分离主线程 buffer，但不能消除 Worker 与 decoder 的输入内存。若新增增量 ABI，必须同时满足：

- 以 `(offset, length)` 管理有上限的稀疏 chunk cache，验证文件总长及最终内容哈希。
- decoder 返回明确的 `NEED_DATA` 和所需区间；不能在 C callback 中同步阻塞等待网络。
- seek 增加 generation，取消旧 Range；已有 chunk 可复用，但旧 decoder 输出不得进入新 ring。
- subsong 列表只有在索引完整后发布；选择 subsong 后仍使用同一文件身份与 generation。
- stop 同时 abort fetch、调用 decoder cancel、销毁 handle，并释放未引用 chunk。
- 错误恢复区分网络缺块、文件截断、decoder corrupt-data 和用户取消。
- 对每个 decoder 单独声明 `sequential`、`randomAccess` 或 `fullInput` 能力，不能用一个乐观策略覆盖所有格式。

## SDK backlog 边界

建议的 ABI 方向是显式异步拉取状态，而不是把 JavaScript fetch 隐藏在同步 C callback 中：

1. `orz_decoder_create_incremental_v2(...)`
2. `orz_decoder_feed_v2(handle, offset, bytes, eof)`
3. create/render/seek 可返回 `ORZ_NEED_DATA`
4. `orz_decoder_get_input_requests_v2(...)` 返回一个或多个缺失区间
5. manifest 暴露每种 decoder 的输入能力和最小首帧条件

这属于公共 ABI 与所有消费端的兼容工作，必须在 OrzAudioCore 仓库实现、发布、锁定制品校验后，OrzMusic 才能接入。

## 重新启动门槛

只有同时满足以下条件才领取 `AUDIOCORE-INCREMENTAL-001`：

- PERF-018 的真实外网/弱网样本证明资源 fetch 占 WASM P95 首帧至少 30%。
- 目标格式 P95 输入至少 1 MiB，或最大文件首帧持续超出预算。
- PERF-019 的 Worker 预热与复用后仍无法达到首帧预算。
- 至少一个高占比 decoder 原型能在不下载完整文件时稳定产出首帧，并通过 seek、subsong、快速切歌和取消测试。

当前真实曲库与本机结果不满足这些条件，因此结论是保留完整 `arrayBuffer()`，不增加 Range 调度复杂度。
