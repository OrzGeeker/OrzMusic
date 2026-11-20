# Web 与 Vapor 的目录/部署拆分评估(结论:暂不做)

> 最后更新：2026-11-20
> 状态：已评估,**暂不推进**;满足下面「触发条件」时重开。

## 现状(评估时的事实)

| 事实 | 位置 |
|---|---|
| `Resources/` 下**只有** `Public/` 与 `Views/` 两个目录,没有任何服务端资源 | `Resources/` |
| Vapor 用 `publicDirectory = <resources>/Public/` 提供静态文件;views 走默认 `Resources/Views` | `Sources/App/configure.swift:25` |
| 页面是**服务端渲染**的 Leaf 模板(240 行,只有 1 个注入变量 `playbackDiagnosticsEnabled`) | `Resources/Views/player.leaf`、`Sources/App/Controllers/FrontendController.swift` |
| 前端**没有构建步骤**:手写 JS/CSS + SDK 提供的 WASM,没有 `package.json` | `Resources/Public/audio` |
| 镜像内运行时布局是 `/app/Resources`(build 阶段 `cp -Ra /build/Resources`) | `Dockerfile:61` |
| 部署包(`package-deploy.sh` 产出的 tar)不含 `Resources` —— 它只带 compose/脚本,镜像由 GHCR 拉 | `script/package-deploy.sh` |
| Web SDK 由脚本写入 `Resources/Public/audio` | `script/update-audio-core-web.sh:6` |

结论:**Web 与 Vapor 是同一个进程、同一份镜像、同一条发布线**;页面由服务端渲染,前端没有独立产物。

## 两个候选方案

### A. 只做目录拆分:`Resources/{Public,Views}` → `clients/web/{public,views}`

改动面(已核对):

- `Sources/App/configure.swift`:2 行(public / views 目录)
- `Dockerfile`:1 处(把 `clients/web` 拷进镜像的同一相对位置)
- `script/update-audio-core-web.sh`:`OUT` 指向新目录
- `Tests/Browser/*.test.mjs`:10 处路径
- `Docs/`、`AGENTS.md`、`README.md` 引用
- 建议同时补一条 `app.test(.GET, "/")`,让 CI 覆盖「页面还能渲染」

**收益**:源码归属与 `clients/*` 一致,「前端代码不在服务端目录里」这件事更直观。

**代价**:

- 运行时路径多一层间接:本地 `swift run` 与容器都要按新路径配置,`/app/Resources` 这个既定布局要么保留一份映射、要么一起改;
- 部署链路(镜像构建)引入非标准路径,排查部署问题时多一个「东西到底在哪」的疑问;
- 对用户、对发布节奏、对回滚**零收益**。

### B. 真正的前后端分离(前端独立部署)

需要:把 `player.leaf` 静态化(处理那 1 个注入变量)→ 前端用绝对 API 地址 + CORS → 独立静态托管/CDN → 服务端只出 API。

**收益**:前端可独立发布/回滚,能上 CDN,服务端重启不影响静态资源。

**代价**:架构级改动,牵动模板、配置、部署与文档,且会引入跨域与配置项。

## 结论

**不做 A。** A 是 B 的中间态:它拿不到 B 的收益,却先支付了一部分部署复杂度。
Web 与 Vapor 同生命周期、同镜像发布,单独拆目录只会让部署变复杂。

如果确实想要「目录看起来一致」,在 `clients/README.md` 里说明 Web 的当前位置与原因就够了(已写)。

## 触发条件(满足任一即重开,并且**直接做 B**)

1. 前端需要独立发布节奏(改一行文案不用重建/重启服务);
2. 需要上 CDN 或独立静态托管;
3. 前端出现构建步骤(打包、TS、依赖管理),`Resources/` 不再是"原样静态文件";
4. 出现第二套 UI 复用同一 API,且需要独立部署。

## 若将来做 B,顺序建议

1. 先加 `app.test(.GET, "/")` 断言页面 200 + 关键标记(现在没有,靠它兜底);
2. `player.leaf` 的注入变量改为 `GET /api/client-config`(或给默认值),模板改为纯静态 HTML;
3. 前端资源改为独立产物 + 绝对 API 基址(必要时开 CORS);
4. 静态托管上线后,服务端保留 `/` 的 301 或移除路由,`FileMiddleware` 下线。
