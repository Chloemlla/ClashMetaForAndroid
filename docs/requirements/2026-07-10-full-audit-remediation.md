# ClashMetaForAndroid 全量审计修复需求

## Goal

实现 `audit-report-ClashMetaForAndroid-2026-07-10.md` 中 F-01 至 F-18 的仓库内可实施修复，使核心用户流程、性能、无障碍、安全、备份和发布链路达到可进入 GitHub workflow 验证的状态。

## Deliverables

- 修复代理测速、日志查看/记录、应用列表的性能和稳定性问题。
- 修复触控目标、代理卡片语义、删除确认、重复提交、退出等待和通知权限 UX。
- 为外部 VPN 控制建立默认拒绝或显式授权边界，同时保留应用内快捷操作。
- 移除当前树中的 release keystore，release 签名缺失时 fail-fast。
- 固定并校验 Geo 构建资产，调整正式发布顺序，增加 GitHub workflow 测试/静态门禁。
- 收紧 `design` 对 `service` 具体实现的依赖。
- 添加与修复相匹配的测试代码；测试只在 GitHub workflow 执行。
- 更新隐私/开发文档和治理凭证。

## Constraints

- 不在本机运行 Flutter、Gradle、Android build、项目 test、benchmark 或 instrumentation。
- 本地只允许静态搜索、文本/脚本语法检查、Git 检查和非项目构建型校验。
- 不覆盖或丢弃用户已有改动；当前基线为 `main`。
- 不重写 Git 历史、不强推；生产签名密钥轮换属于仓库外运维动作，但仓库必须停止继续跟踪/依赖该文件。
- 所有源文件编辑使用 `apply_patch`；二进制 keystore 删除若无法由 patch 表达，可使用受控单文件删除。

## Acceptance Criteria

1. F-01 至 F-18 每项都有代码、配置、测试或文档落点；无法在仓库内完成的外部动作必须有明确阻断说明和自动防复发保护。
2. 代理测速不再 100ms 触发全量移动 Diff 与重叠动画。
3. Logcat 增量通知合法；历史日志读取离开主线程、有界且容错；日志文件有配额/轮转。
4. 代理卡片可访问，核心图标 touch target 至少 48dp，破坏性操作需确认，异步操作不可重复排队。
5. 外部控制默认不可被任意应用调用；应用内快捷方式仍可用。
6. Release 签名缺失明确失败；仓库不再跟踪 keystore；Geo 资产固定版本和摘要。
7. 正式 release 在构建/验证成功后才创建或推送版本/tag。
8. GitHub workflow 包含测试和静态验证步骤；新增测试文件覆盖主要纯逻辑回归。
9. `design` 不再直接依赖 `service` 模块，或至少所有具体 Store 访问被移出展示层且模块依赖方向得到实质收紧。
10. 最终仅通过静态验证声明“代码已实现、待 GitHub workflow 实际构建测试”，提交并推送。

## Non-goals

- 不整体重写 UI 或迁移到 Compose。
- 不重写 Git 历史或执行生产密钥轮换。
- 不逐行修改上游 mihomo 子模块。
- 不在本地执行任何被仓库禁止的构建/测试命令。

## Autonomy

实现导向，允许在不改变产品核心定位的前提下采用最小、局部、可回滚的修复；并行 agent 必须遵守文件所有权边界，不创建第二需求或计划表面。
