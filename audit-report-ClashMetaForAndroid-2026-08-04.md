# Fuck My Shit Mountain 审计报告

**项目:** ClashMetaForAndroid
**审计模式:** full（全维度，并行 agents 分模块扫描）
**日期:** 2026-08-04
**审计者:** Claude + 并行审计 agents

- Updated: 2026-08-05（完成）
- Scope and exclusions: 全库 718 个 tracked 文件（app / core / service / design / common / sdk / hideapi / kaidl-compiler-patch / build / CI / tests / manifests / res）。排除：`core/src/foss/golang/clash`（mihomo 第三方子模块）、`.git`、`build` 产物、`tmp/`、`tame-legacy-codebase/`（工具本身）、设备固件与生成产物。
- Environment and limitations: 本机禁止构建/测试（AGENTS.md：构建只能在 GitHub Actions 执行）→ 静态只读审计，无动态运行/单测执行证据。后端并发限制导致 8 个并行 agent 分批（3/3/2）执行。
- Mode: 系统
- Decisions: **E0 / G0 / Q0 / C1 / D0**

## Before assessment (frozen before first edit)

Security        ███████░░░  7.0   -   Confirmed
Stability       █████░░░░░  5.5   -   Confirmed
Performance     ███████░░░  7.0   -   Confirmed
Testing         █████░░░░░  5.0   -   Confirmed
Maintainability ███████░░░  7.0   -   Confirmed
Design          ██████░░░░  6.5   -   Confirmed
Release         █████░░░░░  5.5   -   Confirmed
─────────────────────────────────────
Overall         ██████░░░░  6.2   -   Confirmed

| Dimension | Confidence | Scope/evidence |
| --- | --- | --- |
| Security | High | 187 发现覆盖全模块，密钥管理、跨进程边界、intent 注入、深链攻击面均审查 |
| Stability | High | 主要崩溃路径（空 IN()、Alpha 迁移、锁竞争、ProfileWorker 生命周期）已定位 |
| Performance | Medium | 主线程 Binder 调用、group 列表无节流、无界通道已标记；无 Trace 验证 |
| Testing | High | 全部 28 个测试文件、115 个 @Test 已审查，覆盖矩阵已穷举 |
| Maintainability | High | 模块边界、死代码、i18n、硬编码、注释漂移已覆盖全部模块 |
| Design | High | 128 个 design 文件全部审查，偏好 DSL 体系、组件生命周期、多线程正确性已评估 |
| Release | Medium | CI 工作流、签名策略、依赖管理已审查；未实际运行构建验证 |

## After assessment

E0（只报告）—— 无 After。

## Finding summary

| Severity | Count | Confirmed | Suspected | Withdrawn |
| --- | ---: | ---: | ---: | ---: |
| Critical | 0 | 0 | 0 | 0 |
| High | 8 | 7 | 1 | 0 |
| Medium | 43 | 33 | 10 | 0 |
| Low | 70 | 53 | 17 | 0 |
| Info | 66 | 59 | 6 | 1 |
| **Total** | 187 | 152 | 34 | 1 |

### 逐模块分布

| 模块 | 文件数 | 源码行 | High | Medium | Low | Info | 总分 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| core | 68 | 5,195 | 2 | 6 | 8 | 11 | 27 |
| service | 106 | 6,718 | 1 | 6 | 8 | 13 | 28 |
| design | 128 | 12,815 | 1 | 4 | 10 | 9 | 25 |
| app | 81 | ~5,000 | 0 | 3 | 15 | 7 | 25 |
| sdk+common+hideapi+kaidl | ~60 | ~3,500 | 0 | 8 | 13 | 9 | 30 |
| build+CI+docs | ~30 | ~2,000 | 1 | 7 | 9 | 5 | 22 |
| tests | 28 | 1,718 | 3 | 7 | 3 | 5 | 18 |
| manifests+res | ~50 | ~4,000 | 0 | 2 | 6 | 5 | 13 |
| **总计** | **~551** | **~41,000** | **8** | **43** | **70** | **66** | **187** |

## Executive summary

本次审计覆盖 ClashMetaForAndroid 全库 718 个 tracked 文件（58,264 物理行），通过 8 个并行 agent 分模块审查，共发现 187 项问题。

**关键发现：**

1. **生产签名密钥在 PR 工作流中物化** (F-rel-01, High) — 同仓库 PR 的 runner 上可读取生产 keystore 并明文落盘，违反 GitHub 安全准则。建议将签名步骤门控到 push/workflow_dispatch 事件。

