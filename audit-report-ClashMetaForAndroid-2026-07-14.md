# Fuck My Shit Mountain 审计报告

**项目:** ClashMetaForAndroid
**审计模式:** full(全维度)
**日期:** 2026-07-14
**审计者:** Claude (Opus 4.8)

---

## 1. 执行摘要 (Executive Summary)

ClashMetaForAndroid 是一个成熟的 Android VPN/代理客户端,封装 mihomo(Clash Meta)Go 核心,采用多模块 Kotlin/Java 架构(app / core / service / design / common / hideapi,共 270 个首方 Kotlin/Java 文件)加 JNI 桥接的 Go 原生核心。本仓库明显经历过一轮结构化审计与修复(见 `docs/plans`、`outputs/runtime/vibe-sessions`、`audit-report-...-2026-07-04.md`),整体工程成熟度高于同类开源项目。

**亮点很实在**:CI/发布流水线是本仓库最强的一环——发布顺序强制为 测试 → Lint → 构建 → SHA256 校验和 → 原子推送 tag,签名密钥仅通过 GitHub Secrets 注入、在 `$RUNNER_TEMP` 解码、`chmod 600`、`always()` 清理;keystore 从未进入 Git 历史;`verify-repository-policy.py` 把大量安全不变量固化成 CI 门禁(禁止跟踪私钥、强制内部控制 Activity 非导出、备份仅限 sharedpref、Geo 资产固定 commit + SHA-256)。迁移代码有 zip-slip 的 canonical 路径防护、导入前 `checkSignatures` 验签、自定义权限为 `signature` 级;PendingIntent 默认 `FLAG_IMMUTABLE`;文件大小纪律优秀,最大首方文件仅 421 行。

**主要风险集中在数据完整性与崩溃原子性**:Alpha→Meta 一次性迁移在任何非预期异常时会把 `KEY_COMPLETED` 永久置真,用户的整套配置将永久无法迁移且无恢复路径(High);活动配置目录采用"先删除后递归复制"的非原子写,进程被杀会留下空/半写目录,导致每次启动都加载损坏配置(High);Geo 资产解包同样非原子;Go 导出函数在无 `recover()` 的 goroutine 中 `panic`,一次序列化失败即崩溃整个进程。

**总体评级 B(6.8/10)**:这是一个可发布但需要在稳定性与数据完整性上做一轮针对性加固的代码库。没有 Critical 级问题,没有系统性架构债务;风险是若干"部分失败留下不一致状态"的具体缺陷,基本都是局部可修的。发布流水线、密钥治理和模块边界都做得好,不需要重写。

### 评分面板

```
Security        ████████░░  7.5  A   CI 密钥治理与签名验证扎实;用户 CA 信任、wrapper 未固定校验和为主要减分项
Stability       ██████░░░░  6.0  B   迁移永久放弃、非原子写、goroutine panic 等多处部分失败留下不一致状态
Performance     ███████░░░  7.0  A   主线程同步解包 Geo、无界 zip;无热路径瓶颈(覆盖度中等)
Testing         ██████░░░░  5.5  B   9 个真实单测 + CI 门禁,但迁移/隧道/配置处理等关键路径基本无测试
Maintainability ████████░░  7.5  A   文件大小纪律优秀、分层清晰;DRY 违规、仓库残留开发产物、依赖陈旧
Design          ███████░░░  7.0  A   fail-fast 与边界验签到位;非原子写违反 fail-safe、DRY、Global 非 SupervisorJob
Release         ███████░░░  7.0  A   发布流水线成熟;缺 wrapper 校验和、依赖校验、明确回滚文档,子模块浮动分支
─────────────────────────────────────
Overall         ███████░░░  6.8  B
```

每个维度按 0.0–10.0 评分,**越高越好(10 = 干净,0 = 屎山)**。评分基于工程质量与可维护性的整体判断,非机械扣分。

### 发现统计 (Finding Statistics)

| 严重度 | 数量 | Confirmed | Suspected |
|----------|-------|-----------|-----------|
| Critical | 0 | 0 | 0 |
| High | 2 | 2 | 0 |
| Medium | 9 | 8 | 1 |
| Low | 11 | 8 | 3 |
| Info | 1 | 1 | 0 |
| **合计** | **23** | **19** | **4** |

## 2. 项目地图 (Project Map)

**模块结构(依赖方向由外向内,未见反向依赖违规):**

- **app**(53 文件)— UI 入口层。`MainApplication`(进程初始化、Geo 解包、Alpha 迁移触发)、`MainActivity`、`ExternalControlActivity`(处理 `clash://`/`clashmeta://` deep-link 导入配置)、各设置/配置 Activity、`TileService`/`DialerReceiver`/`RestartReceiver`。
- **service**(61 文件)— 后台核心,运行在 `:background` 进程。`ClashService`/`TunService`(VpnService)、`ProfileManager`/`ProfileProcessor`/`ProfileWorker`(配置生命周期)、`migration/`(跨包迁移:`MigrationProvider` 导出、`MigrationBundle` 打包解包、`AlphaDataMigrator` 编排)、Room `data/`(Imported/Pending/Selection DAO)、`FilesProvider`(DocumentsProvider)。
- **core**(23 文件)— Kotlin 侧对 Go 核心的封装(`Clash.kt`、`ConfigurationOverride`、`Parcelizer`)。
- **core/src/main/golang/native**(37 Go 文件,首方)— JNI 桥接层,34 个 `//export` 函数。第三方 mihomo 位于 `core/src/foss/golang/clash` 子模块(浮动 Alpha 分支,不在审计范围)。
- **design**(104 文件)— UI 组件/适配器/布局逻辑,不依赖 service 内部(通过 Profile DTO 解耦)。
- **common**(28 文件)— 跨模块工具(`Global` 作用域、`Store`/`Providers` 偏好、`Migration` 常量、日志)。被 69 处 `import` 引用。
- **hideapi**(1 文件)— 隐藏 API 编译占位(`compileOnly`)。

**数据流:** deep-link/UI → `ProfileManager.create/patch` → 写入 `pendingDir` → `ProfileProcessor` 拉取订阅 → `processingDir` → 递归复制到 `importedDir` → Room 记录 → `ConfigurationModule.run` → `Clash.load` → Go 核心。

**状态所有权:** Room 数据库为配置元数据单一真相源;`ServiceStore`/`AppStore`(SharedPreferences)持有偏好;文件系统(`importedDir`/`pendingDir`)持有配置内容。

**持久化:** Room(SQLite)+ SharedPreferences + 文件目录。迁移策略:Room `Migrations.kt`/`LegacyMigration.kt`;跨包迁移用签名验证的 ContentProvider 传输 zip。

**隐私敏感数据:** 订阅 URL、代理凭据、`ageSecretKey`(配置解密密钥)存于本地 Room + 文件;备份规则已收紧至仅 `sharedpref`,敏感数据不进系统备份。

**外部接口:** VpnService、导出的 `ExternalControlActivity`(deep-link)、`MigrationProvider`(signature 权限)、`FilesProvider`(MANAGE_DOCUMENTS 权限)、`TileService`(BIND_QUICK_SETTINGS_TILE)、Go 核心的订阅 HTTP 拉取。

**安全边界:** 内部控制 Activity 非导出;VPN 控制动作不对外暴露;跨包迁移双重验签;签名密钥仅在 CI 内存/临时目录。

**最可能藏风险的区域:** 迁移编排(部分失败处理)、配置写入原子性、Go JNI 边界的 panic 传播、进程启动时的主线程 I/O。

### 覆盖矩阵 (Coverage Matrix)

| 维度 | 覆盖度 | 检查证据 | 排除/限制 |
|-----------|----------|--------------------|---------------------|
| Architecture | High | 全模块文件清单 + 大小统计、依赖方向抽查、design↛service 验证、`settings.gradle.kts` | 未逐一读 104 个 design 文件 |
| Security | High | keystore/git 历史、CI 三个工作流、`verify-repository-policy.py`、6 个 manifest、network_security_config、迁移验签、PendingIntent 标志、Geo 下载 | 未动态运行;未审计 mihomo 子模块 |
| Stability | High | service 模块深度阅读(迁移/ProfileProcessor/ProfileWorker/ConfigurationModule/Global)、`!!`/`as`/`catch`/`runBlocking` 全仓扫描 | 崩溃窗口为控制流推理,非运行时复现 |
| Performance | Medium | 主线程 I/O、无界集合、Go 超时抽查 | 无 profiling/基准;移动端无负载测试 |
| Testing | High | 全测试文件清单(9 个首方)、逐个抽读、CI 测试脚本、`run-jvm-tests.py` | 未执行测试(AGENTS.md 禁止本地构建) |
| Maintainability | High | 文件大小 top-15、DRY 抽查、耦合计数、注释/TODO 扫描 | design 模块未逐文件精读 |
| Design | High | `rubrics/principles.md` 交叉核对、fail-fast/边界/CQS 抽查 | 同上 |
| Release | High | 三个发布工作流、`libs.versions.toml`、renovate、wrapper 属性、go.mod/go.sum | 未实际触发 CI |
| Documentation | High | README、SECURITY、PRIVACY_POLICY、CONTRIBUTING、AGENTS、docs/ | — |
| Configuration | High | `build.gradle.kts`、flavor/PREMIUM 标志、signing.properties 逻辑 | — |
| Observability | Medium | 日志模式、崩溃处理(AppCrashedActivity)、无遥测确认 | 未运行时观察日志量 |
| Data-Integrity | High | 迁移/ProfileProcessor 写入原子性、Room 迁移、zip 解包 | 非运行时复现 |
| Privacy | High | PRIVACY_POLICY、备份规则、`ageSecretKey` 处理、无分析 SDK 确认 | — |
| Accessibility | Medium | 触控区尺寸(48dp 由策略强制)、无障碍语义(前次 F-05/F-06 修复) | 未用辅助技术实测;需人工验证 |
| Supply-Chain | High | 子模块分支、go.sum、wrapper 校验和、verification-metadata、maven 备份源、action 固定 | — |
| Cost | Medium | CI 构建矩阵、后台更新间隔、无云成本 | 移动端成本面小 |
| AI-Safety | Not assessed | 项目无 AI/LLM 运行时表面 | 不适用 |
| Fallback | High | 空 catch、静默回退、SDK 兼容分支扫描 | — |
| Testing-Authenticity | High | 抽读测试确认非空壳/非过度 mock | 覆盖面窄但真实 |
| Type-Safety | High | `!!`/`as`/`valueOf`/`UUID.fromString` 边界扫描 | — |
| Frontend-State | Medium | design 模块 adapter/design 类抽查 | 未逐一精读 |
| Backend-API | Not assessed | 无服务端 API(纯本地应用 + 代理核心) | 不适用 |
| Dependency-Weight | High | `libs.versions.toml` 全量、go.mod 依赖 | 未测 APK 体积 |
| Code-Consistency | High | 命名/导入/错误处理模式跨模块抽查 | — |
| Comment-Coverage | Medium | TODO/FIXME 扫描、公共 API KDoc 抽查 | 未逐文件统计注释密度 |

