# 更新日志 · OrzMusic Android 客户端

客户端版本独立于服务端(根目录 `VERSION`)。发版入口:
`bash clients/android/scripts/android-release.sh`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.7.0] - 2026-11-20

### 新增

- feat(android): 引入 Android 客户端(clients/android) (#21) ([917f3fd](https://github.com/OrzGeeker/OrzMusic/commit/917f3fd))

### 修复

- fix(android): 发版脚本生成的 CHANGELOG 只有标题没有条目 (#23) ([92a993d](https://github.com/OrzGeeker/OrzMusic/commit/92a993d))
- fix(android): 发版脚本适配受保护的 main —— 改成「准备分支 → PR → 打 tag」两步 (#22) ([9e3528e](https://github.com/OrzGeeker/OrzMusic/commit/9e3528e))