2. **Clash/Go JNI 边界零测试** (F-test-03, High) — 34 个 `//export` 函数的序列化契约无 Go 单测或 Kotlin 回归保护。core 模块全部 ~30 Kotlin 文件 + 38 Go 文件零测试覆盖。

3. **Alpha 迁移引擎三件套零测试** (F-test-01, High) — AlphaDataMigrator、MigrationBundle、MigrationProvider 无测试，07-14 审计发现的 F-01/F-06 恰落于此路径，无回归保护。

4. **配置生命周期零测试** (F-test-02, High) — ProfileProcessor/ProfileWorker/ProfileManager 无测试，配置写入原子性与调度可靠性无回归保护。

5. **SensitiveFieldMask.maskUrl 泄露订阅 token** (F-design-01, High) — 对无路径的 query-only URL，掩码逻辑失效，secret 全量显示在 UI 上。

6. **ConfigurationModule 空 selection 集合 IN ()** (F-svc-01, High, Suspected) — Room 对空 List 生成 `proxy IN ()`（SQLite 语法错误），可能令每次启动加载 profile 异常。

7. **Design 基类作用域永不取消** (F-design-02, Medium) — CoroutineScope 不绑生命周期，页面退出后协程/视图泄漏，长期导航内存无界增长。

8. **CI 依赖链脆弱** (F-rel-04/05/06, Medium) — 依赖解析依赖浮动 GitHub 分支 Maven 仓、lumen-crash 版本动态解析"最新"发布、依赖目录严重陈旧（Room 2.4.2 来自 2022）。

**与 07-14 审计对比：** 7 项已确认修复（ProfileWorker 锁、配置目录原子替换、Global SupervisorJob、extractGeoFiles 主线程、wrapper SHA、CA 信任等），多处 STILL PRESENT（Alpha 迁移异常处理、MigrationBundle 超时、枚举序数不稳定性）。

**整体评分 6.2/10（B），** 较上次 6.8 略有下降，主因本次覆盖深度更大（含 build/CI/tests/res 等前次未全面覆盖的模块），发现更多深层问题。

## Code size baseline

| Area/type | Files | Physical lines | Exclusions/notes |
| --- | ---: | ---: | --- |
| First-party production | 420 | 36697 | 首方生产源码（inventory 2026-08-04） |
| Tests | 28 | 1718 | app/common/design/service 的 test 源集 |
| Generated/vendored | 0 | 0 | 启发式判定 |
| All candidate source | 448 | 38415 | |

| Largest file/symbol | Physical lines | Role | Finding ID or rationale |
| --- | ---: | --- | --- |
| design/.../svg/drawablevectors/Coder.kt | 1451 | SVG 矢量路径数据 | 生成类数据，见设计 agent |
| core/src/main/cpp/main.c | 738 | JNI C 侧 | 见 core agent |
| core/src/main/java/.../Clash.kt | 334 | Go 核心 Kotlin 封装 | 见 core agent |

## Baseline checks

| Check | Command/evidence | Result | Baseline failure? |
| --- | --- | --- | --- |
| 构建/单测/静态检查 | AGENTS.md 禁止本地构建；CI-only | 未运行（策略限制） | N/A |
| LOC inventory | `python tame-legacy-codebase/scripts/inventory_codebase.py .` | 58264 物理行 / 38415 源码行 | — |

## Finding ledger

完整发现列表见各模块文件：

| 模块 | 发现文件 | 发现数 | 关键 High 项 |
| --- | --- | ---: | --- |
| core | `tmp/audit-2026-08-04/findings-core.md` | 27 | 2 (多线程竞态 + 原子性) |
| service | `tmp/audit-2026-08-04/findings-svc.md` | 28 | 1 (空 IN() 查询 Suspected) |
| design | `tmp/audit-2026-08-04/findings-design.md` | 25 | 1 (subscript token 泄露) |
| app | `tmp/audit-2026-08-04/findings-app.md` | 25 | 0 |
| sdk+common+hideapi+kaidl | `tmp/audit-2026-08-04/findings-sdk.md` | 30 | 0 |
| build+CI+docs | `tmp/audit-2026-08-04/findings-rel.md` | 22 | 1 (生产密钥 PR 泄露) |
| tests | `tmp/audit-2026-08-04/findings-test.md` | 18 | 3 (core/JNI/Alpha/配置零测试) |
| manifests+res | `tmp/audit-2026-08-04/findings-res.md` | 13 | 0 |

### 重要发现精选

**High:**
- F-rel-01: 生产签名密钥在 PR 工作流物化
- F-test-01: Alpha 迁移引擎零测试
- F-test-02: 配置生命周期零测试
- F-test-03: Clash/Go JNI 边界零测试
- F-design-01: SensitiveFieldMask.maskUrl 泄露订阅 token
- F-core-*: 多线程竞态 + 原子性缺失（详见 core 发现文件）
- F-svc-01: (Suspected) ConfigurationModule 空 selection 集合 IN ()