## 3. 顶层风险 / Top Risks(按优先级)

1. **[High] Alpha 迁移在任何异常时永久放弃** — 一次瞬时错误即让用户整套配置永久无法迁移,无恢复路径(除非清除应用数据)。
2. **[High] 活动配置目录非原子"先删后拷"** — 进程被杀留下空/半写目录,DB 行仍在,每次启动加载损坏配置。
3. **[Medium] network_security_config 信任用户安装 CA** — 用户级 CA 可对应用自身订阅拉取(含代理凭据)做 MITM。
4. **[Medium] Geo 资产解包非原子且不自愈** — 中断留下截断文件,`exists()` 检查通过,核心永久加载损坏 Geo 库。
5. **[Medium] Go 导出函数在 goroutine 中 panic 无 recover** — 一次 JSON 序列化失败崩溃整个进程而非返回错误。
6. **[Medium] MigrationProvider 在 binder 线程 runBlocking 打包** — 大配置集下同步 zip 阻塞 binder 线程,ANR 风险(FilesProvider 同模式)。
7. **[Medium] 迁移导入遇未知枚举/坏 JSON 中途中止** — 留下部分已提交数据,且随后被标记迁移完成,无重试。
8. **[Medium] Gradle wrapper 无 SHA-256 校验和 + 无依赖校验元数据** — 供应链完整性缺口。
9. **[Medium] 子模块固定在浮动 Alpha 分支** — mihomo 核心版本不可复现,每次 `--remote` 更新拉取未知代码。
10. **[Medium] `unzip` 无解压大小/条目上限** — zip 炸弹可耗尽缓存磁盘(同签名校验缓解但未消除)。
11. **[Medium] `ProfileWorker.jobs` 跨线程无同步修改** — ArrayList 并发结构性修改可丢失更新任务或损坏内部状态。
12. **[Low] `MainApplication.extractGeoFiles` 主线程同步 + 无条件运行 + DRY** — 每个进程启动都在主线程同步拷贝四个文件。
13. **[Low] `Global` 用普通 Job 而非 SupervisorJob** — 任一子协程未捕获异常会取消全进程所有 Global 协程。
14. **[Low] `outputs/runtime/vibe-sessions/` 开发产物入库** — 13 个 agent 工具会话文件被 git 跟踪,非发布内容。
15. **[Low] AndroidX/Room 依赖陈旧(约 2022 年)** — renovate 已配置但依赖未更新,可能存在已修复的 bug/CVE。

## 4. 详细发现 (Detailed Findings)

### Finding: F-01 Alpha 迁移在任何非预期异常时永久放弃

- Severity: High
- Confidence: Medium
- Category: Stability
- Status: Confirmed
- Affected area: service / migration
- Principle violated: 6.1(不吞错到终态)、6.3(区分错误分支)
- Evidence:
  - File: `service/src/main/java/com/github/kr328/clash/service/migration/AlphaDataMigrator.kt:121-124`
  - Function: `maybeImportFromAlpha`
  - Relevant behavior: 通用 `catch (e: Exception)` 分支执行 `prefs.edit().putBoolean(KEY_COMPLETED, true).apply()`。窄的 `export_unavailable` 路径正确地把 `KEY_ATTEMPTED` 重置为 false 以允许重试,但任何其它异常(`Database.database` 初始化失败、`queryAllUUIDs`、`sendProfileChanged` 瞬时 IO、复制大 bundle 时 OOM)都会把迁移永久标记为完成。
- Problem: 一次性 Alpha→Meta 导入由 `KEY_COMPLETED` 守卫。一旦置真,`maybeImportFromAlpha` 永远返回 `SkippedAlreadyDone`。
- Why it matters: 单次瞬时错误(拷贝 bundle 时存储不足、DB 尚未迁移)导致用户整套 Alpha 配置被静默且永久地不迁移,除清除应用数据外无恢复路径。
- Realistic failure scenario: 用户在 `.alpha` 旁安装 `.meta`;首次启动时设备缓存空间瞬时不足;`copyBundleFromPackage` 成功但 `importFromZip` 递归复制抛 IOException → catch 标记 COMPLETED → 即使之后释放空间重启也永远拿不到配置。
- Minimal fix: 仅对不可重试的结果(不支持格式、无 Alpha、成功导入)置 `KEY_COMPLETED=true`。对非预期异常保持 false 并重置 `KEY_ATTEMPTED=false` 以便下次重试(可加有界重试计数防死循环)。
- Better long-term fix: 引入区分"永久跳过 / 可重试失败 / 成功"的枚举状态机,持久化重试次数。
- Regression test suggestion: 注入抛异常的 `MigrationBundle.importFromZip`,断言 `KEY_COMPLETED` 保持 false、状态为 `Failed`,且第二次调用会重试。
- Estimated effort: 1-2h

### Finding: F-02 活动配置目录非原子"先删除后递归复制"

- Severity: High
- Confidence: Medium
- Category: Data-Integrity
- Status: Confirmed
- Affected area: service / ProfileProcessor
- Principle violated: 写入原子性(temp-then-rename)、4.4 fail-fast/fail-safe
- Evidence:
  - File: `service/src/main/java/com/github/kr328/clash/service/ProfileProcessor.kt:55-56` 与 `115-116`
  - Function: 配置 import/update 路径
  - Relevant behavior: `context.importedDir.resolve(uuid).deleteRecursively()` 紧接 `context.processingDir.copyRecursively(importedDir.resolve(uuid))`,无临时目录再改名;DB 行在其后(或 update 路径中在其前)更新。
- Problem: 已提交的配置目录被原地删除再由递归复制重建。若在 delete 与 copy 完成之间进程被杀(OOM、用户划掉、崩溃),`imported/<uuid>` 被留成空或部分,而 DB `Imported` 行仍存在。
- Why it matters: `ConfigurationModule.run`(`ConfigurationModule.kt:60`)对活动配置执行 `Clash.load(importedDir.resolve(uuid))`。截断/空的配置目录使活动配置加载失败,而 DB 行持续存在,应用每次启动都指向损坏配置,直到用户手动重新导入。
- Realistic failure scenario: 用户应用一个大配置;`copyRecursively` 中途被低内存杀手终止;下次 VPN 启动 `Clash.load` 报配置错误,配置不可用。
- Minimal fix: 复制到兄弟临时目录(`imported/<uuid>.tmp`),完成后原子 `renameTo` 覆盖目标(删旧、改名新),使崩溃永不留下半写活动目录;仅在改名成功后更新 DB。
- Better long-term fix: 引入统一的原子文件操作工具(见 §32),集中管理临时目录+改名与失败降级。
- Regression test suggestion: 模拟 delete 后 / copy 前失败,断言旧目录完整或操作完全回滚。
- Estimated effort: 3-5h

### Finding: F-03 network_security_config 信任用户安装的 CA

- Severity: Medium
- Confidence: High
- Category: Security
- Status: Confirmed
- Affected area: app
- Evidence:
  - File: `app/src/main/res/xml/network_security_config.xml`
  - Relevant behavior: `base-config` 的 `trust-anchors` 同时包含 `<certificates src="system" />` 和 `<certificates src="user" />`,对所有域生效。
- Problem: 对一个下载订阅配置(含代理服务器凭据、`ageSecretKey` 引用)的应用,信任用户安装的 CA 意味着任何用户级(或通过设备管理/恶意描述文件安装的)CA 都能对应用自身 HTTPS 流量(订阅拉取、provider 更新)进行中间人。
- Why it matters: VPN/代理客户端的订阅内容即其安全根基;MITM 可注入恶意代理节点或窃取订阅令牌。默认信任 user CA 扩大了这一攻击面。
- Realistic failure scenario: 用户被诱导安装一个恶意 CA 描述文件(常见钓鱼手法);攻击者随后 MITM 订阅拉取,替换代理配置,把用户流量导向攻击者控制的节点。
- Minimal fix: 移除应用自身网络流量对 `src="user"` 的信任(至少对订阅拉取域使用仅 `system` 的 `domain-config`)。若为兼容企业抓包需保留,应限定为 debug 构建。
- Better long-term fix: 对订阅拉取域做证书/公钥固定(pinning)。
- Regression test suggestion: 集成测试:安装测试 user CA,断言订阅拉取拒绝该 CA 签发的证书。
- Estimated effort: 1-2h

### Finding: F-04 Go 导出函数在 goroutine 中 panic 且无 recover

- Severity: Medium
- Confidence: High
- Category: Stability
- Status: Confirmed
- Affected area: Go native core
- Evidence:
  - File: `core/src/main/golang/native/utils.go:13,31`(`marshalJson`/`marshalString` 在错误时 `panic`),被 `core/src/main/golang/native/config.go:26-38` 等 `//export` 函数内的 `go func(){...}` 调用;`core/src/main/golang/native/` 首方代码全域 0 处 `recover()`。
  - Function: `marshalJson`、`marshalString`、`fetchAndValid`、`load` 等
  - Relevant behavior: 导出的 JNI 函数把工作放进 goroutine,其中调用会 `panic` 的序列化辅助函数;goroutine 内未捕获的 panic 会终止整个进程。
- Problem: Go 里 goroutine 中未 recover 的 panic 直接崩溃进程(不像同步返回 error 那样可跨 JNI 边界传回)。`marshalString` 对未知类型 `panic`,`marshalJson` 对序列化错误 `panic`。
- Why it matters: 后台代理进程崩溃 = VPN 隧道中断,用户流量泄漏(取决于 always-on/lockdown 设置)。这类崩溃无堆栈上下文回传 Kotlin 侧,难以诊断。
- Realistic failure scenario: 核心返回一个 `marshalString` 未覆盖类型的对象,或 JSON 编码遇到异常值 → goroutine panic → `:background` 进程崩溃 → 隧道断开。
- Minimal fix: 在每个 `//export` 函数的 goroutine 顶部加 `defer func(){ if r:=recover(); r!=nil { /* 通过回调返回错误 */ } }()`;或让 `marshal*` 返回 error 而非 panic。
- Better long-term fix: 在 JNI 边界建立统一的 panic 拦截层,所有导出函数经其转发并把错误结构化回传 Kotlin。
- Regression test suggestion: Go 单测:对 `marshalString` 传入未知类型,断言返回错误而非 panic。
- Estimated effort: 2-3h

### Finding: F-05 MigrationProvider 在 binder 线程 runBlocking 打包(ANR 风险)

