## Clash Meta for Android

A Graphical user interface of [Clash.Meta](https://github.com/MetaCubeX/Clash.Meta) for Android

> **Chloemlla fork (`Chloemlla/ClashMetaForAndroid`)**  
> Tracks community CMFA, then lands **hardening · UX · Alpha→Meta migration · CI/CD · i18n** on this fork’s `main`.  
> **This is not a thin mirror** — the bulk of production-facing Android work is maintained here.  
> Full inventory: **[Branch Improvements / 本分支改进](#branch-improvements-本分支改进)** · recent work as **[Feature Tracks](#feature-tracks-近期功能分支)**.

### Feature

Feature of [Clash.Meta](https://github.com/MetaCubeX/Clash.Meta)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.github.metacubex.clash.meta/)

### Requirement

- Android 5.0+ (minimum)
- Android 7.0+ (recommend)
- `armeabi-v7a` , `arm64-v8a`, `x86` or `x86_64` Architecture

### Build

1. Update submodules

   ```bash
   git submodule update --init --recursive
   ```

2. Install **OpenJDK 21**, **Android SDK**, **CMake** and **Golang**

3. Create `local.properties` in project root with

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

4. (Optional) Custom app package name. Add the following configuration to `local.properties`.

   ```properties
   # config your ownn applicationId, or it will be 'com.github.metacubex.clash'
   custom.application.id=com.my.compile.clash
   # remove application id suffix, or the applicaion id will be 'com.github.metacubex.clash.alpha'
   remove.suffix=true
   ```

5. Create `signing.properties` in project root with

   ```properties
   keystore.file=/absolute/path/to/keystore/file
   keystore.password=<key store password>
   key.alias=<key alias>
   key.password=<key password>
   ```

   Release builds **fail fast** if signing configuration is missing (no silent fallback to debug signing).

6. Build

   ```bash
   ./gradlew app:assembleAlphaRelease
   ```

> Local device builds are optional for contributors; this fork treats **GitHub Actions** as the authoritative build, test, and lint gate.

### Automation

- VPN start, stop, and toggle actions are **app-private**. Dynamic launcher shortcuts remain available, but third-party apps cannot invoke them.
- Import a profile
  - URL Scheme `clash://install-config?url=<encoded URI>` or `clashmeta://install-config?url=<encoded URI>`

### Contribution and Project Maintenance

#### Meta Kernel

- CMFA uses the kernel from `android-real` branch under `MetaCubeX/Clash.Meta`, which is a merge of the main `Alpha` branch and `android-open`.
  - If you want to contribute to the kernel, make PRs to `Alpha` branch of the Meta kernel repository.
  - If you want to contribute Android-specific patches to the kernel, make PRs to  `android-open` branch of the Meta kernel repository.

#### Maintenance

- When `MetaCubeX/Clash.Meta` kernel is updated to a new version, the `Update Dependencies` actions in this repo will be triggered automatically.
  - It will pull the new version of the meta kernel, update all the golang dependencies, and create a PR without manual intervention.
  - If there is any compile error in PR, you need to fix it before merging. Alternatively, you may merge the PR directly.
- Pushing to `main` on the Chloemlla fork triggers **Build Release on Push**: after shared verify, it **parallel-builds** signed **Meta** (`app:assembleMetaRelease`) and **Alpha** (`app:assembleAlphaRelease`). Meta is published as a **full latest** release (`prerelease: false`, `make_latest: true`, tag `v{version}-{shortsha}`); Alpha is published as a separate **pre-release** (`prerelease: true`, `make_latest: false`, tag `v{version}-{shortsha}-alpha`). Manual `Build Release` remains for version-bumped `vX.Y.Z` tags.
- Manually triggering `Build Release` actions will compile, tag and publish a `Release` version.
  - You must fill the blank `Release Tag` with the tag you want to release in the format of `v1.2.3`.
  - `versionName` and `versionCode` in `build.gradle.kts` will be automatically bumped to the tag you filled above.
  - Version bump / tag push happens **after** successful build and verification (not before).

### Related documents

| Document | Purpose |
|----------|---------|
| [`SECURITY.md`](SECURITY.md) | Signing contract, historical keystore exposure note, maintainer rotation duty |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Local data handling, backup scope, logs |
| [`audit-report-ClashMetaForAndroid-2026-07-10.md`](audit-report-ClashMetaForAndroid-2026-07-10.md) | Full engineering audit (F-01 … F-18) |
| [`docs/requirements/`](docs/requirements/) | Frozen requirements for progressive delay UI, warning cleanup, audit remediation |
| [`docs/plans/`](docs/plans/) | Execution plans for the same workstreams |

---

## Branch Improvements (本分支改进)

### Why Chloemlla?

上游 CMFA 提供 Clash.Meta Android 图形壳；**Chloemlla 分支在此之上系统改造生产可用性**，而不是仅同步内核：

| 维度 | Chloemlla 强化 |
|------|----------------|
| **安全** | 外部 VPN 控制默认拒绝 · 签名 fail-fast · keystore 出库 · 备份收紧为 sharedpref |
| **稳定** | 全量审计 F-01~F-18 · 全局协程异常隔离 · 日志有界/轮转 · Access Control 退出超时 |
| **体验** | 渐进测速动画 · 代理搜索 · 首页首启引导 · 剪贴板导入 · 空状态 CTA · 当前节点定位 |
| **迁移** | **Alpha → Meta 同签名自动导入**配置/节点/设置（Meta 正式包首次启动） |
| **发布** | main 推送并行 **Meta latest + Alpha pre-release** · SHA256SUMS · 构建成功后再打 tag |
| **质量** | JVM 单测 · Lint 全量报告 · 失败堆栈透明 · 仓库策略脚本 |
| **i18n** | zh / zh-TW / zh-HK / ja / ko / ru / vi 等社区语言补全 |

当前版本基线约为 **2.11.32**。下列改进均已落在源码/工作流中；**构建、单元测试与 Lint 以 GitHub Actions 为唯一权威执行环境**（本机不跑 Gradle/Flutter 作为门禁）。

### Feature Tracks（近期功能分支）

以下把 **2026-07 近期提交** 按功能线归组（便于对照 PR / cherry-pick / 回归范围）。短哈希可在仓库 History 中定位。

#### Track A · 全量审计修复
| Commit | Summary |
|--------|---------|
| `bc9a35a` | `fix: remediate full audit findings`（F-01~F-18 主修复） |
| `1059807` | `fix: close residual audit F-06 and F-13 gaps` |
| `d198d58` | 审计报告 `audit-report-ClashMetaForAndroid-2026-07-10.md` |

#### Track B · 代理测速 / 日常 UX
| Commit | Summary |
|--------|---------|
| `f301f6f` | 渐进式代理延迟刷新 + 动画 |
| `bc058e5` | `feat(ux): proxy search, empty profiles CTA, and selected-node focus` |
| `9323962` | `feat(ux): home setup CTA, clipboard import, start feedback, logs empty` |

#### Track C · Alpha → Meta 数据迁移
| Commit | Summary |
|--------|---------|
| `a25fad8` | `feat: auto-migrate profiles from Alpha to Meta release` |
| `ea32a28` | `i18n: localize common migration permission strings` |
| `fa677ef` / `0e209c3` / `fbd0a24` | Lint / AGP 8 / migration provider 诊断加固 |
| `970132d` | 补齐 Alpha migration 的 coroutines `launch` import |
| `49d3131` | 迁移 zip 解压避免 API 26 `Path` API |

#### Track D · CI / CD 与发布
| Commit | Summary |
|--------|---------|
| `22a420b` | main 推送发布 Meta full latest |
| `81e769b` | 并行 Meta latest + Alpha pre-release |
| `83fe497` / `d4fff9c` | 统一 / 更新 release 签名 secrets |
| `c3ba6ae` | 去掉对缺失 `SIGNING_CERT_SHA256` 的依赖 |
| `1135e04` / `01aedc7` / `884bc8a` | 单测失败全量暴露 · Gradle 隐藏失败注解 · Lint 报告上传 |

#### Track E · Lint / 构建卫生 / i18n
| Commit | Summary |
|--------|---------|
| `923b25c` / `daf03af` / `32181ac` / `7760c4f` / `0b03a13` / `0022b12` | sideload 残留清理 · Room RestrictedApi · Geo 任务依赖 · MissingTranslation · 返回键现代化 · 清 lint |
| `98e1e11` | `i18n: complete missing community translations` |
| `7762df4` / `72eb015` | Android 构建告警 / kaidl Serializable 修补 |

#### Track F · 运行时韧性（本 PR 含）
| Commit | Summary |
|--------|---------|
| `cb64fce` | **Global 协程异常隔离**：应用级 CoroutineExceptionHandler、关键网络配置收紧、迁移/Profile/配置模块异常边界、2026-07-14 审计报告与仓库策略补强 |

> 详细机制仍见下文分节；本表只做 **功能分支式导航**。

### 1. 全量审计修复（F-01 ~ F-18）

审计报告见 `audit-report-ClashMetaForAndroid-2026-07-10.md`。主修复提交：`bc9a35a`（`fix: remediate full audit findings`），后续补漏：`1059807` 等。

#### 1.1 性能与稳定性

| ID | 问题 | 本分支改进 |
|----|------|------------|
| **F-01** | 代理测速以约 100ms 全量查询 + Diff + 移动动画，易掉帧、发热 | 测速路径改为节流/增量友好的刷新策略；配合渐进式延迟结果与动画（见下文 §2），避免高频整组移动 Diff 与动画重叠 |
| **F-02** | 流式日志 RecyclerView 插入区间与数据集不一致，可崩溃 | 修正 `LogcatCache` / `LogcatDesign` 增量通知语义；新增 `LogcatCacheTest` 覆盖空→N、追加、满容量淘汰等序列 |
| **F-03** | 打开本地日志在主线程整文件解析且无界保留 | 引入/强化 `LogcatParser`、`LogcatReader`：后台 I/O、有界读取、容错解析；配套 `LogcatParserTest` |
| **F-04** | 日志无轮转、无大小上限 | `LogcatWriter` 增加配额/轮转与停止策略，避免 cache 无限增长 |
| **F-09** | 访问控制一次性加载并长期持有全部应用图标 | `AppAdapter` / `AppInfo` 改为图标懒加载与缓存生命周期管理，降低内存常驻 |

#### 1.2 UI / UX / 无障碍

| ID | 问题 | 本分支改进 |
|----|------|------------|
| **F-05** | 自绘代理卡片缺少无障碍名称、状态与完整文本 | `ProxyView` / `ProxyViewState` / `ProxyAdapter` 暴露可访问语义（名称、延迟、选中态等） |
| **F-06** | 大量图标按钮仅约 30dp；“更新全部” contentDescription 错误 | 核心列表/工具栏图标触控目标提升到 **48dp**；修正无障碍文案（后续 `1059807` 补齐残留行内操作） |
| **F-07** | 删除配置无确认，直接递归删库删文件 | 配置删除增加确认流程，降低误删风险 |
| **F-08** | “更新全部”可重复点击，请求无上限排队 | 更新入口 single-flight：进行中禁用/忽略重复提交 |
| **F-10** | 退出 Access Control 等待 VPN 停止无超时 | 增加超时与可退出路径，避免页面永久卡住 |
| **F-11** | 首屏无说明请求通知权限，拒绝结果被忽略 | `MainActivity` 改进权限说明与拒绝后的恢复/不再骚扰路径 |

#### 1.3 安全与隐私边界

| ID | 问题 | 本分支改进 |
|----|------|------------|
| **F-12** | 任意已安装应用可通过 exported Activity 启停/切换 VPN | 外部控制改为 **默认拒绝**；应用内快捷方式走 `InternalControlActivity` 等私有路径，第三方无法再调用 START/STOP/TOGGLE |
| **F-13** | `release.keystore` 曾被仓库跟踪；缺签名时静默用 debug 签 | 从当前树移除 keystore；**缺失签名配置 fail-fast**；`SECURITY.md` 记录历史暴露与轮换责任；仓库策略脚本禁止再次纳入 `release.keystore` |
| **F-17** | 自动备份可能包含订阅源、配置与 `ageSecretKey` | 备份/提取规则收紧为 **sharedpref only**；`PRIVACY_POLICY.md` 明确备份范围与旧版备份残留说明 |

#### 1.4 供应链、发布与架构

| ID | 问题 | 本分支改进 |
|----|------|------------|
| **F-14** | 无测试、CI 仅 assemble | 增加 JVM 单元测试与 CI 测试/静态门禁；失败日志完整暴露（见 §4） |
| **F-15** | 每次 assemble 从可变 `latest` URL 拉 Geo 资产且不校验 | Geo 资产固定版本并做摘要校验，降低供应链篡改面 |
| **F-16** | 正式发布在构建验证前改 main / 建 tag / 推送 | Release 工作流改为 **构建与校验成功后再** 处理版本元数据与 tag |
| **F-18** | `design` 直接依赖 `service` 存储模型与具体 Store | 引入 `design` 侧模型与适配边界（如 `ServiceSettings`、`Profile` 展示模型、`ServiceSettingsAdapter`），收紧 UI→存储依赖方向 |

### 2. 代理测速体验：渐进式延迟结果

提交：`f301f6f`（`Implement progressive proxy delay updates with animations`）。

- 测速过程中按固定节奏刷新代理组延迟，而不是等整组测完才一次性更新。
- `ProxyDesign` / `ProxyPageAdapter` 使用定向 Diff + payload 更新延迟文本。
- `ProxyView` / `ProxyViewState` 对延迟变化做淡入/上浮动画，并配合 `postInvalidateOnAnimation`。
- 关闭默认 change 动画冲突，控制 move 时长，减少列表抖动。
- 需求与计划文档：`docs/requirements/2026-07-10-proxy-delay-progressive-results.md`、`docs/plans/2026-07-10-proxy-delay-progressive-results-execution-plan.md`。


### 2.1 日常使用体验增强

提交：`feat(ux): home setup CTA, clipboard import, start feedback, logs empty state`。

- **首页首启引导**：无激活配置时展示「添加配置」卡片，直达新建配置页。
- **启动中反馈**：点击启动后主卡显示 Starting…，避免重复点按；服务状态广播后恢复。
- **剪贴板导入订阅**：新建配置支持从剪贴板识别 `http(s)` 与 `clash(meta)://install-config?url=` 链接。
- **日志空状态**：无历史日志时展示说明与启动 logcat 的 CTA。
- **未选配置引导**：启动失败 Toast 的操作改为直接创建配置。
### 2.2 全局协程异常隔离与运行时韧性

提交：`cb64fce`（`Global 协程异常隔离`）；续审计见 `audit-report-ClashMetaForAndroid-2026-07-14.md`。

- **Global / MainApplication**：统一 CoroutineExceptionHandler，避免未捕获协程异常直接拖垮进程。
- **Profile / Configuration / Migration**：关键异步路径增加隔离与失败可观测，降低迁移与配置加载的雪崩面。
- **网络安全配置**：收紧 `network_security_config` 默认策略。
- **仓库策略**：`.github/scripts/verify-repository-policy.py` 与 workflow 钩子继续防止危险文件回流入库。

### 2.3 Alpha → Meta 自动迁移（功能线）

主提交：`a25fad8`；加固：`ea32a28`、`fa677ef`、`0e209c3`、`fbd0a24`、`970132d`、`49d3131`。

- Meta 正式包 **首次启动且本地无配置** 时，经同签名 `MigrationProvider` 从已安装 Alpha 导入配置、节点选择与 service/ui 设置。
- 权限文案多语言；zip 解压兼容低 API（避免 `java.nio.file.Path` on API&lt;26）；Lint/CI 诊断与 `BaseExtension.lintOptions` 恢复，保证门禁可开。

### 3. Android 构建告警清理

提交：`7762df4`、`72eb015` 等。

- 修补 **kaidl** 生成路径，避免弃用的无类型 `Parcel.readSerializable()` 警告。
- 前台服务停止路径改为非弃用 API。
- 为原先仅存在于部分 locale 的字符串补全 **default（英文）** 资源，消除 missing default 类告警。
- 需求文档：`docs/requirements/2026-07-10-android-build-warning-cleanup.md`。

### 4. CI / CD 与发布链路加固

相关提交包括：`c76ede2`、`d4fff9c`、`83fe497`、`01aedc7`、`1135e04`、`884bc8a`、`c3ba6ae` 等。

- **main 推送并行发布**：`build-pre-release.yaml` 对 Chloemlla fork 先跑共享校验，再并行 `assembleMetaRelease`（正式 latest）与 `assembleAlphaRelease`（Pre-release，不抢 latest）。
- **Alpha → Meta 数据迁移**：正式 Meta 包首次启动且本地无配置时，通过同签名 `MigrationProvider` 自动从已安装的 Alpha 导入配置文件、节点选择与 service/ui 设置（需两包使用同一签名）。
- **统一签名密钥契约**：main 推送正式包 / 手动 Release 均通过 Secrets（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）注入；在 `$RUNNER_TEMP` 解码 keystore 并生成临时 `signing.properties`，`always()` 清理。
- **产物完整性**：发布 APK 生成 `SHA256SUMS`；移除对缺失的 `SIGNING_CERT_SHA256` 校验依赖，避免因未配置 secret 阻断流水线。
- **版本元数据**：工作流正确写入 release metadata 与 version 变量；正式发布顺序与审计 F-16 对齐。
- **测试可见性**：单元测试失败输出完整堆栈；Gradle 隐藏失败被注解到 Job 日志。
- **Lint 可见性**：打印并上传全部 `lint-results*` 报告，而不仅是 Gradle 首条摘要。
- **仓库策略**：`.github/scripts/verify-repository-policy.py` 等脚本防止再次跟踪危险文件（如 `release.keystore`）。
- **JUnit 依赖范围**：`d9d7284` 将 junit 限定到需要的模块，避免污染发布 classpath。

### 5. Lint 工程化与代码卫生

提交：`923b25c`、`daf03af`、`32181ac`、`7760c4f`、`0b03a13`、`0022b12` 等。

- 删除已废弃的 sideload GEOIP 残留布局，修复 data binding / kapt 断裂。
- 共享 `lint.xml`：抑制 Room KSP 生成代码的 `RestrictedApi` 误报，保证质量门禁可开。
- 为 `downloadGeoFiles` 声明对 lint/asset 消费任务的依赖，适配 Gradle 8 模型生成。
- 对不完整社区翻译启用可控的 `MissingTranslation` 策略（门禁保留，不强制 100% 覆盖）。
- 现代化返回键：`OnBackPressedDispatcher` 替代弃用 `onBackPressed`。
- 包信息查询适配 API 33+；`DialerReceiver` 校验 `SECRET_CODE` action。
- Backup 规则与 sharedpref-only 策略对齐 FullBackupContent / data extraction rules。
- 清理依赖与图标相关 lint 噪音，使 `lintAlphaDebug` / `lintAlphaRelease` 可稳定作为 CI 门禁。

### 6. 国际化（i18n）补全

提交：`98e1e11`（`i18n: complete missing community translations`）。

- 补全 **zh / zh-TW / zh-HK / ja / ko / ru / vi** 等社区语言中缺失的 default 字符串。
- 对不可翻译的 URL、标识符等做统一拷贝/标记。
- 清理过时的越南语多余条目，降低维护噪音。

### 7. 上游功能同步（合并进本分支的近期能力）

本分支在改造同时持续合并上游能力（非本 fork 独有，但已包含在当前 `main`）：

- 订阅信息拉取下沉到 Go（`a36b76c`）。
- 配置支持 **age secret keys**；设置页可生成 age 公私钥（`b829ee9`、`0de43ff`）。
- rule-providers 采用 `path-in-bundle`；附带 BundleMRS 资源。
- 允许忽略 `subscription-userinfo` 拉取错误；自定义更新间隔不被 HTTP header 覆盖。
- Android 多用户：`INTERACT_ACROSS_USERS` 权限声明（需 ADB 手动授予，默认不影响普通用户）。
- 依赖与内核周期性 `Update Dependencies` 自动化。

### 8. 版本与治理

- 版本推进至 **2.11.32 (211032)**（及先前 2.11.30 / 2.11.31 基线）。
- 引入需求冻结与执行计划文档（`docs/requirements/*`、`docs/plans/*`），保证审计修复可追溯。
- 贡献约定见仓库 `AGENTS.md`：实际构建/测试在 GitHub workflow 执行；本地以改代码与静态检查为主。

### 9. 改进一览（按主题）

```text
安全        外部 VPN 控制默认拒绝 · 签名 fail-fast · keystore 出库 · 备份收紧 · 网络安全配置
稳定        日志增量通知修复 · 有界日志解析 · 写盘轮转 · Access Control 退出超时 · 全局协程异常隔离
性能        测速节流/渐进延迟 · 应用图标懒加载 · 日志 I/O 离主线程
体验        48dp 触控 · 删除确认 · 更新 single-flight · 通知权限说明 · 代理无障碍 · 代理搜索 · 配置空状态 · 自动定位当前节点 · 首页首启引导 · 启动中反馈 · 剪贴板导入订阅 · 日志空状态
迁移        Alpha → Meta 同签名自动导入配置/节点/设置 · 低 API zip 解压 · 迁移权限 i18n
架构        design/service 边界收紧 · 展示层模型与适配器
供应链      Geo 固定校验 · 构建后打 tag · SHA256SUMS · 统一 CI 签名 secrets · Meta latest + Alpha pre-release 并行
质量        单元测试 · Lint 全量报告 · 失败日志透明 · 仓库策略脚本
i18n        多语言缺失串补全 · 合理 MissingTranslation 策略
工程        JDK 21 · kaidl/弃用 API 清理 · 返回键现代化 · Gradle 8 任务依赖
```

### 10. 验证说明

- **不要**把本机 Gradle/Flutter 跑通当作发布门禁；本 fork 约定真实验证在 **GitHub Actions**（debug / main 推送 Meta latest + Alpha pre-release / 手动 versioned release 工作流）。
- 推送后请在 Actions 中查看：assemble、unit test、lint 报告产物、签名与 `SHA256SUMS`。
- 若需对照审计条目与代码落点，优先阅读审计报告 §4 与 `docs/plans/2026-07-10-full-audit-remediation-execution-plan.md`。

---

### License / Upstream

This project remains a GUI client for Clash.Meta on Android. Kernel and protocol features follow MetaCubeX Clash.Meta; **Android-side hardening, UX, Alpha→Meta migration, CI/CD, and i18n above are maintained on the Chloemlla fork’s `main` branch** — see Feature Tracks for the recent commit map.

