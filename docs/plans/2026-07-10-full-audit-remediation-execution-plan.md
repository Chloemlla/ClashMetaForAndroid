# ClashMetaForAndroid 全量审计修复执行计划

## Internal grade

XL 规划、L/有界并行执行。根 agent 负责唯一需求/计划、代理热路径、架构边界、集成、验证、提交和最终完成声明；3 个 child lane 只提交各自文件范围内的实现收据。

## Wave 1 — 独立实现

### Unit A: 日志与应用列表性能

- F-02、F-03、F-04、F-09。
- 所有权：`app/.../log/**`、`LogcatActivity.kt`、`LogcatService.kt`、`LogcatDesign.kt`、`LogMessageAdapter.kt`、`AppAdapter.kt`、`AppInfo.kt`、`design/util/App.kt` 及对应测试。
- 验证：静态检查通知区间、I/O dispatcher、有界读取/文件配额、图标懒加载和缓存生命周期。

### Unit B: UI/UX、无障碍与交互状态

- F-06、F-07、F-08、F-10、F-11。
- 所有权：通用尺寸/toolbar XML、Profiles UI/Activity、AccessControl 超时、MainActivity 通知权限、strings 及对应测试。
- 验证：48dp、正确 contentDescription、删除确认、single-flight、timeout、权限拒绝恢复路径。

### Unit C: 安全、备份、供应链与发布

- F-12、F-13、F-14、F-15、F-16、F-17。
- 所有权：Manifest、External/Internal control、Gradle、`.github/workflows/**`、backup rules、Privacy Policy、签名/Geo 校验脚本和相关测试配置。
- 验证：默认外部拒绝、内部 shortcut 可用、无 tracked keystore、签名 fail-fast、固定 hash、构建后 tag、workflow 测试门禁。

### Root unit: 代理与模块边界

- F-01、F-05、F-18。
- 所有权：Proxy Activity/Design/Adapter/View、`design`/`service` 模型和 Store 边界、相关 Gradle 与测试。
- 验证：节流/增量/动画策略、accessibility node/state、`design` 模块依赖收紧。

## Wave 2 — Integration review

- 合并共享资源冲突，检查所有 18 项 traceability。
- 检查 API/包名变化对 Data Binding、AIDL、Parcelable、Manifest 的引用完整性。
- 检查新增 workflow 只在 GitHub 执行实际 Gradle/test。

## Wave 3 — Verification

- 允许：`rg`、PowerShell XML/YAML/JSON 解析、Python 静态脚本、Git diff/status、报告 lint。
- 禁止：本地 `gradlew`、Flutter、Android build/test、benchmark。
- GitHub workflow 必须承担：unit test、Android Lint/build、必要的 instrumentation/benchmark（可按独立 job 配置）。

## Rollback rules

- 每个 finding 采用局部提交前 diff，可按文件回退。
- 不执行 `git reset --hard`、不覆盖用户改动、不强推。
- 若模块边界迁移导致无法静态确认引用完整性，优先缩小为接口/DTO 边界，不进行大规模重写。

## Completion language

只有静态验收全部通过、18 项有落点、工作区无意外文件、commit/push 成功后，才允许声明“实现完成并已推送”；同时必须明确“本地未运行项目构建/测试，实际结果由 GitHub workflow 验证”。

## Phase cleanup

- 删除下载 Geo 资产产生的临时文件。
- 审计 child agent 状态，无残留运行任务。
- 生成 `outputs/runtime/vibe-sessions/20260710-clash-full-remediation/cleanup-receipt.json` 与 delivery acceptance 说明。