- Severity: Medium
- Confidence: High
- Category: Stability
- Status: Confirmed
- Affected area: service / migration
- Principle violated: 10.x(不阻塞 binder 线程)
- Evidence:
  - File: `service/src/main/java/com/github/kr328/clash/service/migration/MigrationProvider.kt:76`
  - Function: `ensureBundle`(由 `query`/`openFile` 调用)
  - Relevant behavior: `val ok = runBlocking { MigrationBundle.exportToZip(ctx, file) }`;`exportToZip` 会 dump Room DAO、全部 SharedPreferences,并递归 zip 整个 `imported`/`pending` 目录树。`FilesProvider.kt:52,68,90,119,138` 有相同的 binder 线程 runBlocking 模式。
- Problem: `ContentProvider.query`/`openFile` 运行在调用进程的 binder 线程;`runBlocking` 同步执行完整 DB 读取加递归文件打包,大配置集下耗时数秒,阻塞 binder 线程,且打包期间持有 `exportLock`。
- Why it matters: 调用方(新 `.meta` 应用)阻塞自身 binder 调用;在受限线程池上有 ANR/超时风险。
- Realistic failure scenario: 拥有数十个导入配置的用户触发迁移;`openFile` 阻塞 binder 线程数秒打包目录,调用方的 DocumentFile/复制操作超时。
- Minimal fix: bundle 已缓存(`cachedBundle`),可保留 runBlocking 但用 `withTimeout` 限界,并优先在提前构建(离线程)后缓存结果;至少加超时。
- Better long-term fix: 在应用空闲时预构建迁移 bundle 并缓存,使 provider 调用永不触发同步打包。
- Regression test suggestion: 插桩测试:合成大配置集下测量 `openFile` 延迟,断言低于阈值或已预构建。
- Estimated effort: 2-4h

### Finding: F-06 迁移导入遇未知枚举/坏 JSON 中途中止,留下部分状态

- Severity: Medium
- Confidence: High
- Category: Type-Safety
- Status: Confirmed
- Affected area: service / migration
- Principle violated: 6.3、边界类型安全
- Evidence:
  - File: `service/src/main/java/com/github/kr328/clash/service/migration/MigrationBundle.kt:379,399`(`Profile.Type.valueOf(getString("type"))`)、`:143,159,177`(`UUID.fromString`)、`:190-204`(prefs 合并),全部在单个 `try` 内,catch 在 `:218` 返回 `skipped`。
  - Function: `importFromZip`
  - Relevant behavior: 依次处理 imported → pending → selections → 合并三个 prefs。任一解析失败(新 Alpha 构建的未知 `Profile.Type`、坏 UUID、坏 prefs JSON)在循环中途抛出;先前迭代已插入的 `ImportedDao` 行和已复制目录残留,但整体结果报 `skipped`,`AlphaDataMigrator` 随后标记 COMPLETED(见 F-01)。
- Problem: 新版 Alpha 加入 Meta 构建不认识的枚举值会导致 `IllegalArgumentException`,在部分数据写入后中止整个导入,并永久标记迁移完成。
- Why it matters: DB 留下配置子集且无重试;用户丢失部分配置。
- Realistic failure scenario: Alpha 新增 `Profile.Type`;用户迁移;前两个配置导入,第三个是新类型 → `valueOf` 抛出 → 导入报失败,2 个配置半提交,迁移被标记完成。
- Minimal fix: 每个配置/selection 迭代包 `runCatching`,跳过并记录坏记录而非中止批次;未知枚举视为跳过单条而非全失败。考虑用 Room `withTransaction` 使部分 DB 行在失败时回滚。
- Better long-term fix: 把整个导入过程包进 Room 事务并引入版本容忍的反序列化层,新枚举降级为跳过而非失败。
- Regression test suggestion: 导入含一个合法 + 一个未知类型配置的 bundle,断言合法配置导入、坏配置被跳过而不中止。
- Estimated effort: 2-4h

### Finding: F-07 Gradle wrapper 无 SHA-256 校验和 + 无依赖校验元数据

- Severity: Medium
- Confidence: High
- Category: Security
- Status: Confirmed
- Affected area: 供应链 / gradle
- Evidence:
  - File: `gradle/wrapper/gradle-wrapper.properties`(无 `distributionSha256Sum`)、无 `gradle/verification-metadata.xml`、CI 工作流无 `gradle/actions/wrapper-validation`。
  - Relevant behavior: `distributionUrl=...gradle-8.10.2-bin.zip` 未固定校验和;依赖无校验元数据。注意 `build.gradle.kts` 的 `tasks.wrapper` 会在生成时追加 sha256,但当前 properties 里没有该行。
- Problem: Gradle 发行版下载不校验完整性;所有 Maven/Go 依赖无校验和固定。若上游或镜像被篡改,构建可能拉入被污染的工具链或依赖。
- Why it matters: 该应用签名并发布 APK;构建工具链被污染可导致供应链攻击。`build.gradle.kts` 还引入了自定义 maven 源 `raw.githubusercontent.com/MetaCubeX/maven-backup`,进一步扩大信任面。
- Realistic failure scenario: 攻击者攻陷 Gradle 分发镜像或 GitHub 原始内容托管;下一次 CI 构建拉入被篡改的 wrapper/依赖,注入进已签名的发布 APK。
- Minimal fix: 在 `gradle-wrapper.properties` 加 `distributionSha256Sum=<官方值>`;在 CI 加 `gradle/actions/wrapper-validation` 步骤;生成 `gradle/verification-metadata.xml` 固定依赖校验和。
- Better long-term fix: 建立完整的供应链完整性基线:wrapper 校验和 + 依赖校验元数据 + CI wrapper-validation,纳入 verify-repository-policy 门禁。
- Regression test suggestion: CI 门禁:篡改 wrapper jar 后断言 wrapper-validation 失败。
- Estimated effort: 2-4h

### Finding: F-08 子模块固定在浮动 Alpha 分支,核心不可复现

- Severity: Medium
- Confidence: High
- Category: Release / Supply-Chain
- Status: Confirmed
- Affected area: core / 子模块
- Evidence:
  - File: `.gitmodules`
  - Relevant behavior: `[submodule "clash-foss"] url = ...mihomo, branch = Alpha`;CI 用 `git submodule update --remote --force`(update-dependencies.yaml:16)拉取分支最新提交。
- Problem: mihomo 核心跟踪浮动 `Alpha` 分支而非固定 commit。虽然 `git submodule` 在超级项目中会记录一个具体 commit,但 `--remote` 更新每次拉分支 HEAD,发布构建的核心版本随时间漂移且不可复现。
- Why it matters: 两次相同 tag 的构建可能包含不同核心代码;无法复现历史发布用于取证或回归;`Alpha` 分支按定义不稳定,用于面向公众的发布风险偏高。
- Realistic failure scenario: 用户报告 v2.11.32 的隧道 bug;维护者重新检出该 tag 构建,却因 `--remote` 已拉取更新的 Alpha 核心而无法复现。
- Minimal fix: 发布构建改用固定 commit(移除 `--remote`,或用 tagged release);为发布记录锁定的子模块 SHA。
- Better long-term fix: 改用发布 tag 或锁定 SHA 的子模块引用,并在发布产物中记录核心版本以支持取证复现。
- Regression test suggestion: 发布流水线加断言:核心子模块处于记录的固定 commit。
- Estimated effort: 1-2h

### Finding: F-09 unzip 无解压大小/条目上限(zip 炸弹)

- Severity: Medium
- Confidence: Medium
- Category: Stability
- Status: Confirmed
- Affected area: service / migration
- Principle violated: 10.2(无界资源消耗)
- Evidence:
  - File: `service/src/main/java/com/github/kr328/clash/service/migration/MigrationBundle.kt:325-343`
  - Function: `unzip`
  - Relevant behavior: 路径穿越已正确防护(`:329-333` canonical 校验),但 `zip.copyTo(out)` 每条目无大小上限,也无总字节/条目数限制。
- Problem: bundle 从另一包的 ContentProvider 读取(`MigrationProvider.enforceCaller` 强制同签名,信任边界真实),但被攻陷/有 bug 的同签名兄弟应用或损坏缓存仍可提供构造的 zip;zip 炸弹或超大条目填满 `cacheDir`。
- Why it matters: 失控解压可耗尽磁盘,导致别处(含 Geo 文件写入)IOException 或 OOM;解压在 `importLock` 下进行。
- Realistic failure scenario: 损坏的 `migration-import.zip` 含高压缩比多 GB 条目被解压,填满设备存储后失败。
- Minimal fix: 在 `unzip` 循环中累计已解压字节与条目数,超过合理上限(单条目 + 总量)时抛异常中止。
- Better long-term fix: 为所有从外部来源解压的路径建立统一的有界解压工具(总量/条目/单条目上限 + 压缩比检测)。
- Regression test suggestion: 喂入声明/解压尺寸超上限的 zip,断言 `unzip` 在超限前抛出。
- Estimated effort: 1-2h

### Finding: F-10 ProfileWorker.jobs 列表跨线程无同步修改

- Severity: Medium
- Confidence: Medium
- Category: Stability
- Status: Confirmed
- Affected area: service / ProfileWorker
- Principle violated: 5.4 / 10.x(共享可变状态无同步)
- Evidence:
  - File: `service/src/main/java/com/github/kr328/clash/service/ProfileWorker.kt:30`(`private val jobs = mutableListOf<Job>()`)、`:43`(`onCreate` 协程中 `jobs.removeFirstOrNull()`)、`:66,76`(`onStartCommand` 主线程中 `jobs.add(job)`)。
  - Function: `onCreate` 排空循环 与 `onStartCommand`
  - Relevant behavior: 普通 `ArrayList` 在主线程 `add`,同时在协程中结构性 `removeFirstOrNull`。`ArrayList` 非线程安全,并发结构性修改可破坏内部状态或丢任务。
- Problem: 竞态的 add/remove 可抛异常、丢失更新任务,或让排空循环提前退出并 `stopSelf()` 而仍有工作待处理。
- Why it matters: 调度的配置更新可能被静默丢弃,导致订阅不更新。
- Realistic failure scenario: 一个调度更新到达(`onStartCommand` add)恰逢排空循环弹出最后一个任务;add 丢失或列表损坏,更新被丢弃。
- Minimal fix: 用线程/协程安全结构(`Channel<Job>` 或用 `Mutex`/synchronized 守卫 `jobs`),或把所有 `jobs` 访问编排到单一 dispatcher/actor。
- Better long-term fix: 把 ProfileWorker 的任务队列重构为基于 Channel 的 actor 模型,单一协程拥有队列状态。
- Regression test suggestion: 压力测试:排空循环运行时并发发多个 `ACTION_PROFILE_REQUEST_UPDATE`,断言所有任务完成。
- Estimated effort: 2-3h

### Finding: F-11 Geo 资产解包非原子且不自愈