**Medium:**
- F-design-02: Design 基类作用域永不取消，页面泄漏
- F-svc-06: LocalSubscriptionTrafficStore 跨实例锁失效
- F-svc-07: FilesProvider 在 binder 线程无界 runBlocking
- F-app-03: withClash/withProfile 无限重试无超时
- F-app-04: RemoteService 崩溃后不自动重连
- F-sdk-01: withClash/withProfile 重试不覆盖代理获取阶段
- F-com-01: Intent.uuid getter 可抛异常致 :background 崩溃
- F-rel-04: 依赖解析依赖浮动 GitHub 分支 Maven 仓
- F-rel-07: release 工作流绕过审查直推 main
- F-res-01: ProfileReceiver intent-filter 是死配置
- F-res-02: design 模块 i18n 缺口 39%

**Low（精选）:**
- F-app-01: 深链 update-interval 参数无上限
- F-app-15: AuditReportImporter 键序敏感比较
- F-app-17: widget 刷新在主线程同步跨进程 Binder
- F-design-06: ActionTextField 禁用后值无法恢复
- F-design-14: LogFile.parseFromFileName 超大数字抛异常
- F-svc-09: replaceDirectoryAtomically 崩溃残留
- F-svc-10: LegacyMigration 非原子，中途异常删旧库
- F-com-04: SliceParcelableListBpBinder.onTransact 参数传错
- F-rel-08: 构建期 CA 覆盖子模块文件
- F-res-03: RestartReceiver 导出可被伪造触发

## Pending user decisions

以下 34 项 Suspected 发现需要用户裁决：

| # | 发现 | 模块 | 裁决问题 | 建议 |
| --- | --- | --- | --- | --- |
| 1 | F-svc-01 | service | Room 2.4.2 对空 List `IN ()` 是否抛 SQLiteException？ | 真机验证；若属实加 `remove.takeIf { it.isNotEmpty() }` |
| 2 | F-svc-02 | service | 旧版 Alpha 格式不匹配永久标记完成，Alpha 更新后也不迁移——是否应纳入重试上限？ | 改为非永久完成 |
| 3 | F-svc-03 | service | export_unavailable 无限重试——是否应计入 KEY_RETRIES 上限？ | 限 5 次或保留并加告警 |
| 4 | F-svc-04 | service | MigrationBundle.activeProfile 无条件覆盖本地——是否也仅当未设置时写入？ | 加守卫 |
| 5 | F-svc-05 | service | ProfileWorker 空队列即退出，迟到 job 被取消——是否改为常驻排空？ | 常驻 + startId 归零信号 |
| 6 | F-svc-14 | service | ClashManager 日志/连接观察者无背压——core 通道容量与丢弃策略？ | 确认后加背压 |
| 7 | F-svc-19 | service | SceneModule Session override 是否被 ConfigurationModule 重载清除？ | 确认 core 行为 |
| 8 | F-svc-24 | service | ClashRuntime.globalLock 无超时——前一个卡死时新服务无限等待？ | 拿锁加超时 |
| 9 | F-svc-28 | service | ProfileProcessor.fetchAndValid 无超时——异常网络下永不返回？ | 确认 core 内部超时或加 withTimeout |
| 10 | F-design-05 | design | patchDelays 跨调度器 TOCTOU——活动层是否保证串行调用？ | 确认单一主协程/互斥 |
| 11 | F-design-10 | design | ProxyViewConfig 单列模式误用 grid3 密集尺寸——有意设计？ | 确认或修复 |
| 12 | F-design-16 | design | 设置页 running/动态通知 enabled 状态只在构造时快照——Activity 重建是否覆盖？ | 确认或加刷新 |
| 13 | F-design-21 | design | "System Apps" 勾选语义与标签歧义——是否改为 "Hide System Apps"？ | 动词标签对齐 |
| 14 | F-design-22 | design | Landscape insets RTL/极端宽度下不对称——是否取 max 对称？ | 确认或修复 |
| 15 | F-design-25 | design | OverrideSettingsDesign secret 明文 vs Properties 掩码策略——是否统一？ | 确认策略 |
| 16 | F-app-02 | app | LogcatActivity.bindLogcatService 不可取消 suspendCoroutine——是否加超时？ | 加超时或回退 |
| 17 | F-app-05 | app | PropertiesActivity 切后台静默 patch + "放弃"语义误导——是否接受"后台自动保存"？ | 更新快照 + 隐藏"放弃"提示 |
| 18 | F-app-07 | app | FilesActivity 根目录 size==0 时只显示空文件——有意设计？ | 确认或修复 |
| 19 | F-app-10 | app | LogcatService.onDestroy 无条件 unbindService 可能抛异常——改用 unbindServiceSilent？ | 确认或替换 |
| 20 | F-app-14 | app | ExternalControlActivity MainScope 不随 Activity 取消——改用 lifecycleScope？ | 确认或修复 |
| 21 | F-app-18 | app | geo 文件导入 ins==null 时静默生成 0 字节——是否提示失败？ | 删输出文件 + 提示 |
| 22 | F-app-19 | app | AutomationSettingsAdapter schedule=Custom 静默 no-op——Custom 是否应隐藏？ | 确认或实现 |
| 23 | F-app-29 | app | MetaFeatureSettingsActivity ImportCountry 输出 country.* 无消费者——是否下线？ | 确认或调整 |
| 24 | F-sdk-02 | sdk | suspend API 无超时、无"未连接"错误路径——是否加超时/快速失败？ | 加超时或 requireNotNull |
| 25 | F-sdk-04 | sdk | events 无 replay + unbind 重置——迟订阅丢事件——是否加 replay=1？ | 加状态快照 |
| 26 | F-sdk-05 | sdk | DeadObjectException 重试对非幂等写可能重复——是否接受并文档声明？ | 文档声明语义 |
| 27 | F-sdk-07 | sdk | 重试循环无退避——是否加指数退避或限次？ | 确认或实现 |
| 28 | F-sdk-11 | sdk | Resource 持 monitor 期间 resume 协程——是否移到锁外通知？ | 拷贝 pending 后通知 |
| 29 | F-rel-03 | rel | Go 1.26 + 第三方发行源 + 补丁能否干净应用？ | 验证构建可复现 |
| 30 | F-rel-11 | rel | README mihomo 分支名引用是否仍然有效？ | 确认或更新 |
| 31 | F-rel-22 | rel | update-dependencies.yaml repository_dispatch 外部可达性？ | 确认门控 |
| 32 | F-res-06 | res | 小组件触控目标 25-32dp——是否豁免 48dp 标准？ | 确认或提升 |
| 33 | F-res-12 | res | extractNativeLibs=true 是否有必须解压的依赖？ | 确认依赖加载方式 |
| 34 | F-res-13 | res | ExternalControlActivity 深链是否需增加一次性确认防钓鱼？ | 确认或实现 |

