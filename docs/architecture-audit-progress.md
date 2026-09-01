# 架构审查修复进度交接（2026-09-01）

本文件只记录“修到哪了、怎么接着修”，缺陷清单本体在 `docs/architecture-audit-2026-08.md`。

## 当前进度

- 清单总计 249 条：**已修 218，剩余 31**。
- 已推送批次（main）：`b7ee23f6` → `b51f339c` → `971d2b63`（第三批，CI 全绿 run 33416566471），第四批见下。

## 工作方式（硬约束，务必遵守）

- **禁止本地构建/测试**（`AGENTS.md`）：Gradle 只能在 GitHub Actions 里跑。本机 `services.gradle.org` 超时，连 wrapper 都下不来。
- 唯一验证者是 push 到 main 触发的 `build-pre-release.yaml`（Verify：`:sdk:assemble` + JVM 单测 + Android Lint，之后 AlphaPreRelease / MetaRelease）。
- `git add` 必须显式列文件，不用 `-A`；`.audit-reports/` 是草稿目录，永不提交。
- push 前 `gh auth setup-git`，并带 `GIT_TERMINAL_PROMPT=0`（否则本机会挂在凭据交互上）。
- CI 判绿只信逐 job 结论，不信 combined status：
  `gh api repos/Chloemlla/ClashMetaForAndroid/actions/runs/<id>/jobs --jq '.jobs[]|"\(.name) \(.conclusion)"'`
  轮询用 until 循环，`gh run watch --exit-status` 在 Windows 上不可靠。
- 子代理的完成报告不可信，只有 `git diff` 与读代码算验证（本批已两次抓到子代理虚报）。
- 并行修复时给每个子代理**互不相交**的文件集，否则互相覆盖。

## 提交签名状态（需要用户决定）

用户在本批开始前说“我去睡觉了先禁止提交签名”，因此仓库里设了 `git config --local commit.gpgsign false`（global 仍为 true）。
`b7ee23f6` / `b51f339c` / `971d2b63` 及第四批提交均**未签名**。用户醒来后若要恢复：

```
git config --local --unset commit.gpgsign   # 回到 global=true
```

已有的未签名提交需要用户自行决定是否 rebase 重签（会重写历史，需其明确同意）。

## 第四批（本次）内容

40 条：`A-10 A-11 A-31 A-32 A-38 A-39 A-40 B-44 B-164…B-196（除 B-185）`。四个方向：
迁移链路（LegacyMigration 幂等/失败可见/不删共享存储源文件、Database 只在 `:background` 启动迁移、MigrationProvider 私钥不驻留 cacheDir）、
配置处理链路（ProfileProcessor 进程锁超时 + age 私钥锁 + 流量字段兜底、ProfileManager 阻塞调用挪到 IO）、
生命周期与隧道（ClashManager 选择写入不阻塞 Binder、日志通道关闭自恢复、requestGc 按内存等级合并、TunService `onRevoke`）、
伙伴安全与 FilesProvider（授权决策一次提交 + 排除备份、配对提示按包冷却、DocumentsProvider 全部改 IO 调度并尊重 `CancellationSignal`）。

带偏差落地的条目已在清单里加了“落地：”说明：`A-39`（无崩溃上报）、`B-44`（未做 Keystore 加密）、`B-166`（`SelectionDao` 未改 suspend）、`B-172`（`cloneToPending` 仍在 `pick` 内）、`B-174`（未加 manifest 级权限）。

## 剩余 31 条的建议分批

- **可直接照修（service/core 局部）**：`B-29`（组延迟传错参数，延迟恒 0）、`B-33`（NetworkCallback 注册失败被吞）、`B-53`、`B-54`、`B-56`、`B-51`、`B-108`、`B-110`、`B-90`、`A-34`。
- **需要设计决定**：`B-28`（失效转移无后台驱动者）、`B-15`（跨进程 scenes 读改写）、`B-39`（计费周期重置）、`B-42`（场景评估防抖）、`B-45`（三条 ticker 合一）、`B-35`（抓包明文落盘）、`A-30`（ConnectionSnapshot 过 Parcel）、`B-65`。
- **模块/构建层重构，单独开批**：`B-27`、`B-136`、`B-185`、`C-01`、`C-02`、`C-04`、`C-05`。
- **需补测试**：`B-57`（现有单测只覆盖 happy path）。
- **综述型（C-06…C-10）**：是模式性结论，建议在对应具体条目修完后再回填结论，不单独改代码。

## 仍然悬而未决（需要用户拍板）

- `release.keystore` 仍能从 git 历史（`8b2ef8aa`）取出。修掉需要重写历史 + 强推 + 换签名密钥，**未执行**。
- `A-37`（应用锁被两个控制入口绕过）是产品取舍，清单里按“不自行修复”处理。
- `LUMEN_CRASH_READ_PACKAGES_TOKEN` 已过期，目前靠 local-maven 暂存 aar 消费 Project-Lumen SDK。