- Severity: Medium
- Confidence: High
- Category: Data-Integrity
- Status: Confirmed
- Affected area: app / MainApplication
- Principle violated: 写入原子性(temp-then-rename)
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/MainApplication.kt:58-96`
  - Function: `extractGeoFiles`
  - Relevant behavior: 每个文件用 `FileOutputStream(geoipFile).use { assets.open(...).copyTo(it) }` 直写最终路径,仅由 `if (!geoipFile.exists())` 守卫。
- Problem: 直写目标文件。若复制被中断(进程死亡、存储不足),文件存在但截断。守卫是 `exists()` 加 `lastModified() < updateDate`,故上次更新后创建的截断文件会通过两项检查且永不重新解包。
- Why it matters: `geoip.metadb`/`geosite.dat`/`ASN.mmdb`/`BundleMRS.7z` 被核心消费;截断的 Geo 库使核心初始化失败或错误路由,且无自愈。
- Realistic failure scenario: 更新后首次启动解包 `geoip.metadb` 时进程被杀;后续每次启动都视文件为已存在且最新,核心加载损坏 DB。
- Minimal fix: 解包到临时文件(`geoip.metadb.tmp`),完全复制后再 `renameTo`;失败删临时文件。可选校验大小与 asset 长度。
- Better long-term fix: 复用 §32 的原子文件工具统一 Geo 与配置的资产落盘,并按 asset 长度校验完整性。
- Regression test suggestion: 模拟中断复制,断言目标要么缺失(会重新解包)要么完整,绝不截断。
- Estimated effort: 2h

### Finding: F-12 extractGeoFiles 主线程同步执行、无条件运行、四段近重复

- Severity: Low
- Confidence: High
- Category: Performance / Maintainability
- Status: Confirmed
- Affected area: app / MainApplication
- Principle violated: 4.1 DRY、主线程 I/O
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/MainApplication.kt:34,46-97`
  - Function: `onCreate` → `extractGeoFiles`
  - Relevant behavior: `extractGeoFiles()` 在 `onCreate` 中于进程判断(`if (processName == packageName)`)**之前**无条件调用,故每个进程(主 + `:background`)启动都在主线程同步拷贝四个文件;四个文件块是近乎逐字重复的复制粘贴(exists → lastModified 比较 → delete → 复制)。
- Problem: 主线程 I/O 在冷启动路径;四段重复代码增加维护成本(改一处易漏其它)。
- Why it matters: 大 Geo 文件(BundleMRS.7z 等)首次拷贝可能触发启动卡顿/ANR;每个进程重复执行浪费。
- Realistic failure scenario: 低端设备冷启动时主线程拷贝数 MB Geo 文件,启动可见卡顿。
- Minimal fix: 抽取 `extractAsset(name)` 辅助函数消除重复;仅在主进程执行;移到后台线程或首次核心加载前的懒初始化。
- Better long-term fix: 把 Geo 资产管理抽为独立的懒初始化组件,与应用启动路径解耦。
- Regression test suggestion: 单测抽取后的 `extractAsset` 幂等性与截断处理(结合 F-11)。
- Estimated effort: 1-2h

### Finding: F-13 Global 使用普通 Job 而非 SupervisorJob

- Severity: Low
- Confidence: Medium
- Category: Stability
- Status: Suspected
- Affected area: common / Global
- Principle violated: 协程异常隔离(SupervisorJob)
- Evidence:
  - File: `common/src/main/java/com/github/kr328/clash/common/Global.kt:8`
  - Function: `object Global : CoroutineScope by CoroutineScope(Dispatchers.IO)`
  - Relevant behavior: `CoroutineScope(...)` 创建非 supervisor `Job`;应用级使用(`MainApplication.kt:102` 触发 Alpha 迁移、`Database.kt:43` 触发 legacy 迁移)。
- Problem: 普通 `Job` 下,任一 `Global.launch` 子协程未捕获异常会取消父 Job 及所有兄弟协程,且该作用域此后整个进程生命周期保持取消。当前调用点多数内部捕获,故为潜在隐患。
- Why it matters: 未来任一 Global 协程边缘情况抛出会静默杀死 legacy DB 迁移、Alpha 迁移等所有 Global 工作,无重启。
- Realistic failure scenario: 一次代码改动新增可抛出的 `Global.launch`,取消 Global job,并发运行的 legacy 迁移协程在写入中途被取消。
- Minimal fix: `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 并附 `CoroutineExceptionHandler` 隔离并记录子失败。
- Better long-term fix: 为应用级协程作用域建立统一的监督 + 异常上报基础设施。
- Regression test suggestion: 在 Global 上启动抛异常子协程,断言第二个 `Global.launch` 仍执行。
- Estimated effort: 30m

### Finding: F-14 outputs/runtime/vibe-sessions/ 开发产物被 git 跟踪

- Severity: Low
- Confidence: High
- Category: Maintainability / Release
- Status: Confirmed
- Affected area: 仓库根
- Evidence:
  - File: `outputs/runtime/vibe-sessions/`(13 个文件:delegation-envelope.json、governance-capsule.json、intent-contract.json、skeleton-receipt.json、stage-lineage.json、delivery-acceptance.md 等,约 8KB),`git ls-files outputs/` 确认被跟踪;`.gitignore` 未包含 `outputs/`。
  - Relevant behavior: agent 工具会话产物随 `bc9a35a fix: remediate full audit findings` 提交入库,且目录未 gitignore,会持续累积。
- Problem: 内部 agent 编排产物出现在准备公开发布的仓库中,非发布内容,可能泄露内部流程细节且污染仓库。
- Why it matters: 一个准备稳定公开发布的项目不应携带开发工具会话残留;后续会随每次 agent 运行累积。
- Realistic failure scenario: 公开发布后第三方在仓库看到内部编排元数据,或目录随时间膨胀。
- Minimal fix: `git rm -r --cached outputs/runtime/vibe-sessions/` 并把 `outputs/`(或该子路径)加入 `.gitignore`;确认无构建依赖它。
- Better long-term fix: 在 verify-repository-policy 中加入跟踪文件白名单门禁,防止开发产物再次入库。
- Regression test suggestion: 在 `verify-repository-policy.py` 加断言:不跟踪 `outputs/runtime/vibe-sessions/`。
- Estimated effort: 15m

### Finding: F-15 AndroidX/Room 依赖陈旧

- Severity: Low
- Confidence: Medium
- Category: Security / Maintainability
- Status: Confirmed
- Affected area: gradle / libs.versions.toml
- Evidence:
  - File: `gradle/libs.versions.toml`
  - Relevant behavior: `coreKtx 1.8.0`、`appcompat 1.4.2`、`room 2.4.2`、`material 1.6.1`、`activity 1.5.0`、`fragment 1.5.0`(约 2022 年),而 AGP 8.8/Kotlin 2.1 为近期版本;`renovate.json` 已配 `config:recommended` 但依赖未更新。
- Problem: 核心 AndroidX 库落后约 2-3 年;renovate 配置存在但依赖陈旧,说明自动更新未生效或 PR 被忽略。
- Why it matters: 陈旧依赖可能含已修复的 bug/安全问题;与 targetSdk 35 组合可能有已知兼容问题;偏离 renovate 的维护意图。
- Realistic failure scenario: 旧版 Room/AndroidX 的已知崩溃在新 Android 版本上触发,而新版已修复。
- Minimal fix: 审阅并合并 renovate PR,分批升级核心 AndroidX/Room 依赖并跑 CI 测试/Lint。
- Better long-term fix: 启用 renovate 的自动合并策略并纳入 CI 门禁,使依赖持续保持最新。
- Regression test suggestion: 升级后依赖 CI 测试套件 + Lint 门禁把关。
- Estimated effort: 2-4h(含回归验证)

### Finding: F-16 FilesProvider 吞掉所有异常返回空游标

- Severity: Low
- Confidence: High
- Category: Stability
- Status: Confirmed
- Affected area: service / FilesProvider
- Principle violated: 6.1(不吞错)、6.2(保留上下文)
- Evidence:
  - File: `service/src/main/java/com/github/kr328/clash/service/FilesProvider.kt:131-133,147-149`
  - Function: DocumentsProvider query/列举
  - Relevant behavior: `catch (e: Exception) { MatrixCursor(resolveDocumentProjection(projection)) }` — 异常既不记录也不重抛,返回空游标。
- Problem: 任何解析/列举失败被掩盖为"空目录",无 `Log` 调用,异常上下文完全丢失。
- Why it matters: 文件管理 UI 中真实的 IO/权限错误与空文件夹无法区分,故障不可诊断。
- Realistic failure scenario: 列举 provider 目录时的权限/路径错误在用户侧呈现为空文件夹,无日志可诊断。
- Minimal fix: 返回兜底游标前 `Log.w` 记录异常;考虑用错误状态而非无法区分的空结果表达。
- Better long-term fix: 为所有 ContentProvider/DocumentsProvider 建立统一的错误日志与错误状态表达约定。
- Regression test suggestion: 强制 `picker.list` 抛出,断言有日志输出。
- Estimated effort: 30m

### Finding: F-17 ConfigurationModule 对广播 extra 无守卫 UUID.fromString

- Severity: Low
- Confidence: Medium
- Category: Stability
- Status: Suspected
- Affected area: service / ConfigurationModule
- Principle violated: 边界类型安全
- Evidence:
  - File: `service/src/main/java/com/github/kr328/clash/service/clash/module/ConfigurationModule.kt:37`
  - Function: `select`/`broadcasts.onReceive` lambda
  - Relevant behavior: `UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))` 在 `:46` 的 `try` 之外,故解析失败逃逸出模块错误处理。
- Problem: 若 `EXTRA_UUID` 为 null 或格式错误,`UUID.fromString` 在 `select` 子句内抛异常(在 try 外),沿模块协程上抛;在 `ClashRuntime` 结构化并发下会拆掉运行时(VPN 停止)。
- Why it matters: 单个格式错误的 `ACTION_PROFILE_CHANGED` 广播会崩溃配置模块并停止隧道。广播受权限保护(`RECEIVE_SELF_BROADCASTS`)且内部发送方总附带合法 UUID,故可利用性低,但仍是 VPN 热路径上的无守卫边界解析。
- Realistic failure scenario: 未来/重构的发送方发出不带该 extra 的 `ACTION_PROFILE_CHANGED`;配置模块抛出,VPN 断开。
- Minimal fix: 防御式解析 `runCatching { UUID.fromString(...) }.getOrNull()`,失败返回 null 走既有 `changed == null` 处理。
- Better long-term fix: 为所有跨进程边界的 extra 解析建立类型安全的解码工具层。
- Regression test suggestion: 投递缺失/垃圾 UUID extra 的 `PROFILE_CHANGED` intent,断言模块继续而非崩溃。
- Estimated effort: 30m

### Finding: F-18 SharedPreference 存储对 getString/getStringSet 强解包

- Severity: Low
- Confidence: Medium
- Category: Type-Safety
- Status: Confirmed
- Affected area: common / Providers
- Evidence:
  - File: `common/src/main/java/com/github/kr328/clash/common/store/Providers.kt:28,38`
  - Function: string / stringSet provider getter
  - Relevant behavior: `preferences.getString(key, defaultValue)!!` 与 `getStringSet(...)!!`。
- Problem: 若键以非预期类型持久化(跨版本类型变更,或 F-06 迁移合并写入类型不匹配的值),类型化 getter 返回 null 或抛 `ClassCastException`,`!!` 把 null 变 NPE。
- Why it matters: `mergeSharedPreferences` 从导入 bundle 写值后,某键以意外类型存储可能使后续 `getString(key)!!` 抛出,崩溃读取该设置的代码。
- Realistic failure scenario: 导入的 prefs 条目把某键存为一种类型而另一代码路径按 string set 读取(或反之),类型化 getter 抛出。
- Minimal fix: null 时返回默认(`?: defaultValue`)而非 `!!`,并在 getter 中防 `ClassCastException`。
- Better long-term fix: 用类型安全的偏好访问封装替换原始 SharedPreferences getter,消除 `!!`。
- Regression test suggestion: 把某键存为 Int 再经 `getString` 读取,断言优雅降级为默认而非崩溃。
- Estimated effort: 30m

### Finding: F-19 activeProfile 存储字符串解析可对损坏值抛出

- Severity: Low
- Confidence: Low
- Category: Type-Safety
- Status: Suspected
- Affected area: service / ServiceStore
- Evidence:
  - File: `service/src/main/java/com/github/kr328/clash/service/store/ServiceStore.kt:17-21`
  - Function: `activeProfile` 的 `from` 转换,经 `Store.typedString`
  - Relevant behavior: `from = { if (it.isBlank()) null else UUID.fromString(it) }`;非空但格式错误的 `active_profile` 使 getter 抛 `IllegalArgumentException`。
- Problem: 每次读 `store.activeProfile`(`ConfigurationModule.kt:47`、`ProfileProcessor.active`)都会抛。仅内部写入,概率低。
- Why it matters: 该 pref 损坏(或迁移合入坏值)会在每次服务启动时破坏配置加载。
- Realistic failure scenario: 迁移合入非 UUID 的 `active_profile`;后续读取抛出,VPN 无法选配置。
- Minimal fix: `from` 解析包 `runCatching { ... }.getOrNull()`,损坏值降级为"无活动配置"。
- Better long-term fix: 把持久化的领域类型(UUID 等)的序列化/反序列化集中到容错的类型化 Store 层。
- Regression test suggestion: 把 `active_profile` 设为垃圾非空串,断言 `activeProfile` 返回 null。
- Estimated effort: 30m

### Finding: F-20 MIGRATE_DATA 自定义权限名硬编码而非 ${applicationId}

- Severity: Low
- Confidence: Medium
- Category: Security
- Status: Confirmed
- Affected area: common / service manifest
- Evidence:
  - File: `common/src/main/AndroidManifest.xml:11-16`、`common/src/main/java/com/github/kr328/clash/common/constants/Migration.kt:4`
  - Relevant behavior: 权限名硬编码为 `com.github.metacubex.clash.permission.MIGRATE_DATA`(`signature` 级),而非用 `${applicationId}` 派生。
- Problem: 固定权限名理论上可被恶意应用抢先以更低保护级别定义(自定义权限抢注)。不过 `MigrationProvider.enforceCaller` 在代码层用 `checkSignatures` 二次验签兜底,实际风险被显著缓解。
- Why it matters: 纵深防御良好(代码验签兜底),但硬编码权限名 + 依赖安装顺序的保护级别是已知的 Android 陷阱;若未来移除代码层验签则暴露。
- Realistic failure scenario: 恶意应用先安装并以 `normal` 级定义同名权限;但代码层 `checkSignatures` 仍拒绝非同签名调用方,故当前不可利用。
- Minimal fix: 记录该权限名有意固定(alpha↔meta 需共享同名)的设计意图;保留代码层验签作为主要控制。可选:文档化为什么不能用 `${applicationId}`。
- Better long-term fix: 评估是否可将迁移权限迁移到基于 ${applicationId} 的命名 + 显式信任列表,减少对固定名的依赖。
- Regression test suggestion: 插桩测试:非同签名调用方访问 MigrationProvider 被 `SecurityException` 拒绝。
- Estimated effort: 30m(主要为文档)

### Finding: F-21 MainApplication 覆盖 finalize()(反模式)

- Severity: Low
- Confidence: High
- Category: Maintainability
- Status: Confirmed
- Affected area: app / MainApplication
- Principle violated: 3.1 最小惊讶
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/MainApplication.kt:113-115`
  - Function: `fun finalize() { Global.destroy() }`
  - Relevant behavior: 覆盖 `finalize()` 调 `Global.destroy()`。
