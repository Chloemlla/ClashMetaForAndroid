# Runtime / Service SDK（方向 B）需求

## Goal

在 Chloemlla fork 上提供 **可嵌入的 Runtime / Service SDK**，使宿主 App 在 **同进程 / 同签名 / 同 applicationId 边界内** 复用 CMFA 的 Profile 管理、Clash 控制与 VPN/非 VPN 运行时，而无需依赖完整 `app` UI 壳。

## Scope

### In scope（P0）

- 新增 `:sdk` Android Library 模块，作为对外门面。
- 对宿主暴露稳定 API：
  - `ClashRuntime.install` / `bind` / `unbind`
  - VPN / 非 VPN 启动与停止
  - Profile 创建 / 导入 URL / 激活 / 列表
  - 代理组查询、选节点、测速
  - 运行状态与配置变更事件观察
- 解除 service 对硬编码 `MainActivity` / `PropertiesActivity` 的耦合：宿主可配置通知与 VPN 配置页的回跳 `ComponentName`。
- 文档：嵌入步骤、权限、进程模型、安全边界、非目标。
- 需求/计划可追溯；构建与测试仍只在 GitHub Actions 执行。

### Out of scope（Non-goals）

- 跨 App 遥控第三方启停 VPN（与审计 F-12 默认拒绝冲突）。
- Flutter / React Native 绑定（后续可选）。
- 发布到 Maven Central（后续可选；本阶段以模块 + 文档为主）。
- 把 `design` UI 组件打成 SDK。
- 重写 Go 内核或更换 mihomo API。
- 本机 Gradle/Flutter 构建验证。

## Deliverables

1. `docs/requirements/2026-07-15-runtime-service-sdk.md`（本文）
2. `docs/plans/2026-07-15-runtime-service-sdk-execution-plan.md`
3. `common`：`Components` 可配置
4. `:sdk` 模块 + public API + consumer rules
5. `docs/sdk/runtime-embed.md` 嵌入指南
6. README Feature Track 增补（可选同 PR）

## Constraints

- 不在本机运行 Flutter、Gradle、Android build 或项目 test。
- 不破坏现有 `app` 默认行为：未调用 `Components.configure` 时保持 CMFA 原组件名。
- SDK 默认 **in-app embed only**；不新增 exported 遥控入口。
- 不引入新的 super 文件；不写无关重构。
- 提交后推送并开 PR 到 `main`。

## Acceptance Criteria

1. 宿主可仅依赖 `:sdk`（传递依赖 `service`/`core`/`common`）完成：初始化 → 导入 URL 配置 → 授权 VPN → 启动 → 查询代理组 → 停止。
2. 通知点击 / VPN 配置 Intent 可通过 `Components.configure` 指向宿主 Activity。
3. 未配置时 CMFA 自身仍指向 `com.github.kr328.clash.MainActivity` / `PropertiesActivity`。
4. 文档明确：geo 资产、进程 `:background`、权限、签名/applicationId、与外部控制安全策略的关系。
5. 静态结构完整（模块 include、源码、文档）；真实验证留给 GitHub workflow。

## Security boundary

| 允许 | 禁止（默认） |
|------|----------------|
| 同 App 内嵌 SDK 控制本应用 VPN | 任意第三方 App 启停本应用 VPN |
| 同签名 companion（若宿主自行扩展） | 无授权 exported START/STOP/TOGGLE |
| 用户明确授权的 VPN 准备页 | 静默绕过 `VpnService.prepare` |

## Open points（后续）

- Sample host App 模块
- GitHub Packages 发布坐标
- 将 `app` 内部 `withClash`/`Remote` 迁到 `:sdk` 去重