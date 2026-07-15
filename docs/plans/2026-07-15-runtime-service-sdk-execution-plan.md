# Runtime / Service SDK 执行计划

## Phase 0 — 冻结

- [x] 需求文档
- [x] 本执行计划

## Phase 1 — Host hooks

- [x] `Components` 改为可配置 + 安全默认值
- [ ] （可选后续）通知文案/图标宿主覆盖

## Phase 2 — `:sdk` 门面

- [x] 模块脚手架（gradle include、consumer rules）
- [x] `ClashRuntime` 安装与绑定
- [x] VPN 启停 API（不依赖 `design.UiStore`）
- [x] Profile / Proxy 高层 API
- [x] 事件观察（started/stopped/profile）

## Phase 3 — 文档

- [x] `docs/sdk/runtime-embed.md`
- [x] README Feature Track G

## Phase 4 — 交付

- [ ] 提交、推送、`feat/runtime-service-sdk` → `main` PR
- [ ] GHA 验证（远端）

## Risks

1. Geo 资产仍在 `app` assets：宿主需自行打包/解压 — 文档标明。
2. `packageName` 常量依赖 `Global.init`：install 顺序必须强制。
3. 多进程 `:background`：bind 仅在 UI 进程调用。
4. kaidl/Binder 生成物对混淆敏感：consumer rules 保留。