- Problem: `finalize()` 在 Java/Kotlin 中已弃用且不可靠——不保证被调用,时机不确定,拖慢 GC。`Application` 对象生命周期与进程一致,`finalize` 实际永不有意义地触发。
- Why it matters: 死代码,给读者错误的资源清理心智模型;`finalize` 已在现代 JVM 弃用。
- Realistic failure scenario: 无实际失败(方法基本不被调用),但误导维护者以为存在清理机制。
- Minimal fix: 删除 `finalize()` 覆盖;`Application` 无需显式销毁 Global(进程退出即回收)。
- Better long-term fix: 无(删除死代码即可)。
- Regression test suggestion: 无(删除死代码)。
- Estimated effort: 15m

### Finding: F-22 ExternalControlActivity deep-link 的 url 参数无 scheme 校验

- Severity: Low
- Confidence: Medium
- Category: Security
- Status: Suspected
- Affected area: app / ExternalControlActivity
- Principle violated: 4.4 fail-fast(边界输入验证)
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/ExternalControlActivity.kt:26,30-42`
  - Function: `onCreate`
  - Relevant behavior: 从导出的 `clash://`/`clashmeta://` deep-link 取 `url` 查询参数,不校验其 scheme 直接作为配置 `source` 传给 `patch`;`type` 默认 `Url`。该 `source` 后续被 `ProfileProcessor.fetchProfile` 拉取。
- Problem: 任意应用/网页可用 `clash://install-config?url=<任意>` 拉起该导出 Activity,注入一个 source 为任意 URL 的待处理配置。虽然会跳到 `PropertiesActivity` 需用户确认,但未校验 `url` 是否为 http(s)(可能为 `file://` 等)。
- Why it matters: 缺少 scheme 白名单是边界输入未 fail-fast;结合 `type=file` 分支,`url` 被当作文件路径,潜在本地文件读取面(取决于 `fetchProfile` 对 File 类型的处理)。用户确认步骤缓解了大部分风险。
- Realistic failure scenario: 恶意网页触发 `clash://install-config?url=file:///...&type=file`,尝试把本地文件路径作为配置源;需用户在 Properties 界面确认导入。
- Minimal fix: 校验 `url` 参数为 `http`/`https` scheme;拒绝或忽略其它 scheme。
- Better long-term fix: 为所有导出的 deep-link 入口建立统一的输入校验/白名单层。
- Regression test suggestion: 用非 http scheme 的 `url` 触发 Activity,断言被拒绝。
- Estimated effort: 1h

### Finding: F-23 物理存在的 keystore 文件位于工作目录(已 gitignore,未入历史)

- Severity: Info
- Confidence: High
- Category: Security
- Status: Confirmed
- Affected area: 仓库根
- Evidence:
  - File: `clashmeta-release.jks`、`keystore_base64.txt`(物理存在于工作目录)
  - Relevant behavior: `git ls-files` 与 `git log --all --diff-filter=A` 确认两者**从未被 git 跟踪、从未进入历史**;`.gitignore` 明确排除 `*.jks`/`keystore_base64.txt`;`verify-repository-policy.py` CI 门禁禁止跟踪私钥容器且检查 `release.keystore` 不可从历史访问。
- Problem: 这不是漏洞——只是提示:签名密钥容器与其 base64 明文物理存在于开发者工作目录。治理已到位(gitignore + CI 门禁 + SECURITY.md 记录历史轮换责任)。
- Why it matters: 记录以确认审计已核实"密钥未泄漏到版本控制"这一关键点。风险仅限开发者本地机器安全,不属仓库风险。SECURITY.md 已说明历史上曾有 `release.keystore` 被提交并已从重写历史中清除,维护者应假设该历史副本已公开并轮换。
- Realistic failure scenario: 无仓库层面场景;仅当开发者本地机器被攻陷时本地 keystore 暴露(属常规本地安全,非本仓库缺陷)。
- Minimal fix: 无需改动。建议开发者将本地 keystore 移出工作目录树以进一步降低误提交面。
- Better long-term fix: 无需架构改动;可在 SECURITY.md 补充开发者本地密钥存放规范。
- Regression test suggestion: 已有 `verify-repository-policy.py` 覆盖。
- Estimated effort: 0(已缓解)

## 5. 架构分析(Architecture)

- 覆盖度: High
- 检查证据: 全模块文件清单与大小统计、`settings.gradle.kts`、依赖方向抽查(design↛service 验证)、`build.gradle.kts` 模块配置。
- 排除/限制: 未逐一精读 104 个 design 文件。

### 架构小结

| 子类型 | 数量 | 受影响区域 | 建议 |
|---------|-------|----------------|-------------------|
| ModuleBoundary | 0 | — | 边界清晰,无违规 |
| DependencyDirection | 0 | — | 依赖由外向内,design 已与 service 解耦(前次 F-18) |
| StateOwnership | 1 | ProfileWorker.jobs(F-10) | 定义单一线程安全所有权 |
| BoundaryContract | 0 | — | 迁移用显式 JSON manifest + 格式版本 |
| EvolutionRisk | 1 | 迁移枚举演进(F-06) | 降低未知枚举的爆炸半径 |

模块分层健康:`app`(UI)→ `design`/`service`/`core`/`common`,依赖方向由外向内,未见 UI 反向被业务逻辑依赖或持久化层导入 UI 类型的情况。`design` 模块不 import `service` 内部(通过 Profile DTO/ServiceSettings port 解耦,前次审计 F-18 修复保持有效)。`common` 作为工具层被 69 处引用,耦合度偏高但内容为稳定基础设施(日志、偏好、常量),属可接受的稳定依赖方向(原则 2.6)。JNI 边界(Go native ↔ Kotlin core)封装在 `core` 模块,隔离良好。

## 6. 安全分析(Security)

- 覆盖度: High
- 检查证据: keystore 的 git 跟踪/历史核验、三个 CI 工作流、`verify-repository-policy.py`(200 行策略门禁)、6 个 AndroidManifest、`network_security_config.xml`、迁移验签、`pendingIntentFlags` 全部用法、Geo 下载逻辑、Go `fetch.go` 超时、`InsecureSkipVerify` 扫描。
- 排除/限制: 未动态运行应用;未审计 mihomo 第三方子模块内部。