## Remaining risk and uninspected areas

- **mihomo 子模块内部**：`core/src/foss/golang/clash` 为第三方子模块，未审计 Go 核心代码。依赖扫描、安全漏洞、性能瓶颈均未覆盖。
- **构建验证未运行**：AGENTS.md 禁止本地构建，Gradle 构建脚本、Go 编译、资源链接、依赖解析完整性均未实际验证。
- **动态行为未验证**：跨进程 SharedPreferences 一致性、Room 空 IN() 行为、Binder 线程池压力、ANR 门槛等依赖运行时行为，均基于静态推理。
- **生成/忽略内容**：`app/src/main/assets`（构建期生成）、`device_firmware/`、`tmp/`、`build/` 产物未审查。
- **Play 商店 listing**：fastlane/ 元数据仅确认存在，未与 Google Play 实际 listing 交叉验证。
- **Renovate 配置**：Renovate 是否已在 GitHub 侧启用、是否实际生效未经确认。
- **性能基线**：无 Trace/Profile 数据，所有性能结论基于代码结构推理，缺乏量化证据。
- **安全纵深**：网络层安全（TLS 配置、证书固定）、加密策略（age 密钥派生）、日志敏感信息等未深入审查。

## 并行 agent 扫描记录

| 模块 | Agent 状态 | 发现文件 |
| --- | --- | --- |
| core | 完成 | `tmp/audit-2026-08-04/findings-core.md` (27 发现) |
| service | 完成 | `tmp/audit-2026-08-04/findings-svc.md` (28 发现) |
| design | 完成 | `tmp/audit-2026-08-04/findings-design.md` (25 发现) |
| app | 完成 | `tmp/audit-2026-08-04/findings-app.md` (25 发现) |
| sdk+common+hideapi+kaidl | 完成 | `tmp/audit-2026-08-04/findings-sdk.md` (30 发现) |
| build+CI+docs | 完成 | `tmp/audit-2026-08-04/findings-rel.md` (22 发现) |
| tests | 完成 | `tmp/audit-2026-08-04/findings-test.md` (18 发现) |
| manifests+res | 完成 | `tmp/audit-2026-08-04/findings-res.md` (13 发现) |