### 安全小结

| 子类型 | 数量 | 受影响面 | 建议 |
|---------|-------|------------------|-------------------|
| 密钥管理 | 0(Info×1) | keystore(F-23) | 已妥善治理,仅记录 |
| 传输安全 | 1 | network_security_config(F-03) | 移除应用流量的 user CA 信任 |
| 供应链 | 2 | wrapper/依赖校验(F-07)、子模块分支(F-08) | 固定校验和与 commit |
| 输入验证 | 1 | deep-link scheme(F-22) | 白名单 http(s) |
| 权限模型 | 1 | MIGRATE_DATA 硬编码(F-20) | 代码验签已兜底,文档化 |

**做得好的地方(需明确肯定):** CI 密钥治理是范本级——base64 secret 注入 + `$RUNNER_TEMP` 解码 + `chmod 600` + `always()` 清理 + 从不打印;keystore 从未入 git 历史;`verify-repository-policy.py` 把安全不变量固化为 CI 门禁(禁跟踪私钥、内部控制 Activity 非导出、导出 Activity 不暴露 VPN 控制动作、备份仅 sharedpref、Geo 固定 commit+SHA256)。迁移跨包传输双重验签(`signature` 权限 + `checkSignatures`)。PendingIntent 默认 `FLAG_IMMUTABLE`。导出组件最小化:仅 `MainActivity`/`ExternalControlActivity`/`TileService`(BIND 权限)/两个 receiver,VPN 控制不外暴露。未发现 `InsecureSkipVerify`、明文流量、硬编码凭据。

## 7. 稳定性分析(Stability)

- 覆盖度: High
- 检查证据: service 模块深度阅读(migration/ProfileProcessor/ProfileWorker/ConfigurationModule/Global/TunService/ClashRuntime/Database)、全仓 `!!`/`as`/`catch`/`runBlocking`/`Thread.sleep` 扫描、Go 首方 panic/recover 计数。
- 排除/限制: 崩溃窗口(非原子写、runBlocking ANR、jobs 竞态、goroutine panic)为控制流推理而非运行时复现,严重度取决于真实配置规模与时序。

### 稳定性小结

| 子类型 | 数量 | 受影响区域 | 建议 |
|---------|-------|----------------|------|
| 部分失败/终态 | 2 | 迁移放弃(F-01)、导入中止(F-06) | 状态机 + 逐条 runCatching |
| 写入原子性 | 2 | 配置目录(F-02)、Geo(F-11) | temp-then-rename |
| 并发 | 2 | ProfileWorker(F-10)、Global(F-13) | 线程安全结构 + SupervisorJob |
| 进程崩溃 | 1 | Go goroutine panic(F-04) | recover 或返回 error |
| binder 阻塞 | 1 | runBlocking 打包(F-05) | withTimeout + 预构建 |
| 吞错 | 1 | FilesProvider 空游标(F-16) | 记录日志 |

稳定性是本次最集中的维度。核心模式问题是"操作在部分完成后失败会留下不一致状态"(F-01/F-02/F-06/F-11),叠加迁移一次性标志使不一致无法自愈。这些基本都是局部可修的(加临时目录改名、逐条 catch、状态机),不需要重构。

## 8. 性能分析(Performance)

- 覆盖度: Medium
- 检查证据: 主线程 I/O(extractGeoFiles)、无界集合(unzip)、Go config fetch 超时(60s context)。
- 排除/限制: 无 profiling/基准测试;移动端无负载测试;未测 APK 体积或内存占用。

### 性能小结

| 子类型 | 数量 | 成本驱动 | 建议 |
|---------|-------|-------------|------|
| 主线程 I/O | 1 | Geo 解包(F-12) | 移后台/懒加载 |
| 无界资源 | 1 | zip 解压(F-09) | 加上限 |

未发现热路径瓶颈或资源泄漏。Go config fetch 有 60s context 超时。主要性能关注点是冷启动路径上的主线程 Geo 拷贝(F-12)。覆盖度中等(无运行时 profiling),故不给满分。

## 9. 测试缺口(Testing)

- 覆盖度: High(结构)
- 检查证据: 全部 9 个首方测试文件清单与逐个抽读、`run-jvm-tests.py`、CI 测试门禁。
- 排除/限制: 未执行测试(AGENTS.md 禁止本地构建,CI 为权威执行者)。

### 测试文件清单(首方)

| 文件 | 类型 | 评估 |
|------|------|------|
| `common/.../MigrationTest.kt` | 单测 | 真实(验证包名/authority/URI 逻辑) |
| `app/.../util/ProfileTest.kt` | 单测 | 真实(验证 DTO 映射) |
| `app/.../log/LogcatCacheTest.kt` | 单测 | 真实 |
| `app/.../log/LogcatParserTest.kt` | 单测 | 真实 |
| `design/.../model/ProfilePageStateTest.kt` | 单测 | 真实 |
| `design/.../util/ClipboardUrlTest.kt` | 单测 | 真实 |
| `design/.../util/DiffTest.kt` | 单测 | 真实 |
| `design/.../util/LogUpdateTest.kt` | 单测 | 真实 |
| `design/.../util/ProxyFilterTest.kt` | 单测 | 真实 |

9 个真实单测(非空壳、非过度 mock),CI 通过 `run-jvm-tests.py` 执行 `testAlphaDebugUnitTest` 并作为发布门禁。这比多数同类项目(通常零测试)已是明显进步(前次 F-14 引入)。但关键路径基本无测试:迁移导入/导出(`MigrationBundle` 的 zip 打包解包、部分失败)、`ProfileProcessor` 非原子写、`AlphaDataMigrator` 状态机、隧道/VpnService 生命周期、配置处理均无覆盖。F-01/F-02/F-06 恰好落在无测试的关键路径上。

## 10. 可维护性分析(Maintainability)

- 覆盖度: High
- 检查证据: 文件大小 top-15、DRY 抽查、`common` 耦合计数、TODO/FIXME 扫描、命名/导入模式抽查。
- 排除/限制: design 模块未逐文件精读。

### 可维护性小结

| 子类型 | 数量 | 受影响区域 | 建议 |
|---------|-------|----------------|------|
| DRY | 1 | extractGeoFiles(F-12) | 抽取辅助函数 |
| 死代码 | 1 | finalize()(F-21) | 删除 |
| 仓库卫生 | 1 | vibe-sessions(F-14) | gitignore |
| 依赖陈旧 | 1 | AndroidX/Room(F-15) | 升级 |

**文件大小纪律优秀**:270 个首方文件中最大仅 421 行(`MetaFeatureSettingsDesign.kt`),无一超过 500 行阈值,远低于同类项目。命名清晰、模块职责分明。扣分项是若干局部问题:extractGeoFiles 的四段重复、finalize 死代码、仓库残留开发产物、依赖陈旧。均为低成本可修。

## 11. 设计/原则合规(Design / Principles)

- 覆盖度: High
- 检查证据: `rubrics/principles.md` 交叉核对、fail-fast/边界验证/CQS/SupervisorJob 抽查。
- 排除/限制: design 模块未逐文件精读。

**遵循良好的原则:** 4.4 fail-fast(签名缺失时构建失败、导入前验签、格式版本校验)、7.1 分层依赖方向、7.2 业务逻辑独立(design 与 service 解耦)、1.2 文件大小、边界安全(zip-slip 防护、PendingIntent immutable)。

**违反的原则见下方合规表。**

## 12. 发布分析(Release)

- 覆盖度: High
- 检查证据: 三个发布工作流(build-release/build-pre-release/build-debug)、`libs.versions.toml`、renovate、wrapper 属性、go.mod/go.sum、fastlane 目录。
- 排除/限制: 未实际触发 CI 运行。

### 发布小结

| 子类型 | 数量 | 受影响面 | 建议 |
|---------|-------|------------------|------|
| 供应链完整性 | 2 | wrapper/依赖(F-07)、子模块(F-08) | 校验和 + 固定 commit |
| 依赖策略 | 1 | 陈旧依赖(F-15) | 合并 renovate |
| 回滚 | 1(见下) | 无显式回滚文档 | 记录回滚流程 |

发布流水线成熟:发布顺序强制为 测试 → Lint → 构建 → SHA256 → 原子推送 tag(`git push --atomic`),版本号从 tag 校验并正则替换,预发布对 Meta(latest)/Alpha(pre-release)并行发布并生成校验和。`verify-repository-policy.py` 断言发布步骤顺序安全。**减分项**:无 Gradle wrapper 校验和(F-07)、无依赖校验元数据(F-07)、子模块浮动分支使核心不可复现(F-08)、无显式回滚/降级文档(用户升级到坏版本后如何回退未记录)。整体 A 级,这些是锦上添花的加固项。

## 13. 文档分析(Documentation)

- 覆盖度: High
- 检查证据: README(16KB)、SECURITY.md、PRIVACY_POLICY.md、CONTRIBUTING.md、AGENTS.md、docs/plans、docs/requirements。

### 文档小结

| 子类型 | 数量 | 受影响文档 | 建议 |
|---------|-------|---------------|------|
| UserDocs | 0 | — | README 构建说明准确 |
| OperatorDocs | 1 | 无回滚 runbook | 补充回滚流程 |
| DeveloperDocs | 0 | — | signing.properties 说明与实际一致 |
| DecisionRecord | 0 | — | docs/ 有 plans/requirements |

文档质量高且准确。README 的构建说明(submodule init、创建 signing.properties、gradlew assemble)与 `build.gradle.kts` 实际逻辑一致(fail-fast 签名)。SECURITY.md 诚实记录了历史 keystore 暴露与轮换责任。PRIVACY_POLICY.md 准确描述本地数据处理与备份边界。唯一缺口是缺少运维回滚 runbook。

## 14. 配置安全分析(Configuration)

- 覆盖度: High
- 检查证据: `build.gradle.kts` 签名逻辑、flavor(alpha/meta)、PREMIUM 标志、signing.properties 校验。

### 配置小结

| 子类型 | 数量 | 受影响键/文件 | 建议 |
|---------|-------|-----------------------|------|
| SchemaValidation | 0 | — | signing.properties 缺项 fail-fast |
| UnsafeDefault | 0 | — | 无静默 debug 签名回退 |
| SecretConfig | 0 | — | 密钥仅经 Secret 注入 |

配置安全良好。`build.gradle.kts` 对 `signing.properties` 做必填项校验,缺失时抛 `GradleException`(fail-fast,原则 9.2);release 构建缺签名时明确失败,无静默 debug 回退(`verify-repository-policy.py` 断言此点)。flavor 分离(alpha/meta)通过 `versionNameSuffix`/`applicationIdSuffix` 实现,无环境检测式业务分支。

## 15. 可观测性分析(Observability)

- 覆盖度: Medium
- 检查证据: 日志模式(`common/log`)、崩溃处理(`AppCrashedActivity`)、遥测/分析 SDK 扫描(无)。
- 排除/限制: 未运行时观察日志量或结构。

### 信号小结

| 子类型 | 数量 | 缺失关键信号 | 建议 |
|---------|-------|--------------------------|------|
| Logging | 1 | FilesProvider 吞错无日志(F-16) | 记录异常 |
| Debuggability | 1 | Go panic 无上下文回传(F-04) | recover 时回传错误 |

应用有自建日志(`Log`)与崩溃展示 Activity(`AppCrashedActivity`),用户可导出诊断日志(隐私政策说明)。无第三方遥测/分析 SDK(符合隐私定位)。可观测性缺口:F-16(吞错无日志)使文件操作故障不可诊断,F-04(Go panic 崩溃无堆栈回传)使核心崩溃难定位。对一个无服务端的本地应用,当前可观测性基本够用。

## 16. 数据完整性分析 (Data Integrity)

- 覆盖度: High
- 检查证据: 迁移/ProfileProcessor 写入原子性、Room 迁移(Migrations.kt/LegacyMigration.kt)、zip 解包、SharedPreferences 合并。

### 完整性小结

| 子类型 | 数量 | 风险中的不变量 | 建议 |
|---------|-------|-------------------|------|
| 写入原子性 | 2 | 配置目录(F-02)、Geo(F-11) | temp-then-rename |
| 事务边界 | 1 | 迁移导入非事务(F-06) | Room withTransaction |
| 幂等/终态 | 1 | 迁移标志(F-01) | 状态机 |
| 无界解压 | 1 | zip(F-09) | 上限 |

数据完整性与稳定性高度重叠,是本次第二集中的维度。核心问题:文件写入不原子(F-02/F-11)、迁移导入非事务性(F-06 部分提交)、迁移终态不可自愈(F-01)。Room 迁移本身(Migrations.kt)结构正常。修复方向统一为临时目录+原子改名与事务化。

## 17. 隐私/数据治理分析(Privacy)

- 覆盖度: High
- 检查证据: PRIVACY_POLICY.md、备份规则(full_backup_content.xml、data_extraction_rules.xml)、`ageSecretKey` 处理、分析 SDK 扫描(无)。

### 隐私小结

| 子类型 | 数量 | 受影响数据 | 建议 |
|---------|-------|---------------|------|
| DataInventory | 0 | — | 隐私政策已分类 |
| Minimization | 0 | — | 无遥测收集 |
| TelemetryPrivacy | 0 | — | 无自动上传 |
| Deletion | 0 | — | 备份已排除敏感数据 |

隐私处理良好。无账号系统、无服务端、无分析 SDK、无自动日志上传。系统备份/设备迁移仅限 `sharedpref`,配置数据库、导入配置、`ageSecretKey` 不进系统备份(前次 F-17 修复,`verify-repository-policy.py` 断言)。隐私政策准确说明本地数据处理与订阅运营方接收的网络信息。唯一残留风险由隐私政策诚实披露:旧版本较宽的备份规则可能已被备份提供方持有,应用更新无法删除,需用户自行清理——这是已披露的历史事实而非缺陷。

## 18. 无障碍/UX 正确性分析(Accessibility)

- 覆盖度: Medium
- 检查证据: 触控区尺寸(`verify-repository-policy.py` 强制 48dp)、无障碍语义(前次 F-05/F-06 修复:代理名称/说明/延迟/选中态语义、图标操作触控区)。
- 排除/限制: 未用辅助技术(TalkBack)实测;完整 WCAG 合规需人工辅助技术测试与专家评审。

### 无障碍小结

| 子类型 | 数量 | 受影响流程 | 建议 |
|---------|-------|-------------------|------|
| SemanticStructure | 0 | — | 前次已加语义(F-05) |
| ResponsiveVisual | 0 | — | 48dp 触控区由 CI 强制(F-06) |

前次审计已处理无障碍(F-05 代理列表语义、F-06 图标操作 48dp 触控区),且 `verify-repository-policy.py` 把 48dp 触控区固化为 CI 门禁。未发现新问题。注意:完整 WCAG 合规验证需借助辅助技术人工测试与专家评审,本静态审计无法替代。

## 19. 供应链/可复现性分析 (Supply Chain)

- 覆盖度: High
- 检查证据: `.gitmodules`、go.sum、wrapper 属性、verification-metadata(无)、自定义 maven 源、CI action 固定情况、renovate。

### 供应链小结

| 子类型 | 数量 | 受影响面 | 建议 |
|---------|-------|------------------|------|
| Reproducibility | 2 | wrapper 校验和(F-07)、子模块分支(F-08) | 固定 |
| DependencyProvenance | 1 | 自定义 maven 源(见下) | 校验元数据 |
| CIIntegrity | 0(部分) | action 固定不一致(见下) | 全部固定到 SHA |

供应链是发布维度的主要加固空间。**问题**:Gradle wrapper 无 SHA256(F-07)、无 `verification-metadata.xml`(F-07)、子模块浮动 Alpha 分支(F-08)、自定义 maven 源 `raw.githubusercontent.com/MetaCubeX/maven-backup`(扩大信任面)。**CI action 固定不一致**:部分固定到 SHA(`setup-go`、`softprops/action-gh-release`),部分用浮动标签(`actions/checkout@v6`、`setup-java@v5`、`peter-evans/create-pull-request@v8`)——建议全部固定到 commit SHA。**好的方面**:go.sum 存在(固定 Go 传递依赖),Go 工具链版本固定(1.26 + 自定义 base URL),`.github/patch` 补丁固定。

## 20. 成本/资源经济性分析(Cost)

- 覆盖度: Medium
- 检查证据: CI 构建矩阵(4 ABI × 2 flavor)、后台更新(ProfileWorker/ProfileReceiver 调度)、无云成本。

### 成本小结

| 子类型 | 数量 | 成本驱动 | 建议 |
|---------|-------|-------------|------|
| CI 成本 | 0(Info) | 构建矩阵 | 可接受 |
| UnboundedWork | 1 | zip 解压(F-09,已列稳定性) | 上限 |

本地移动应用,云成本面极小。主要成本是 CI 构建分钟数(4 ABI × 多 flavor + Go 编译),但对开源项目属正常。配置自动更新有间隔调度(`ExternalControlActivity` 强制 `coerceAtLeast(15L)` 分钟最小间隔,防止过频拉取)。无外部 API/LLM 花费。无需专门优化。

## 21. AI/LLM 安全分析(AI-Safety)

- 覆盖度: Not assessed
- 说明: 项目无 AI/LLM 运行时表面(无提示、检索、模型调用、工具授权)。仓库内的 `outputs/vibe-sessions`、`fuck-my-shit-mountain`、`Install-CodexTrellis.ps1` 是开发时 agent 工具产物,不进入应用运行时,与应用 AI 安全无关。不适用,不评分。

## 22. 回退/防御性代码分析(Fallback)

- 覆盖度: High
- 检查证据: 空 catch、静默回退、SDK 兼容分支扫描。

### 回退小结

| 子类型 | 数量 | KeepWithAlert | FailFast | Remove |
|---------|-------|---------------|----------|--------|
| SilentFallback | 1(F-16) | 1 | 0 | 0 |
| EmptyCatch | 1(F-16) | 1 | 0 | 0 |
| SilentCorrection | 1(F-01) | 0 | 1 | 0 |
| CompatibilityBranch | 0 | — | — | — |

主要回退问题是 F-16(FilesProvider 静默返回空游标掩盖错误,应加告警日志)与 F-01(迁移异常静默标记完成,应 fail-fast 到可重试状态)。SDK 版本兼容分支(`pendingIntentFlags`、`extractGeoFiles` 的 TIRAMISU 检查)是合理的平台适配,非可疑回退。

## 23. 测试真实性分析 (Testing Authenticity)

- 覆盖度: High
- 检查证据: 逐个抽读 9 个测试文件确认非空壳/非过度 mock。

### 置信度评估

| 测试区域 | 真实置信度 | 风险 | 处置 |
|-----------|---------------|------|------|
| MigrationTest | Medium | 只测常量映射,不测 zip 打包/解包/部分失败 | 保留 + 扩展 |
| ProfileTest | Medium | 测 DTO 映射,真实 | 保留 |
| Logcat/Design util 测试 | Medium | 真实单元逻辑测试 | 保留 |

**有价值的测试:** 全部 9 个都是真实的单元逻辑测试(常量映射、DTO 转换、日志解析、diff、代理过滤),无过度 mock、无实现细节断言、无 happy-path 空壳。**可疑测试:** 无。**缺失测试:** 关键路径无覆盖——迁移的 zip 打包解包与部分失败(F-01/F-06)、`ProfileProcessor` 非原子写(F-02)、Geo 解包(F-11)、隧道生命周期。测试真实但覆盖面窄。

## 24. 类型安全分析 (Type Safety)

- 覆盖度: High
- 检查证据: `!!`/`as`/`Profile.Type.valueOf`/`UUID.fromString` 边界扫描。

### 类型安全小结

| 子类型 | 数量 | Critical | High | Medium | Low |
|---------|-------|----------|------|--------|-----|
| InputBoundary | 3 | 0 | 0 | 1(F-06) | 2(F-17,F-19) |
| StringlyTyped | 1 | 0 | 0 | 0 | 1(F-18) |

边界类型解析是主要类型安全风险:`Profile.Type.valueOf`(F-06,未知枚举中止导入)、`UUID.fromString`(F-17 广播 extra、F-19 存储值)未包 `runCatching`,坏输入抛异常。`getString(...)!!`(F-18)在类型不匹配时 NPE。均为边界处 fail-fast 缺失,修复方向统一为防御式 `runCatching`。design 模块的 view-binding `!!` 是约定俗成的低风险用法,不计入。

## 25. 前端状态分析 (Frontend State)

- 覆盖度: Medium
- 检查证据: design 模块 adapter/design 类抽查(ProxyPageAdapter、ProxyDesign、各 SettingsDesign)。
- 排除/限制: 未逐一精读 104 个 design 文件。

### 前端状态小结

| 子类型 | 数量 | 受影响组件 |
|---------|-------|-------------------|
| ComponentSize | 0 | 最大 design 文件 421 行,可控 |
| StateDuplication | 0 | — |
| UIBusinessCoupling | 0 | design 与 service 已解耦 |

Android 传统 View + DataBinding 架构(非声明式)。design 组件大小可控(最大 421 行),与 service 通过 DTO 解耦。前次审计(F-05/F-08/F-09)已处理列表刷新性能(payload 局部刷新、single-flight、图标懒加载 LRU)。未发现新的状态重复或 UI-业务耦合问题。覆盖度中等(未逐文件精读)。

## 26. 后端 API 分析 (Backend API)

- 覆盖度: Not assessed
- 说明: 项目为纯本地 Android 应用 + 嵌入式代理核心,无自建服务端 HTTP API。跨进程通信通过 AIDL(kaidl)/ContentProvider/广播,已在架构/安全维度覆盖。不适用,不评分。

## 27. 依赖权重分析 (Dependency Weight)

- 覆盖度: High
- 检查证据: `libs.versions.toml` 全量、`app/build.gradle.kts` 依赖、go.mod。

### 依赖记分板

| 依赖 | 状态 | 用途 | 建议 |
|------------|--------|----------|------|
| kotlinx-coroutines | Healthy | 并发 | 保留 |
| AndroidX core/appcompat/fragment/activity | Overweight(陈旧) | UI 基础 | 升级(F-15) |
| Room 2.4.2 | Overweight(陈旧) | 持久化 | 升级(F-15) |
| material 1.6.1 | Overweight(陈旧) | UI 组件 | 升级(F-15) |
| quickie-bundled | Healthy | 二维码扫描 | 保留 |
| kaidl | Healthy | AIDL 生成 | 保留(自维护补丁) |
| rikkax-multiprocess | Healthy | 多进程偏好 | 保留 |

依赖集精简合理,无明显冗余或未使用依赖。主要问题是 AndroidX/Room/material 版本陈旧(F-15,约 2022 年)。`kaidl` 用 `includeBuild("kaidl-compiler-patch")` 打了自维护补丁,属可接受的按需定制。Go 依赖由 mihomo 核心间接引入,go.sum 固定。

## 27b. 代码一致性分析 (Code Consistency)

- 覆盖度: High
- 检查证据: 命名约定、导入组织、错误处理模式跨模块抽查、文件结构。
- 排除/限制: 未逐文件精读全部 270 个文件。

### 一致性小结

| 子类型 | 数量 | 建议 |
|---------|-------|------|
| Naming | 0 | 命名一致(PascalCase 类、camelCase 成员) |
| ErrorHandling | 1(见下) | 错误处理模式不完全统一 |
| PatternUniformity | 0 | 协程/DAO/Design 模式一致 |

代码一致性良好。命名约定统一(类 PascalCase、成员 camelCase、常量 UPPER_SNAKE),包结构规整(`design`/`service`/`common` 按职责分层),协程用法、DAO 访问、Design 组件模式跨模块一致。唯一不一致点是错误处理:多数路径用 `runCatching`/结构化 catch,但少数边界(F-17/F-18/F-19)直接 `!!`/`fromString` 无守卫——属局部不一致而非系统性问题,已在类型安全维度列出。未发现需专门整改的一致性问题。

## 28. 注释覆盖分析 (Comment Coverage)

- 覆盖度: Medium
- 检查证据: TODO/FIXME/HACK 扫描、公共 API KDoc 抽查、关键模块内联注释质量。
- 排除/限制: 未逐文件统计注释密度。

### 注释小结

| 子类型 | 数量 | 建议 |
|---------|-------|------|
| StaleDocs | 0 | 未见误导性注释 |
| ModuleDocs | 0(Info) | 迁移/安全边界注释充分 |

注释质量良好且克制。关键安全/迁移逻辑有解释性注释(如 zip-slip 防护、跨签名迁移、Geo 任务依赖、Android 17+ loopback 权限说明)。未发现过时/误导注释或过度注释。公共 API 缺 KDoc 但对一个应用(非库)项目属可接受。无 TODO/FIXME/HACK 堆积。

---

## 29. 原则合规(Principles Compliance)

本代码库整体原则合规度高。文件大小、分层依赖、fail-fast(签名/配置校验)、边界安全(zip-slip、PendingIntent)做得尤其好。违规集中在错误处理与写入原子性。

### 违反的原则

| 原则 | 违规数 | 严重度 | 受影响区域 |
|-----------|------------|----------|----------------|
| 4.4 Fail-Fast(错误终态/边界验证) | 3 | High/Medium | 迁移放弃(F-01)、导入中止(F-06)、deep-link scheme(F-22) |
| 写入原子性(非编号,数据完整性) | 2 | High/Medium | 配置目录(F-02)、Geo(F-11) |
| 5.4 共享可变状态无同步 | 1 | Medium | ProfileWorker.jobs(F-10) |
| 6.1 不吞错 | 2 | Medium/Low | 迁移 catch(F-01)、FilesProvider(F-16) |
| 6.3 处理所有分支 | 1 | Medium | 迁移枚举(F-06) |
| 10.x 不阻塞 binder/无界资源 | 2 | Medium | runBlocking 打包(F-05)、无界 zip(F-09) |
| 4.1 DRY | 1 | Low | extractGeoFiles(F-12) |
| 边界类型安全 | 3 | Low/Medium | UUID/枚举/强解包(F-06/F-17/F-18/F-19) |
| 协程异常隔离(SupervisorJob) | 1 | Low | Global(F-13) |
| 3.1 最小惊讶 | 1 | Low | finalize()(F-21) |

### 遵循良好的原则

- **1.2 文件大小**:最大首方文件 421 行,无一超 500 行——同类项目罕见的纪律。
- **7.1 分层依赖方向 / 7.2 业务逻辑独立**:依赖由外向内,design 与 service 通过 DTO 解耦。
- **4.4 Fail-Fast(配置)**:签名/配置缺失时构建失败,无静默 debug 回退。
- **边界安全**:zip-slip canonical 路径防护、PendingIntent `FLAG_IMMUTABLE`、跨包迁移双重验签。
- **9.2 缺配置失败 / 9.3 环境分离**:flavor 通过后缀而非业务分支实现,无 `if(isDev)` 式分支。

---

## 30. 建议修复顺序 (Recommended Fix Order)

### 立即修复(数据丢失/服务中断风险)

| 编号 | 标题 | 严重度 | 工作量 |
|------|------|--------|--------|
| F-01 | Alpha 迁移异常永久放弃 → 改为可重试状态机 | High | 1-2h |
| F-02 | 配置目录非原子写 → temp-then-rename | High | 3-5h |
| F-04 | Go goroutine panic 崩溃进程 → recover/返回 error | Medium | 2-3h |
| F-11 | Geo 解包非原子 → temp-then-rename | Medium | 2h |

### 稳定版发布前修复(降低可靠性/正确性/安全风险)

| 编号 | 标题 | 严重度 | 工作量 |
|------|------|--------|--------|
| F-03 | network_security_config 信任 user CA | Medium | 1-2h |
| F-06 | 迁移导入中止 → 逐条 runCatching + 事务 | Medium | 2-4h |
| F-05 | MigrationProvider binder 阻塞 → withTimeout | Medium | 2-4h |
| F-07 | wrapper 校验和 + 依赖校验元数据 | Medium | 2-4h |
| F-08 | 子模块固定 commit | Medium | 1-2h |
| F-09 | unzip 加大小/条目上限 | Medium | 1-2h |
| F-10 | ProfileWorker.jobs 线程安全 | Medium | 2-3h |

### 稍后安排(增加可维护性/降低偶发风险)

| 编号 | 标题 | 严重度 | 工作量 |
|------|------|--------|--------|
| F-12 | extractGeoFiles DRY + 主线程/后台 | Low | 1-2h |
| F-13 | Global 改 SupervisorJob | Low | 30m |
| F-15 | 升级陈旧 AndroidX/Room 依赖 | Low | 2-4h |
| F-16 | FilesProvider 吞错加日志 | Low | 30m |
| F-17 | ConfigurationModule UUID 防御式解析 | Low | 30m |
| F-18 | Providers 去掉 `!!` | Low | 30m |
| F-19 | activeProfile 防御式解析 | Low | 30m |
| F-22 | deep-link url scheme 白名单 | Low | 1h |
| CI action | 全部固定到 commit SHA | Low | 1h |

### 暂时忽略(低风险/理论/已缓解)

| 编号 | 标题 | 理由 |
|------|------|------|
| F-14 | vibe-sessions 入库 | 15m 顺手清理即可 |
| F-20 | MIGRATE_DATA 权限名硬编码 | 代码验签已兜底 |
| F-21 | finalize() 死代码 | 无害,顺手删 |
| F-23 | 本地 keystore 文件 | 已妥善治理,仅记录 |

## 31. Quick Wins(1-2 小时内、去除真实风险)

- **F-01(1-2h)**:仅对不可重试结果置 `KEY_COMPLETED`,异常时保持可重试——消除"一次瞬时错误永久丢配置"的高危路径,投入产出比最高。
- **F-11(2h)+ F-12(1-2h)**:extractGeoFiles 同时改原子写 + 抽取辅助函数 + 移主进程,一次解决截断不自愈与主线程 I/O。
- **F-13(30m)**:Global 改 `SupervisorJob() + CoroutineExceptionHandler`——30 分钟消除全局协程连坐取消隐患。
- **F-16/F-17/F-18/F-19(各 30m)**:一组防御式解析/日志修复,累计约 2h,消除多个边界崩溃点。
- **F-14(15m)+ F-21(15m)**:清理仓库残留产物与死代码,发布前卫生。
- **F-08(1-2h)**:子模块固定 commit——低成本换取发布可复现性。

## 32. 长期重构计划

本代码库**不需要**结构性重写。架构、模块边界、发布流水线均健康。若要系统性提升,建议两项有界改进:

1. **统一"原子文件操作"工具**
   - 动机:F-02、F-11、以及迁移导入的目录复制都重复"删除-写入"的非原子模式。
   - 方法:抽取 `atomicReplaceDir(target, build: (tmp) -> Unit)` 与 `atomicWriteFile(target, write)` 工具(临时路径 + 完成后 `renameTo`/`ATOMIC_MOVE`),统一替换 ProfileProcessor、MainApplication、MigrationBundle 的写入点。
   - 风险:低(纯封装,行为等价);需覆盖 `renameTo` 失败的降级路径。
   - 测试策略:模拟各阶段中断,断言目标永远处于"旧完整"或"新完整"二态之一。

2. **迁移导入事务化 + 关键路径测试**
   - 动机:F-01/F-06 落在无测试的迁移关键路径;部分提交 + 终态标志放大不一致。
   - 方法:用 Room `withTransaction` 包裹 DB 写入,配文件原子操作;引入区分"永久跳过/可重试/成功"的状态机;补充迁移 zip 打包-解包-部分失败的单测/插桩测试。
   - 风险:中(触及数据写入路径);需 CI 测试门禁把关。
   - 测试策略:合成含合法+坏记录的 bundle、注入中途异常,断言原子性与可重试性。

---

*报告依据 fuck-my-shit-mountain skill 的 full 模式模板生成。评分为基于证据的整体判断,非机械扣分。方向:10.0 = 最佳(干净),0.0 = 最差(屎山)。*
*覆盖说明:本次审计为静态代码审查,未运行时执行(遵守 AGENTS.md 本地构建限制)。标记 Suspected 的发现为控制流推理,严重度取决于真实运行时条件。*
