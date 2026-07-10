# Fuck My Shit Mountain Audit Report

**Project:** ClashMetaForAndroid  
**Audit mode:** full  
**Date:** 2026-07-10  
**Reviewer:** Codex GPT-5

---

## 1. Executive Summary

ClashMetaForAndroid 的模块划分表面上较清楚：`app` 负责 Activity 与用户流程，`design` 负责传统 Android View/Data Binding UI，`service` 负责后台进程、配置与 Room 持久化，`core` 封装 JNI/Go 内核，`common` 提供跨模块工具。问题主要不在“完全无法维护”，而在若干直接命中真实用户的热路径和状态边界：测速界面以 100ms 周期重复拉取整组代理、计算移动 Diff、在主线程重建状态并触发动画；日志文件在主线程整文件解析且无大小上限；流式日志的 RecyclerView 更新区间计算存在确定性错误。这些问题会表现为测速时掉帧和耗电、打开大日志时长时间白屏或 ANR、实时日志列表异常甚至崩溃。

UI/UX 方面存在系统性而非零散问题。20 个图标按钮直接使用 30dp 点击区域，低于常见 48dp 触控目标；代理卡片完全自绘，却没有向无障碍服务暴露代理名、延迟、选中状态或可操作语义；配置删除立即递归删除数据库与文件，没有确认或撤销；“更新全部”只旋转图标但不禁用入口，重复点击会进入无上限请求队列。首屏通知权限也在缺少用途说明时立即请求，拒绝结果被忽略。这些设计会让视力、运动能力受限用户以及普通用户在高频操作下都更容易误触、迷失状态或重复提交。

安全与发布成熟度同样偏弱：导出的外部控制 Activity 允许任意已安装应用发送 START/STOP/TOGGLE Intent，从而停止 VPN；仓库历史中跟踪了实际被发布签名配置引用的 `release.keystore`；构建会从可变的 `latest` URL 下载地理数据且不校验摘要；正式发布工作流在构建前就修改并推送版本与 tag。仓库未发现任何单元、集成或 UI 测试，CI 只执行 assemble，导致上述回归缺少自动保护。当前状态不适合在不修复高风险项的前提下直接视为稳定发布就绪。

### Score Dashboard

```text
Security        █████░░░░░  4.6  C   外部 Intent 可无授权停止 VPN，且发布签名材料被版本库跟踪；静态覆盖为 Medium。
Stability       █████░░░░░  4.8  C   流式日志更新区间确定性错误、退出等待无超时、日志解析脆弱；未做运行复现，覆盖为 Medium。
Performance     ████░░░░░░  4.2  C   测速 10Hz 全量刷新、主线程整文件日志解析、图标全量常驻与日志无界增长形成多处真实瓶颈；覆盖为 Medium。
Testing         █░░░░░░░░░  1.2  D   未发现任何测试源文件，CI 只构建不测试，关键用户流程没有回归保护；覆盖为 High。
Maintainability ██████░░░░  5.8  B   模块名义边界清楚，但 UI 直接依赖 service 存储模型、无上限事件通道和手工状态同步扩大修改风险；覆盖为 Medium。
Design          ████░░░░░░  4.3  C   触控目标、无障碍语义、破坏性操作与加载状态存在系统性 UX 缺陷；覆盖为 Medium。
Release         ████░░░░░░  3.6  C   可变外部资产、构建前推送 tag、签名回退和缺失测试门禁共同削弱可复现与回滚能力；覆盖为 Medium。
─────────────────────────────────────
Overall         ████░░░░░░  4.1  C
```

每个维度按 0.0–10.0 计分，**越高越好**。分数是基于证据的工程判断，不是机械扣分。

### Finding Statistics

| Severity | Count | Confirmed | Suspected |
|----------|-------|-----------|-----------|
| Critical | 0 | 0 | 0 |
| High | 7 | 7 | 0 |
| Medium | 10 | 10 | 0 |
| Low | 1 | 1 | 0 |
| Info | 0 | 0 | 0 |
| **Total** | **18** | **18** | **0** |

## 2. Project Map

| Component | Responsibility | Important boundaries |
|-----------|----------------|----------------------|
| `app` | Activity、入口 Intent、前台用户流程、日志查看与导出 | UI 请求通过 `Design.requests` 进入 Activity 的 `select` 循环，再调用远程 service/core |
| `design` | XML/Data Binding、RecyclerView、对话框、自定义 View、UI store | 直接依赖 `service` 的 `Profile`、`ServiceStore`，边界不完全独立 |
| `service` | VPN/Clash 后台进程、Profile 管理、Room、DocumentsProvider、广播 | 处理持久化、文件、定时更新和内核生命周期 |
| `core` | Kotlin 到 JNI/Go 内核桥接、代理/流量/配置模型 | 通过 native bridge 返回 JSON/Parcelable 数据 |
| `common` | Intent、兼容层、日志、协程 ticker 等共享工具 | 被所有上层模块使用 |
| `hideapi` | Android 隐藏 API 编译接口 | 仅 compileOnly 使用 |

主要运行链路为：用户点击 XML/View → `Design.requests` 无上限 Channel → Activity 主协程 `select` → AIDL/远程 service → Room/文件/native core → 广播或查询结果 → Data Binding/RecyclerView 更新。UI 状态分散在 Activity 局部变量、`UiStore`、`ServiceStore`、Room、`Remote.broadcasts` 和各 Adapter state 中，缺少统一的请求进行中/失败状态模型。

持久化包含 Room 数据库、`filesDir/imported`、`filesDir/pending`、Clash override、SharedPreferences 和 cache 日志。Android Auto Backup 明确包含全部 SharedPreferences、数据库、导入配置和 pending 配置。外部接口包括 BROWSABLE deep link、公开 START/STOP/TOGGLE Intent、DocumentsProvider、VPN service、Quick Settings Tile、剪贴板导入导出和外部配置提供者。

覆盖说明：审计基于提交 `faf2022` 的静态代码与 Git/CI 元数据。执行了文件清单、模块/扩展名统计、关键模式搜索、XML 可点击控件解析、Git 跟踪状态检查和逐文件控制流追踪。遵照仓库约束，没有运行 Flutter、Gradle、Android 构建、项目测试、基准测试或设备 UI 测试；未读取 keystore 内容，也未输出任何密钥值。排除了 `.git` 内部对象、构建产物、二进制资产内容、生成代码，以及 `core/src/foss/golang/clash` 上游子模块的逐行审计。

### Coverage Matrix

| Dimension | Coverage | Evidence inspected | Exclusions / limits |
|-----------|----------|--------------------|---------------------|
| Architecture | Medium | Gradle 模块依赖、Activity→Design→Service 数据流、store/Room/native 边界 | 未生成完整依赖图，未逐行审计上游 Go 子模块 |
| Security | Medium | Manifest exported 组件、Intent、签名配置、备份、网络安全配置、敏感文件 Git 元数据 | 未做动态攻击验证和依赖 CVE 扫描 |
| Stability | Medium | 协程循环、Channel、日志、退出流程、Profile 文件操作、异常路径 | 未在设备上复现崩溃、进程死亡或 Binder 故障 |
| Performance | Medium | 代理测速、RecyclerView、日志 I/O、应用列表、轮询与内存保留 | 未运行 profiler、Macrobenchmark 或低端设备测试 |
| Testing | High | 全仓测试文件清单、全部 GitHub Actions 构建步骤 | 没有测试可运行；未触发 GitHub workflow |
| Maintainability | Medium | 文件规模、模块依赖、状态通道、Adapter/UI 分层、热点文件 | 未做历史 churn 和完整复杂度度量 |
| Design | Medium | 破坏性操作、加载态、触控目标、导航与状态反馈 | 未进行用户访谈或可用性实验 |
| Release | Medium | 三个构建/发布 workflow、版本、签名、产物、外部下载 | 未实际执行发布和回滚演练 |
| Documentation | Medium | README、Privacy Policy、Manifest/行为对照 | 未验证所有语言翻译和外部发布页 |
| Configuration | Medium | Gradle 配置、SharedPreferences store、Manifest、备份与签名默认值 | 未枚举所有运行时配置组合 |
| Observability | Medium | 应用日志、Logcat service/cache/writer、错误 Snackbar/Toast | 未检查真实日志样本和线上告警体系 |
| Data Integrity | Medium | Room 模型、ProfileProcessor 锁、删除/复制、迁移与备份 | 未做中断写入、恢复和迁移实测 |
| Privacy | Medium | QUERY_ALL_PACKAGES、剪贴板、备份内容、Privacy Policy、日志 | 未做数据流抓包或云备份恢复验证 |
| Accessibility | Medium | 95 个 design Kotlin 文件、全部 layout XML、可点击控件尺寸与语义 | 未运行 TalkBack、Switch Access、字体缩放或横屏设备测试 |
| Supply Chain | Medium | workflow action pinning、submodule、Go 更新、Geo 下载、签名产物 | 未生成 SBOM、依赖树或验证远端资产 |
| Cost | Medium | 轮询、日志存储、图标内存、后台 service | 移动端无云账单；未测量电量、CPU、磁盘速率 |
| AI / LLM Safety | Not assessed | 仓库未发现模型调用、RAG、prompt 或工具执行面 | 项目不包含 AI/LLM 功能 |
| Fallback | Medium | debug signing 回退、异常 catch、默认分支、无超时等待 | 未触发所有兼容性分支 |
| Testing Authenticity | High | 测试目录/文件与 CI 命令清单 | 仓库没有测试，因此不存在可评估的测试真实性 |
| Type Safety | Medium | `!!`、索引、字符串解析、Data Binding 表达式、外部 Intent 参数 | 未运行静态分析器或 Kotlin 编译器 |
| Frontend State | Medium | Design/Activity request channel、加载态、Adapter state、轮询刷新 | 未抓取真实生命周期竞态 |
| Backend API | Not assessed | 项目是本地 Android 客户端/服务，不提供常规 HTTP 后端 API | native core 外部协议不属于此维度 |
| Dependency Weight | Low | 直接 Gradle 依赖、Go module 清单、QR 扫描依赖 | 禁止运行 Gradle，无法得到 transitive tree/包体贡献 |
| Code Consistency | Medium | Kotlin/XML 命名、错误处理、toolbar 控件、Adapter 更新模式 | 未执行 ktlint/detekt/Android Lint |
| Comment Coverage | Low | 关键复杂路径、README、Manifest 注释、TODO/注释搜索 | 未逐个公共 API 评估文档覆盖 |

## 3. Top Risks

| Rank | Finding | Severity | Summary |
|------|---------|----------|---------|
| 1 | F-02 流式日志更新区间错误 | High | RecyclerView 收到与真实数据集不一致的插入/删除通知，实时日志可能异常或崩溃 |
| 2 | F-03 日志文件在主线程整文件解析 | High | 大日志会在界面显示前阻塞主线程并把全部消息常驻内存 |
| 3 | F-12 任意应用可停止或切换 VPN | High | exported Activity 无权限/用户授权检查即可执行 START/STOP/TOGGLE |
| 4 | F-13 发布签名链路不安全 | High | keystore 被 Git 跟踪，release 缺配置时还会静默回退 debug key |
| 5 | F-15 构建下载可变且未校验的 Geo 资产 | High | `latest` 内容可变化或被替换，直接进入 APK，构建不可复现 |
| 6 | F-16 发布前先推送版本与 tag | High | 构建失败也可能留下已推送版本提交/tag，形成假发布状态 |
| 7 | F-14 零测试与零测试门禁 | High | 关键 UI、持久化、Intent 和发布逻辑没有自动回归保护 |
| 8 | F-01 测速 100ms 全量刷新链路 | Medium | 每秒约 10 次跨进程查询、Diff、主线程分配、移动动画和重绘 |
| 9 | F-07 配置删除无确认/撤销 | Medium | 一次误触即可递归删除数据库记录和配置文件 |
| 10 | F-04 日志文件无轮转和上限 | Medium | 长时间记录持续占用 cache 磁盘，最终影响设备空间与后续打开性能 |
| 11 | F-05 代理卡片没有无障碍语义 | Medium | TalkBack 无法可靠读取代理名、延迟与选中态，长名称还会无提示截断 |
| 12 | F-06 20 个图标按钮只有 30dp | Medium | 工具栏和列表操作目标过小，误触率高，且同步按钮被错误朗读为“新建” |

## 4. Detailed Findings

### Finding: [F-01] 代理测速以 100ms 周期执行整组查询、Diff、动画和重绘

- Severity: Medium
- Confidence: High
- Category: Performance
- Status: Confirmed
- Affected area: 代理组测速与渐进式延迟结果刷新
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/ProxyActivity.kt:105-152`
  - Function / Module: `ProxyActivity.main` / `reloadGroup`
  - Relevant behavior: 测速期间子协程每 100ms 调用 `queryProxyGroup` 拉取完整代理组；`ProxyPageAdapter.updateAdapter` 随后为整组计算 `detectMove = true` 的 Diff，在主线程创建 `associateBy`/`map` 状态集合、派发移动更新，并对可见子 View 全量 `invalidateChildren`；ItemAnimator 移动时长为 220ms，长于刷新周期。
- Problem: 一次健康检查被实现为高频全量轮询和全列表更新，而不是只消费变化的延迟结果。刷新周期短于动画周期，移动动画与下一轮 Diff 会重叠；排序按延迟变化时，大量 item 会持续移动。
- Why it matters: 这是用户主动触发且可见的热路径，会同时消耗 Binder/native 查询、CPU、主线程分配、RecyclerView 布局和 GPU 绘制预算。代理数越多、设备越弱，掉帧、触摸延迟、发热和电量消耗越明显。
- Realistic failure scenario: 用户在含 100–300 个节点的组中点击“延迟测试”；测试持续数秒，页面每秒约更新 10 次，节点顺序不断变化，220ms 动画尚未结束下一次移动又开始，导致明显卡顿和列表抖动。
- Minimal fix: 将刷新周期提高到 300–500ms；只在数据实际变化时更新；测速期间关闭 move animation 或固定排序；把 `oldStatesByName`/新状态构造也放到后台并用 payload 只更新延迟文本。
- Better long-term fix: 让 core/service 暴露增量测速事件流，以代理名和延迟为 payload；UI 使用稳定顺序与节流后的批量提交，测速结束后再执行一次排序 Diff。
- Regression test suggestion: 在 GitHub workflow 中增加 Macrobenchmark/基准测试，构造 300 节点并持续 10 秒更新，断言主线程帧超时比例、每秒 adapter 更新次数和排序动画次数受限。
- Estimated effort: 1–2 days

### Finding: [F-02] 流式日志 RecyclerView 的插入区间与实际数据集不一致

- Severity: High
- Confidence: High
- Category: Stability
- Status: Confirmed
- Affected area: 实时日志列表
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/log/LogcatCache.kt:32-45`; `design/src/main/java/com/github/kr328/clash/design/LogcatDesign.kt:38-47`
  - Function / Module: `LogcatCache.snapshot` / `LogcatDesign.patchMessages`
  - Relevant behavior: full snapshot 将 `appended` 计算为 `array.size() + appended`；UI 先把 adapter 数据替换为新列表，再以新列表长度 `adapter.messages.size` 作为插入起点调用 `notifyItemRangeInserted`，随后才通知头部删除。
- Problem: RecyclerView 的增量通知不描述真实的旧列表→新列表变化。即使 full snapshot 没有双计数，插入起点也应是新列表尾部新增段的起点，而不是新列表长度之后；有滚动淘汰时通知顺序同样不稳定。
- Why it matters: RecyclerView 依赖通知和 `getItemCount()` 一致。错误区间会造成新增日志不显示、滚动位置跳动、`IndexOutOfBoundsException` 或 “Inconsistency detected” 崩溃。
- Realistic failure scenario: 用户打开实时日志，500ms 后收到若干日志；snapshot 返回新列表和 appended 数，UI 把列表替换后通知在列表末尾之后插入相同数量，RecyclerView 在下一次布局/预取时访问不存在的位置并崩溃。
- Minimal fix: 在替换数据前记录 old size，先按旧数据通知删除，再以 `newSize - appended` 为插入起点；full snapshot 直接使用 `array.size()`，不要再加累计 appended。更安全的局部修复是直接用 DiffUtil。
- Better long-term fix: 让 snapshot 返回明确的 `oldSize/newSize/removedFromHead/appendedItems`，并用单一 adapter 更新函数原子应用；对 128 条小列表可直接 `ListAdapter.submitList`。
- Regression test suggestion: 增加纯 JVM 测试覆盖空→N、N→N+M、满容量头删尾增、full snapshot 四种序列，并用 RecyclerView adapter observer 断言通知区间合法且最终 itemCount 一致。
- Estimated effort: 2–4 hours

### Finding: [F-03] 打开本地日志会在主线程整文件解析并无界保留全部消息

- Severity: High
- Confidence: High
- Category: Performance
- Status: Confirmed
- Affected area: 历史日志查看
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/BaseActivity.kt:98-100`; `app/src/main/java/com/github/kr328/clash/LogcatActivity.kt:48-60`; `app/src/main/java/com/github/kr328/clash/log/LogcatReader.kt:18-42`
  - Function / Module: `BaseActivity.onCreate` / `LogcatActivity.mainLocalFile` / `LogcatReader.readAll`
  - Relevant behavior: `main()` 在 MainScope 启动；`mainLocalFile` 在任何 `Dispatchers.IO` 切换前直接执行 `readAll()`；reader 对每行 trim/split/对象化并最终 `toList()`，随后整列表交给 RecyclerView。解析还假设数字开头行一定有三个字段并且日志级别枚举合法。
- Problem: 磁盘读取、字符串拆分和对象分配全部阻塞 UI 线程，且文件大小没有上限或分页。损坏/截断行会使整个文件读取失败，而不是跳过单行并展示其余内容。
- Why it matters: 大文件会造成首屏长时间空白、ANR 和高峰内存；日志本应是排障工具，却可能在最需要时让应用本身失去响应。
- Realistic failure scenario: 用户后台记录数小时后打开数十 MB 日志；Activity 在显示布局前同步解析几十万行并创建同数量对象，主线程超过 5 秒无响应，低内存设备还可能直接 OOM。
- Minimal fix: 在 `Dispatchers.IO` 读取；限制最大读取条数/字节数；按页或流式加载；对字段数、时间戳和 level 使用安全解析并保留“损坏行”占位。
- Better long-term fix: 使用分页数据源或按块索引日志文件，只加载当前可视窗口附近的数据，并提供搜索/导出而不是一次性物化全部日志。
- Regression test suggestion: 在 GitHub workflow 生成 50MB、尾行截断、未知 level 的日志 fixture；断言 Activity 数据加载不在 Main dispatcher、内存有上限、有效行仍可展示。
- Estimated effort: 1–3 days

### Finding: [F-04] 日志记录没有文件轮转、大小上限或自动停止策略

- Severity: Medium
- Confidence: High
- Category: Performance
- Status: Confirmed
- Affected area: Logcat foreground service 与 cache 日志目录
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/LogcatService.kt:90-125`; `app/src/main/java/com/github/kr328/clash/log/LogcatWriter.kt:10-24`; `app/src/main/java/com/github/kr328/clash/LogcatActivity.kt:94-137`
  - Function / Module: `LogcatService.startObserver` / `LogcatWriter.appendMessage`
  - Relevant behavior: 服务启动后持续把每条日志追加到单个文件；writer 没有字节/行数限制、轮转或定期 flush 策略；离开 Activity 只 unbind，不会停止前台记录服务，只有显式“Close”请求才停止。
- Problem: 内存 cache 虽限制为 128 条，但磁盘文件不受限，用户返回或切走页面后记录仍可继续。文件越大，F-03 的打开成本越高。
- Why it matters: 长时间运行会持续消耗磁盘与写入功耗，cache 分区空间不足时还可能影响其他应用功能和系统稳定性。
- Realistic failure scenario: 用户为排障开启日志后按系统返回键离开，忽略通知数小时或数天；高日志量持续写入，文件增长到数百 MB，之后打开日志卡死或设备存储告急。
- Minimal fix: 增加单文件上限、总目录配额和轮转数量；Activity 离开时明确询问是否继续后台记录；通知中显示当前大小并提供停止动作。
- Better long-term fix: 将日志记录建模为有生命周期、配额和保留策略的 session，支持按时间/大小轮转、自动清理和导出后删除。
- Regression test suggestion: 用 fake writer 写入超过配额的数据，断言触发轮转、旧文件清理和总目录大小上限；增加返回键行为 UI 测试。
- Estimated effort: 1–2 days

### Finding: [F-05] 自绘代理卡片没有可访问名称、状态和完整文本

- Severity: Medium
- Confidence: High
- Category: Design
- Status: Confirmed
- Affected area: 代理选择与延迟查看
- Evidence:
  - File: `design/src/main/java/com/github/kr328/clash/design/adapter/ProxyAdapter.kt:18-38`; `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt:11-196`; `design/src/main/java/com/github/kr328/clash/design/component/ProxyViewState.kt:59-169`
  - Function / Module: `ProxyAdapter.onBindViewHolder` / `ProxyView.onDraw`
  - Relevant behavior: 卡片名称、副标题、延迟和选中颜色全部通过 Canvas 绘制；绑定时只设置 `isFocusable`、`isClickable` 和 click listener，没有 `contentDescription`、`stateDescription`、`isSelected`、AccessibilityDelegate 或自定义 node info；长文本通过 `breakText` 静默截断且没有省略号/详情入口。
- Problem: 屏幕阅读器看不到 Canvas 文本和颜色语义，无法知道当前代理、延迟单位或是否已选中；相似长名称在三列布局中可能被截成相同前缀。
- Why it matters: 代理选择是核心流程。无障碍用户可能只能听到“未标记按钮”，普通用户也会因截断和纯颜色状态难以确认选择。
- Realistic failure scenario: TalkBack 用户在代理组中逐项导航，焦点落在可点击 View 上但没有可读名称/状态；用户无法判断哪个节点延迟最低或当前已选节点，误选后也没有语音确认。
- Minimal fix: 绑定时生成本地化 `contentDescription`，包含完整名称、类型、延迟毫秒和“已选择”；同步设置 `isSelected/stateDescription`，为不可选项提供正确角色；长按或辅助文本显示完整名称。
- Better long-term fix: 使用语义化子 TextView 组合或完整实现 `onInitializeAccessibilityNodeInfo`，把名称、延迟和选择动作作为可访问节点属性，而不是依赖 Canvas 颜色。
- Regression test suggestion: 在 instrumentation workflow 启用 AccessibilityChecks，断言每个 ProxyView 有非空名称、选中态和 click action；增加三列长名称和 200% 字体缩放截图测试。
- Estimated effort: 4–8 hours

### Finding: [F-06] 多个核心图标按钮只有 30dp，且“更新全部”被错误标注为“新建”

- Severity: Medium
- Confidence: High
- Category: Design
- Status: Confirmed
- Affected area: 全局工具栏、日志、配置、代理、搜索和编辑操作
- Evidence:
  - File: `design/src/main/res/values/dimens.xml:38-42`; `design/src/main/res/layout/design_profiles.xml:49-79`; `design/src/main/res/layout/design_proxy.xml:63-95`
  - Function / Module: `item_header_component_size` / `item_tailing_component_size` 与 20 个 clickable ImageView
  - Relevant behavior: 两个通用组件尺寸均为 30dp；静态 XML 解析确认 20 个 clickable/focusable 图标直接使用该宽高。Profiles 的同步图标 `update_view` 使用 `@string/_new` 作为 contentDescription，而不是“更新全部”。
- Problem: 可点击区域小于常见 Android 48dp 最小触控目标，图标之间又使用较小边距；无障碍名称错误使 TalkBack 用户执行与朗读不一致的动作。
- Why it matters: 这不是单个页面的小瑕疵，而是复用尺寸变量导致的全局误触风险，尤其影响单手操作、震颤用户和大屏/车载遥控输入。
- Realistic failure scenario: 用户尝试点击代理测速旁的更多菜单或日志删除/导出图标，因 30dp 命中区点到相邻动作；TalkBack 在 Profiles 页朗读“新建”，用户激活后却开始更新所有订阅。
- Minimal fix: 保持视觉图标 24–30dp，但使用至少 48dp 的 ImageButton/父容器作为可点击区域；修正 `update_view` 的 contentDescription；检查相邻间距。
- Better long-term fix: 建立统一 ToolbarAction 组件和静态检查规则，集中约束最小触控尺寸、role、focus、tooltip 和 contentDescription。
- Regression test suggestion: 增加 XML/Android Lint gate，扫描所有 clickable View 的有效 touch target；instrumentation 测试验证同步按钮朗读文本为“更新全部”。
- Estimated effort: 1 day

### Finding: [F-07] 删除配置没有确认或撤销，随后永久递归删除文件

- Severity: Medium
- Confidence: High
- Category: Stability
- Status: Confirmed
- Affected area: Profiles 配置管理
- Evidence:
  - File: `design/src/main/res/layout/dialog_profiles_menu.xml:50-57`; `design/src/main/java/com/github/kr328/clash/design/ProfilesDesign.kt:132-135`; `app/src/main/java/com/github/kr328/clash/ProfilesActivity.kt:59-64`; `service/src/main/java/com/github/kr328/clash/service/ProfileProcessor.kt:164-177`
  - Function / Module: `requestDelete` / `ProfileProcessor.delete`
  - Relevant behavior: Bottom sheet 中点击 Delete 后立即发送请求并关闭；Activity 直接调用 delete；service 在 NonCancellable 区域删除 Room 记录，并递归删除 pending/imported 目录，没有确认、软删除、回收站或 Snackbar undo。
- Problem: 高破坏性操作与普通菜单项只有颜色差异，一次点击就进入不可取消的永久删除。
- Why it matters: 用户配置可能包含手工规则、订阅信息和密钥，恢复成本很高；小触控目标问题进一步提高误触概率。
- Realistic failure scenario: 用户本想点击“复制”或“编辑”，在 Bottom sheet 上误点相邻“删除”；菜单立即关闭，配置和文件被永久移除，用户没有机会撤销。
- Minimal fix: 在删除前显示包含配置名的确认对话框；或先从列表移除并提供 10–30 秒 Snackbar 撤销，超时后再提交删除。
- Better long-term fix: 引入短期回收站/软删除记录，确保删除 active profile 时也有明确状态迁移和恢复路径。
- Regression test suggestion: UI 测试点击删除后断言必须二次确认；取消不调用 service；确认或 Snackbar 超时才删除；撤销后数据库和文件仍存在。
- Estimated effort: 4–8 hours

### Finding: [F-08] “更新全部”未禁用且请求通道无上限，重复点击会排队重复更新

- Severity: Medium
- Confidence: High
- Category: Performance
- Status: Confirmed
- Affected area: Profiles 批量更新
- Evidence:
  - File: `design/src/main/java/com/github/kr328/clash/design/Design.kt:15-20`; `design/src/main/java/com/github/kr328/clash/design/ProfilesDesign.kt:95-103`; `design/src/main/res/layout/design_profiles.xml:49-62`; `app/src/main/java/com/github/kr328/clash/ProfilesActivity.kt:45-58`
  - Function / Module: `Design.requests` / `requestUpdateAll` / `ProfilesActivity.main`
  - Relevant behavior: 所有 Design 请求使用 `Channel.UNLIMITED`；点击更新只把 `allUpdating` 设为 true 并启动旋转动画，没有 guard、`isEnabled=false` 或请求去重；Activity 串行遍历全部可更新配置，完成后才恢复图标。
- Problem: 加载态只是视觉动画，不限制交互。快速多次点击会把多个 UpdateAll 放入无上限队列，并在当前批次结束后再次执行完整批次。
- Why it matters: 重复网络下载、配置校验与磁盘替换浪费流量、电量和时间，也让用户看到“明明完成又重新开始”的混乱状态。
- Realistic failure scenario: 网络慢时用户认为第一次点击未生效，连续点击三次；第一次更新结束后队列继续执行两轮相同任务，失败提示和完成提示重复出现。
- Minimal fix: `requestUpdateAll` 在 `allUpdating` 时直接返回；更新期间禁用/不可点击并设置 stateDescription；批量请求使用 conflated/single-flight 语义。
- Better long-term fix: 建立统一 AsyncActionState（Idle/Running/Success/Error），所有长操作通过 single-flight coordinator 管理取消、重试和重复提交。
- Regression test suggestion: UI/协程测试在 100ms 内触发 5 次点击，断言 service update 每个 profile 只调用一次、按钮禁用且完成后恢复。
- Estimated effort: 2–4 hours

### Finding: [F-09] 应用访问控制一次性加载并长期持有所有应用图标

- Severity: Medium
- Confidence: High
- Category: Performance
- Status: Confirmed
- Affected area: Access Control 应用列表
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/AccessControlActivity.kt:120-149`; `design/src/main/java/com/github/kr328/clash/design/util/App.kt:8-15`; `design/src/main/java/com/github/kr328/clash/design/model/AppInfo.kt`
  - Function / Module: `loadApps` / `PackageInfo.toAppInfo`
  - Relevant behavior: 页面打开时查询全部已安装包，对每个候选包立即 `loadIcon(pm).foreground()` 和 `loadLabel`，排序后把含 Drawable 的完整 `List<AppInfo>` 长期交给 adapter；RecyclerView 虚拟化没有减少图标解码/常驻成本。
- Problem: 图标不是按可见项懒加载，而是在后台一次性解码后全部保留。安装应用数量越多，启动延迟与 Java/native bitmap 内存越高。
- Why it matters: 重度用户、工作资料夹或厂商预装较多的设备可能有数百个包；页面会长时间空白，低内存设备可能触发 GC 抖动或进程被杀。
- Realistic failure scenario: 设备安装 400 个应用，进入 Access Control 后需要读取 400 个图标并排序，界面数秒后才出现；滚动时 GC 频繁，返回其他页面后 adapter 仍持有全部 Drawable 直到 Activity 销毁。
- Minimal fix: 初始只加载 packageName/label，图标在 ViewHolder 可见时异步加载并用有界 LruCache 缓存；取消已回收 holder 的任务。
- Better long-term fix: 使用分页/增量查询和统一图标仓库，按尺寸缓存缩略图，并对 package change 事件做局部更新。
- Regression test suggestion: 用 fake PackageManager 模拟 500 个应用，断言首屏前只请求可见数量附近的图标、缓存有上限且滚动回收不会错位。
- Estimated effort: 1–2 days

### Finding: [F-10] 退出 Access Control 时等待 VPN 停止没有超时，页面可能永远关不掉

- Severity: Medium
- Confidence: High
- Category: Stability
- Status: Confirmed
- Affected area: Access Control 保存与服务重启
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/AccessControlActivity.kt:30-40`; `app/src/main/java/com/github/kr328/clash/BaseActivity.kt:123-135`
  - Function / Module: `defer` / `BaseActivity.finish`
  - Relevant behavior: 如果选中包发生变化且 Clash 正在运行，finish defer 会 stop service，然后 `while (clashRunning) delay(200)` 无限等待；`BaseActivity.finish` 在 defer 完成前不会调用 `super.finish()`，且没有 timeout 或失败 UI。
- Problem: 页面退出与一个跨进程状态位永久绑定。服务崩溃、广播丢失或状态未及时刷新时，用户无法通过返回键关闭页面。
- Why it matters: 保存设置的正常操作可能变成导航死锁；用户会认为应用卡死并强杀进程，设置是否保存也变得不明确。
- Realistic failure scenario: 后台服务在 stop 期间异常或状态广播未更新，`clashRunning` 一直为 true；用户按返回键后界面没有任何反馈，协程每 200ms 永久轮询。
- Minimal fix: 使用 `withTimeoutOrNull` 设置 5–10 秒上限；超时时仍完成退出并显示“服务未能重启”的持久提示；优先等待明确的 stop 事件而非轮询全局状态。
- Better long-term fix: 将“应用访问控制保存并重启服务”做成可观察事务，返回 Success/Timeout/Error，并允许后台完成或用户重试。
- Regression test suggestion: fake service 永不切换 running=false，断言 Activity 在超时后仍 finish，错误被展示且不会启动第二个服务。
- Estimated effort: 2–4 hours

### Finding: [F-11] 首屏无说明请求通知权限，且拒绝结果被完全忽略

- Severity: Low
- Confidence: High
- Category: Design
- Status: Confirmed
- Affected area: Android 13+ 首次启动
- Evidence:
  - File: `app/src/main/java/com/github/kr328/clash/MainActivity.kt:151-163`
  - Function / Module: `MainActivity.onCreate`
  - Relevant behavior: MainActivity 创建时立即注册并启动 POST_NOTIFICATIONS 请求；回调体为空，没有用途说明、拒绝状态记录、后续引导或只在启动 VPN/日志功能时请求的上下文。
- Problem: 权限请求发生在用户理解功能之前，系统弹窗与当前动作无关联；拒绝后应用也不解释前台服务通知和日志通知会怎样受影响。
- Why it matters: 无上下文权限请求的拒绝率更高，用户之后遇到通知缺失时难以定位原因。
- Realistic failure scenario: 用户首次打开应用就看到通知权限弹窗并拒绝；数天后启用 VPN 或日志记录却看不到预期通知，应用没有提示去设置中恢复权限。
- Minimal fix: 在用户第一次启动需要通知的前台服务时展示简短 rationale，再请求权限；处理拒绝并提供设置入口。
- Better long-term fix: 建立权限状态组件，将权限请求绑定到具体功能并持续展示降级影响。
- Regression test suggestion: UI 测试覆盖首次启动不立即弹窗、首次启用服务时弹窗、拒绝后展示可恢复提示三条路径。
- Estimated effort: 2–4 hours

### Finding: [F-12] 任意已安装应用都能无授权停止、启动或切换 VPN

- Severity: High
- Confidence: High
- Category: Security
- Status: Confirmed
- Affected area: ExternalControlActivity 与公开控制 Intent
- Evidence:
  - File: `app/src/main/AndroidManifest.xml:74-103`; `common/src/main/java/com/github/kr328/clash/common/constants/Intents.kt:5-10`; `app/src/main/java/com/github/kr328/clash/ExternalControlActivity.kt:31-99`
  - Function / Module: exported `ExternalControlActivity` / ACTION_START_CLASH / ACTION_STOP_CLASH / ACTION_TOGGLE_CLASH
  - Relevant behavior: Activity `exported=true`，三个控制 action 没有 manifest permission、调用方校验、用户 opt-in 或确认；收到 STOP/TOGGLE 后直接调用 `stopClashService()`。
- Problem: 自动化接口被标为 Public，但没有授权边界。任何本地应用都可以显式发送 Intent 改变 VPN 状态。
- Why it matters: 恶意或被入侵的普通应用可以静默停止代理/VPN，随后用户流量绕过预期隧道；Toast 不是有效授权机制。
- Realistic failure scenario: 一个无网络安全权限的第三方应用在后台发送 STOP Intent，Clash 被停止；用户继续使用敏感应用，以为 VPN 仍在运行，直到注意到短暂 Toast 或状态变化。
- Minimal fix: 将 deep link 导入和控制 action 拆分组件；控制 action 默认关闭并使用自定义权限或用户明确启用的授权开关；外部调用时要求可见确认。
- Better long-term fix: 为自动化提供带调用方授权/令牌的 API，记录最近调用方和时间，并在设置中允许逐应用授权与撤销。
- Regression test suggestion: instrumentation 测试从不同 package 发送三个 action，默认必须被拒绝；授权后仅允许白名单调用并记录审计事件。
- Estimated effort: 1–2 days

### Finding: [F-13] 发布 keystore 被版本库跟踪，release 缺配置时还会静默使用 debug 签名

- Severity: High
- Confidence: High
- Category: Security
- Status: Confirmed
- Affected area: Android 发布签名
- Evidence:
  - File: `release.keystore`; `build.gradle.kts:146-166`; `.gitignore:33-36`; `.github/workflows/build-release.yaml:95-111`
  - Function / Module: `signingConfigs.release` / release build type
  - Relevant behavior: Git 索引确认 `release.keystore` 自历史提交起被跟踪，即使 `.gitignore` 已忽略 `*.keystore`；build 使用该路径作为 release storeFile。若 `signing.properties` 不存在，release build 静默回退 `signingConfigs["debug"]`。审计未读取 keystore 内容或任何密码值。
- Problem: 私钥容器进入 Git 历史后无法靠 `.gitignore` 撤回；缺签名配置不失败而改用 debug key，会生成看似 release、实际无法升级正式安装包的产物。
- Why it matters: 如果该 keystore 是生产密钥且密码在其他渠道泄露，攻击者可伪造升级；即使不是生产密钥，debug 回退也可能导致发布不可升级和用户数据迁移中断。
- Realistic failure scenario: 新 runner/维护者缺少 signing.properties 仍成功构建 release APK，并错误上传；用户安装后发现无法覆盖正式版本。另一场景中，Git 历史里的生产 keystore 与泄露密码组合导致签名被接管。
- Minimal fix: 从索引和历史中移除 keystore；确认是否为生产密钥并按风险轮换；所有 release 构建在签名缺失时 `throw GradleException`；像 pre-release 一样从 secrets 临时恢复并在 job 结束清理。
- Better long-term fix: 使用受控签名服务/Play App Signing，CI 只获得最小权限的临时凭据；发布产物验证证书指纹并生成 provenance。
- Regression test suggestion: workflow 增加签名证书指纹校验；在缺少 secret 的测试 job 中断言 release 配置明确失败，而不是生成 debug-signed APK。
- Estimated effort: 1–3 days，若需生产密钥轮换则更长

### Finding: [F-14] 仓库没有测试，CI 也没有任何测试或静态质量门禁

- Severity: High
- Confidence: High
- Category: Testing
- Status: Confirmed
- Affected area: 全项目回归保障
- Evidence:
  - File: `.github/workflows/build-debug.yaml:69-71`; `.github/workflows/build-pre-release.yaml:81-83`; `.github/workflows/build-release.yaml:109-111`
  - Function / Module: GitHub Actions build jobs
  - Relevant behavior: `rg --files` 未发现 test/androidTest/Test 文件；三个 workflow 只执行 assemble，没有 unit test、instrumentation test、lint、detekt、dependency scan、UI accessibility 或 benchmark gate。
- Problem: 构建成功只能证明编译/打包，不证明列表更新、持久化、Intent 权限、迁移、错误路径或 UI 状态正确。
- Why it matters: F-02 这类纯逻辑区间错误、F-07 删除行为、F-08 重复请求和 F-12 外部授权问题都能在编译完全成功时进入发布。
- Realistic failure scenario: 开发者改动实时日志或代理 Diff，PR workflow 绿灯并合并；用户升级后才发现日志崩溃或测速卡顿，维护者只能通过线上反馈定位。
- Minimal fix: 先添加不依赖设备的 JVM 测试覆盖 LogcatCache/adapter 更新、Profile 删除确认状态、请求去重和解析边界；CI 增加 `test`、Android Lint 和测试报告步骤。
- Better long-term fix: 分层建立 unit、Room/integration、instrumentation/E2E、AccessibilityChecks 和 Macrobenchmark，并把稳定发布绑定到全部门禁通过。
- Regression test suggestion: 本 finding 本身的验证是 CI 必须存在至少一个失败示例 fixture，并在故意破坏 LogcatCache 区间时阻止 merge。
- Estimated effort: 初始 3–5 days，持续建设

### Finding: [F-15] 每次 assemble 都从可变 latest URL 下载未校验资产并打包

- Severity: High
- Confidence: High
- Category: Release
- Status: Confirmed
- Affected area: Geo 数据构建供应链
- Evidence:
  - File: `app/build.gradle.kts:35-67`
  - Function / Module: `downloadGeoFiles` task / `afterEvaluate`
  - Relevant behavior: 四个资产从 GitHub Releases 的 `/download/latest/` URL 下载；没有固定版本、commit、SHA-256、签名或文件大小验证；所有以 assemble 开头的任务都依赖下载任务并把结果写入 `src/main/assets`。
- Problem: 同一源码提交在不同时间可能打包不同字节，远端 latest 被替换或上游账号/发布流程受损时，恶意数据会直接进入 APK。
- Why it matters: 这是稳定发布的核心资产供应链，破坏可复现构建，也让 code review 无法审查最终进入产物的内容。
- Realistic failure scenario: 上游 latest release 更新或被篡改，维护者未改任何源码却生成内容不同的 APK；用户收到异常规则数据库，代理解析或路由行为被改变。
- Minimal fix: 固定 release 版本 URL和每个文件 SHA-256；下载后校验摘要、大小与格式，失败即停止构建；缓存已验证资产。
- Better long-term fix: 将资产版本和摘要放入受审查的 manifest，更新由独立 PR 完成；发布生成 SBOM/provenance 并记录资产来源。
- Regression test suggestion: workflow 使用错误摘要 fixture 必须构建失败；重复两次构建时验证资产 hash 和 APK 输入一致。
- Estimated effort: 4–8 hours

### Finding: [F-16] 正式发布在构建验证前修改 main、创建 tag 并推送

- Severity: High
- Confidence: High
- Category: Release
- Status: Confirmed
- Affected area: `build-release.yaml`
- Evidence:
  - File: `.github/workflows/build-release.yaml:51-111`
  - Function / Module: version conversion / Re-write version / Commit and push / Release Build
  - Relevant behavior: workflow 先改 `build.gradle.kts`，commit、tag 并 `git push --follow-tags`，之后才准备签名并执行 `assembleMetaRelease`；没有测试门禁或失败回滚。
- Problem: Git 仓库状态被当作构建前置动作，而不是已验证产物的结果。任何后续下载、签名、编译或上传失败都会留下指向未成功发布版本的提交/tag。
- Why it matters: 版本号和 tag 是用户、自动更新和维护者的信任锚点；假 tag 会干扰重试、变更日志、回滚和后续发布。
- Realistic failure scenario: workflow 已推送 v2.x.x tag，随后 mutable Geo 下载失败或 Gradle 构建失败；GitHub 上已有 tag 和版本提交，但没有对应可安装产物，重跑还可能因 tag 冲突失败。
- Minimal fix: 在临时 checkout 上先完成资产校验、测试、构建、签名和证书验证；全部成功后再原子创建/推送 tag 和 release。版本提交可通过独立 PR 完成。
- Better long-term fix: 使用“不可变源码 tag → 可复现构建 → provenance → 发布”的流程，tag 仅由受保护环境在全部 required checks 通过后创建。
- Regression test suggestion: 在临时分支模拟构建失败，断言远端没有新 tag/版本提交；对 workflow YAML 增加 actionlint/策略检查确保 push 步骤位于 build verify 之后。
- Estimated effort: 1 day

### Finding: [F-17] Android 自动备份包含订阅源、配置文件和 ageSecretKey，但文档未说明

- Severity: Medium
- Confidence: High
- Category: Security
- Status: Confirmed
- Affected area: 备份、恢复与隐私治理
- Evidence:
  - File: `app/src/main/AndroidManifest.xml:27-35`; `app/src/main/res/xml/full_backup_content.xml:2-17`; `service/src/main/java/com/github/kr328/clash/service/data/Imported.kt:11-22`; `service/src/main/java/com/github/kr328/clash/service/data/Pending.kt:11-22`; `PRIVACY_POLICY.md:1-30`
  - Function / Module: Android Auto Backup / Room profile entities
  - Relevant behavior: `allowBackup=true`，backup rules 包含全部 sharedpref、全部 database、`imported`、`pending` 和 override；Room 表保存 source 与 `ageSecretKey`。Privacy Policy 使用通用“第三方服务/Log Data”文本，却没有描述配置、订阅 URL、密钥材料的云备份与恢复。
- Problem: 敏感连接配置和解密密钥默认进入平台备份边界，未做字段级排除/加密分类，也没有向用户说明或提供选择。
- Why it matters: 用户可能认为代理配置仅保存在本机；云账号、厂商备份或恢复设备扩大了数据暴露面，并可能把旧密钥恢复到不期望的设备。
- Realistic failure scenario: 用户启用系统云备份后更换设备，完整数据库和 imported 配置被恢复，包括订阅 URL 与 ageSecretKey；共享账号或受管设备管理员可接触到这些备份数据。
- Minimal fix: 分类敏感与非敏感数据；默认从 backup 排除 ageSecretKey、认证 URL 和原始配置，或使用用户绑定密钥加密；补充 Android 12+ data extraction rules 和隐私说明。
- Better long-term fix: 提供显式“备份/导出配置”功能，允许用户选择是否包含秘密，并以加密包、密码和恢复审计替代隐式全量云备份。
- Regression test suggestion: 增加备份规则静态测试，断言敏感数据库/目录不在 cloud/device transfer 范围；恢复测试确保非敏感 UI 设置仍可恢复。
- Estimated effort: 1–3 days

### Finding: [F-18] design UI 模块直接依赖 service 存储模型和具体 Store

- Severity: Medium
- Confidence: High
- Category: Maintainability
- Status: Confirmed
- Affected area: 模块边界与 UI 状态所有权
- Evidence:
  - File: `design/build.gradle.kts:7-10`; `design/src/main/java/com/github/kr328/clash/design/ProfilesDesign.kt:9-17`; `design/src/main/java/com/github/kr328/clash/design/AppSettingsDesign.kt`; `design/src/main/java/com/github/kr328/clash/design/NetworkSettingsDesign.kt`
  - Function / Module: `:design` dependency graph
  - Relevant behavior: `design` 直接 implementation `:service`，UI/adapter 使用 service `Profile`、`AccessControlMode`，部分 Design 直接构造并读写 `ServiceStore`；因此 UI 模块不能脱离后台持久化实现测试或复用。
- Problem: 展示层依赖具体存储与后台模型，状态验证、持久化副作用和渲染混在同一层；service schema 变化会直接扩散到 XML binding 和 UI。
- Why it matters: 新增加载/错误状态、替换存储、编写 JVM UI state 测试都更困难，促进了当前“Activity 手工 select + Design 手工状态”的重复模式。
- Realistic failure scenario: Profile 数据库字段或 ServiceStore key 变化，需要同时修改 service、design adapter、多个 XML 和 Activity；缺少测试时很容易出现部分页面仍使用旧语义。
- Minimal fix: 在 app/domain 边界引入小型 UI model 和 settings interface；Design 只接收数据与发出意图，不直接访问 ServiceStore。
- Better long-term fix: 逐页面建立明确的 state reducer/controller，使持久化和远程调用位于 app/service adapter，design 成为可测试的纯渲染层；无需整体重写。
- Regression test suggestion: 为 Profiles/Settings 创建纯 JVM state 测试，证明 design module 可在不实例化 service store/Room 的情况下渲染并处理 intent。
- Estimated effort: 分页面 2–5 days

## 5. Architecture

- Coverage: Medium
- Inspected evidence: Gradle 模块依赖、Activity/Design/Service 调用链、Room/store/native 边界、关键用户流程。
- Exclusions / limits: 未生成完整依赖图，未逐行审计上游 Go 子模块。

| Finding | Subtype | Affected areas | Recommended action |
|---------|---------|----------------|--------------------|
| F-18 | DependencyDirection / StateOwnership | `:design` → `:service`、Profiles/Settings | 引入 UI model 与 settings port，收紧状态所有权 |
| F-08 | StateOwnership | 无上限 UI request channel | 将长操作建模为 single-flight async state |

已验证：`core` 不依赖 `service/design/app`，`service` 不依赖 UI，主要依赖方向整体仍是可修复的；问题集中在 design 越过展示边界访问 service 具体类型。

## 6. Security

- Coverage: Medium
- Inspected evidence: exported 组件、Intent actions、签名配置、Git 敏感文件元数据、backup、DocumentsProvider、网络安全配置。
- Exclusions / limits: 未做动态 Intent 攻击、CVE 扫描或 keystore 内容检查。

| Finding | Severity | Security boundary |
|---------|----------|-------------------|
| F-12 | High | 任意本地应用→VPN 控制 |
| F-13 | High | Git/CI→发布签名 |
| F-17 | Medium | 本机配置→平台备份 |

已验证：后台 service 多数 `exported=false`；VPN service 受系统 `BIND_VPN_SERVICE` 权限保护；DocumentsProvider 使用系统 `MANAGE_DOCUMENTS` 权限。

## 7. Stability

- Coverage: Medium
- Inspected evidence: RecyclerView 更新、协程循环、退出 defer、日志解析、Profile 锁与非取消删除。
- Exclusions / limits: 未在设备上复现 RecyclerView 崩溃、Binder 死亡或进程恢复。

| Finding | Severity | Failure mode |
|---------|----------|--------------|
| F-02 | High | Adapter notification 与数据集不一致 |
| F-03 | High | 主线程阻塞、OOM、损坏日志整文件失败 |
| F-10 | Medium | finish 无限等待服务状态 |

已验证：Profile apply/update/delete 使用 mutex 与 NonCancellable 保护关键文件/数据库切换，降低了取消导致半写入的风险。

## 8. Performance

- Coverage: Medium
- Inspected evidence: 代理测速、RecyclerView Diff/animation、日志读写、PackageManager 图标加载、ticker/轮询。
- Exclusions / limits: 没有 profiler、trace、benchmark 或真机数据。

| Finding | Workload | Bottleneck |
|---------|----------|------------|
| F-01 | 大代理组测速 | 10Hz 全量 IPC + Diff + 主线程分配 + 重绘 |
| F-03 | 大历史日志 | 主线程全文件解析 + 无界对象列表 |
| F-04 | 长时日志记录 | 无界磁盘写入与后续超大文件读取 |
| F-09 | 数百已安装应用 | 全量图标解码与常驻 |

已验证：Proxy Diff 计算本身已切到 Default dispatcher，应用列表查询在 IO；问题是全量频率、主线程提交和无界资源，而不是所有工作都在主线程。

## 9. Testing

- Coverage: High
- Inspected evidence: 全仓文件清单、所有 workflow 的 Gradle 命令、测试目录/命名模式。
- Exclusions / limits: 仓库没有测试，因此无法运行或评估覆盖率。

| Priority | Missing confidence |
|----------|--------------------|
| Must add | LogcatCache/RecyclerView 更新、Profile 删除、外部 Intent 授权、备份规则、release 顺序 |
| Should add | Proxy 300 节点性能、重复提交、Access Control 大列表、TalkBack/触控目标 |
| Nice to have | 多语言截图、低端设备功耗、日志搜索/导出体验 |

F-14 是发布阻断项。当前绿色 CI 只代表 assemble 成功。

## 10. Maintainability

- Coverage: Medium
- Inspected evidence: 模块依赖、文件规模、UI state、Channel、Adapter 更新和 Store 使用。
- Exclusions / limits: 未执行 detekt/ktlint 或历史 churn 分析。

| Finding | Principle | Maintenance cost |
|---------|-----------|------------------|
| F-18 | Dependency Rule 7.1 / Business Logic Independence 7.2 | service schema 变化扩散到 UI |
| F-08 | Unbounded Resources 10.2 | 所有 Design 默认可无界积压请求 |
| F-05 | Explicit semantic contract | 自绘 UI 行为只能靠实现细节理解 |

已验证：最大 Kotlin 文件约 421 行，没有超过 rubric 的 500 行文件阈值；模块和类命名总体能表达职责。

## 11. Design

- Coverage: Medium
- Inspected evidence: 核心页面 XML、custom view、loading/error/empty state、破坏性操作和权限时机。
- Exclusions / limits: 未进行用户研究、TalkBack 或屏幕尺寸实测。

| Finding | UX subtype | User impact |
|---------|------------|-------------|
| F-05 | SemanticStructure / ResponsiveVisual | 代理信息无法被辅助技术读取，长名称不可辨 |
| F-06 | ResponsiveVisual / SemanticStructure | 30dp 误触与错误朗读 |
| F-07 | UXStateCorrectness | 不可撤销误删除 |
| F-08 | LoadingState | 重复提交与状态混乱 |
| F-11 | ErrorState / Permission UX | 无上下文权限请求和无恢复引导 |

## 12. Release

- Coverage: Medium
- Inspected evidence: debug/pre-release/release workflow、版本、签名、Geo 资产、产物上传、submodule 更新。
- Exclusions / limits: 未实际执行 workflow、签名校验或回滚。

| Finding | Release risk | Required gate |
|---------|--------------|---------------|
| F-13 | 错误/泄露签名 | 缺 secret 必须失败，验证证书指纹 |
| F-14 | 无测试门禁 | test/lint/instrumentation required checks |
| F-15 | 不可复现资产 | 固定版本与 SHA-256 |
| F-16 | 假 tag/失败后污染 main | 构建验证后再推送 tag |

## 13. Documentation

- Coverage: Medium
- Inspected evidence: README、Privacy Policy、Manifest 行为、备份与发布脚本。
- Exclusions / limits: 未核对外部网站、商店描述和全部翻译。

F-17 同时是文档缺口：Privacy Policy 没有说明配置、订阅源、密钥材料的系统备份边界，却保留了与当前代码不完全对应的通用第三方采集/Log Data 文案。README 能描述基本构建和自动化入口，但缺少架构、测试策略、发布回滚与签名应急说明。

已验证：README 标出了主要模块外的构建入口，仓库也有独立 Privacy Policy；应更新而不是删除文档体系。

## 14. Configuration

- Coverage: Medium
- Inspected evidence: Gradle build types/flavors、signing config、SharedPreferences stores、Manifest、backup rules。
- Exclusions / limits: 未遍历所有 UI 设置组合。

| Finding | Unsafe default | Fix |
|---------|----------------|-----|
| F-13 | release 缺配置回退 debug 签名 | release 配置缺失时 fail-fast |
| F-01 | 100ms 热路径硬编码 | 提升节流并集中为可测试策略常量 |

已验证：applicationId、flavor、NDK/SDK 版本集中在根 Gradle 文件，基本配置来源可追踪。

## 15. Observability

- Coverage: Medium
- Inspected evidence: common Log、Logcat service/cache/writer、Snackbar/Toast 错误展示、前台通知。
- Exclusions / limits: 未查看真实日志内容和线上崩溃/指标平台。

| Finding | Signal problem |
|---------|----------------|
| F-02 | 日志查看器自身更新协议错误 |
| F-03 | 大/损坏日志不可渐进读取 |
| F-04 | 日志无配额、轮转、session 大小可见性 |

已验证：项目具备本地实时日志、文件导出和前台记录通知，这是良好基础；应先保证日志工具自身可靠且资源有界。

## 16. Data Integrity

- Coverage: Medium
- Inspected evidence: Room entities/DAO、ProfileProcessor locks、文件复制替换、删除、legacy migration、backup。
- Exclusions / limits: 未运行迁移、断电或恢复测试。

F-07 是用户数据完整性风险：删除路径本身原子而彻底，但 UI 缺少确认/撤销。F-17 说明备份边界过宽。正向方面，ProfileProcessor 用 `profileLock`/`processLock` 串行化处理并在 NonCancellable 中完成关键转换，降低半完成状态。

## 17. Privacy

- Coverage: Medium
- Inspected evidence: QUERY_ALL_PACKAGES、Installed Apps、剪贴板、日志、Auto Backup、Privacy Policy。
- Exclusions / limits: 未做网络抓包、云备份恢复或数据主体请求验证。

| Finding | Affected data | Governance gap |
|---------|---------------|----------------|
| F-17 | source、配置文件、ageSecretKey、SharedPreferences | 默认进入平台备份且无清晰告知/选择 |
| F-09 | 已安装应用名称与图标 | 本地全量枚举合理用于功能，但应避免不必要常驻和文档缺失 |

未发现明显第三方分析 SDK 调用；Privacy Policy 应以实际本地数据流为准重新校准。

## 18. Accessibility

- Coverage: Medium
- Inspected evidence: 全部 design layout XML、自定义 View、可点击控件尺寸/标签、focusable/clickable 属性。
- Exclusions / limits: 未运行 TalkBack、Switch Access、键盘/D-pad 和字体缩放实测。

### Accessibility Summary

| Subtype | Count | Affected workflows | Recommended action |
|---------|-------|--------------------|--------------------|
| SemanticStructure | 2 findings | 代理选择、Profiles 更新 | 暴露完整名称/状态并修正标签 |
| KeyboardFocus | 0 confirmed | 多数控件已 focusable | 需要设备验证 ViewPager/RecyclerView 焦点顺序 |
| ResponsiveVisual | 2 findings | 全局 toolbar、三列代理 | 48dp touch target、字体缩放与长名称测试 |
| ErrorState | 1 finding | 通知权限 | 拒绝后给出恢复路径 |
| LoadingState | 1 finding | 更新全部 | 禁用/去重并宣布进行中状态 |
| UXStateCorrectness | 1 finding | 配置删除 | 二次确认或撤销 |

相关 finding：F-05、F-06、F-07、F-08、F-11。

## 19. Supply Chain

- Coverage: Medium
- Inspected evidence: GitHub Actions uses、Go toolchain、submodule、Geo 下载、release keystore、产物上传。
- Exclusions / limits: 未生成 SBOM/依赖树，未核验所有 action tag 对应 commit。

### Supply Chain Summary

| Subtype | Count | Affected surface | Recommended action |
|---------|-------|------------------|--------------------|
| DependencyProvenance | 1 | Geo latest assets | 固定版本与 hash |
| Reproducibility | 1 | assemble 输入 | 缓存并验证资产 manifest |
| CIIntegrity | 2 | action pinning、release push | SHA pin、高权限步骤后移 |
| ArtifactProvenance | 2 | signing、APK | 指纹、checksum、provenance、SBOM |
| RegistryHygiene | 1 | Git history keystore | 移除并评估轮换 |

F-13、F-15、F-16 是主要问题。部分 workflow action 已固定 commit，这是值得保留的方向，但仍存在大量仅按 major/tag 引用的 action。

## 20. Cost

- Coverage: Medium
- Inspected evidence: CPU/主线程热路径、后台 service、图标内存、日志磁盘与网络更新。
- Exclusions / limits: 无云端费用面，未测量设备电量和存储写入。

| Cost driver | Finding | Bound needed |
|-------------|---------|--------------|
| CPU/GPU/Binder | F-01 | 更新频率、payload、动画次数 |
| Disk | F-04 | 单文件/总目录配额和保留时间 |
| Memory | F-03, F-09 | 日志分页、图标 LRU |
| Network | F-08 | single-flight/去重 |

移动端的“成本”主要体现为电量、发热、流量和存储，而不是云账单。

## 21. AI / LLM Safety

- Coverage: Not assessed
- Inspected evidence: 文件清单、依赖和源码搜索未发现模型、prompt、RAG、agent tool 或 LLM API。
- Exclusions / limits: 项目不包含该功能面。

本维度不适用，不计入额外 finding 或评分。

## 22. Fallback

- Coverage: Medium
- Inspected evidence: signing fallback、异常 catch、默认 mode、等待循环、错误 Toast/Snackbar。
- Exclusions / limits: 未触发所有设备/系统版本兼容分支。

| Finding | Fallback type | Action |
|---------|---------------|--------|
| F-13 | SilentFallback | release 缺签名时必须 fail-fast |
| F-10 | DefensiveGuess | 用明确事件+timeout 代替无限轮询 |
| F-03 | Whole-file fallback | 单行损坏不应让整个日志不可读 |

## 23. Testing Authenticity

- Coverage: High
- Inspected evidence: 测试文件清单、CI 命令和测试目录模式。
- Exclusions / limits: 无测试可评价 mock、断言或 flaky 风险。

### Confidence Assessment

| Test area | Real confidence | Risk | Action |
|-----------|-----------------|------|--------|
| Kotlin/JVM core logic | None | Diff、cache、解析错误逃逸 | Add |
| Room/Profile integration | None | 删除、迁移、备份错误逃逸 | Add |
| Android UI/accessibility | None | 误触、语义、重复提交逃逸 | Add |
| Release workflow | None | tag/签名/资产问题逃逸 | Add |

当前不是“测试质量低”，而是没有可见测试信心。

## 24. Type Safety

- Coverage: Medium
- Inspected evidence: `!!`、索引、字符串 split/enum、Parcelable/JSON 边界和 Data Binding。
- Exclusions / limits: 未执行 Kotlin 编译器、Android Lint 或 fuzz。

F-03 包含最现实的边界类型问题：日志行从 `List<String>` 直接索引并用 `valueOf` 解析，外部/损坏文件没有结构化验证。另有多处内部 `!!`，但多数位于 Android 生命周期不变量或已检查路径，本报告未将其泛化为独立 finding。

## 25. Frontend State

- Coverage: Medium
- Inspected evidence: Design request Channel、Activity `select`、Adapter state、loading/empty/error、轮询与生命周期取消。
- Exclusions / limits: 未做旋转、后台恢复、进程重建和请求竞态实测。

### Summary

| Subtype | Count | Affected components |
|---------|-------|---------------------|
| StateDuplication | 2 | Proxy urlTesting、Profiles allUpdating |
| EffectChain | 1 | Proxy 100ms polling→Diff→animation |
| UIBusinessCoupling | 1 | design→ServiceStore/Profile |
| RequestState | 3 | F-08、F-10、F-11 |
| RenderPerf | 2 | F-01、F-09 |

相关 finding：F-01、F-08、F-10、F-11、F-18。

## 26. Backend API

- Coverage: Not assessed
- Inspected evidence: 项目结构和 Manifest 表明这是 Android 客户端/本地后台服务，没有常规 HTTP server/controller/endpoint 层。
- Exclusions / limits: native core 的代理协议和外部订阅源不属于本节后端 API 设计范围。

本维度不适用。

## 27. Dependency Weight

- Coverage: Low
- Inspected evidence: Gradle 直接依赖、Go module 文件、QR scanner、Room、Material、native build 插件。
- Exclusions / limits: 禁止运行 Gradle，无法获得 transitive 数量、DEX/so 体积和未使用依赖报告。

未确认明显“仅为一个函数引入巨型库”的 finding。`quickie.bundled` 可能增加包体，但项目确实使用二维码扫描，缺少包体证据时不建议删除。优先处理 F-15 的构建资产供应链，而不是凭名称猜测依赖重量。

## 28. Code Consistency

- Coverage: Medium
- Inspected evidence: Kotlin/XML 命名、toolbar 控件、Adapter 更新、错误展示、Channel 模式。
- Exclusions / limits: 未执行格式化/lint 工具。

F-06 体现了复制式 toolbar XML 的一致性债务：同一 30dp 交互模式传播到 20 个控件，并出现更新按钮复用“新建”标签。Adapter 数据更新同时存在 `swapDataSet`、`patchDataSet`、手写 notify 区间和手写 Diff，多种模式并存是 F-02 更易产生的背景。建议统一为少量经过测试的 ListAdapter/ToolbarAction 组件，而不是全库重格式化。

## 29. Comment Coverage

- Coverage: Low
- Inspected evidence: 复杂协程、日志增量协议、Manifest、build workflow、README/Privacy Policy、TODO/FIXME 搜索。
- Exclusions / limits: 未逐个公共 API 统计 doc comment。

高风险缺口不在“每个函数都没注释”，而在复杂不变量没有被说明：LogcatCache 的 removed/appended 语义、代理测速刷新/排序策略、release workflow 的 tag 顺序和敏感备份边界都缺少可执行契约。应以测试和短注释记录 why/invariant，避免用大量复述代码的注释制造噪音。

## 30. Principles Compliance

总体上，模块命名和大部分文件规模保持克制，ProfileProcessor 对关键状态使用锁和非取消区间，也体现了明确的一致性意识。但在资源边界、fail-fast、最小权限、依赖方向和 UI 语义方面存在直接风险。

### Principles Violated

| Principle | Violations | Severity | Affected areas |
|-----------|------------|----------|----------------|
| 10.2 Unbounded Resources | 3 | High/Medium | Design Channel、日志文件、日志整列表 |
| 10.1 No Blocking Calls in Async Context | 1 | High | 主线程 `LogcatReader.readAll` |
| 10.4 Timeout Every External Call/Wait | 1 | Medium | Access Control 退出等待 |
| 4.6 Least Privilege | 2 | High | ExternalControl、发布签名材料 |
| 4.4 Fail-Fast | 2 | High | debug signing fallback、日志输入解析 |
| 7.1 Dependency Rule | 1 | Medium | design→service concrete dependency |
| 7.2 Business Logic Independence | 1 | Medium | UI 直接使用 Store/持久化模型 |
| 6.2 Don't Lose Error Context | 2 | Medium | 权限拒绝空回调、损坏日志整文件 invalid |
| Accessibility semantic contract | 2 | Medium | ProxyView、toolbar icon actions |

### Principles Respected

- `core`、`service`、`app` 的宏观依赖方向基本单向，没有发现明显模块循环。
- Profile apply/update/delete 使用 mutex 串行化并保护关键提交区间。
- 大多数后台服务组件默认不导出，VPN service 使用系统绑定权限。
- 代理 Diff 计算和应用列表枚举已尝试放到后台 dispatcher，说明代码已有避免主线程重活的意识。
- Kotlin 文件规模总体受控，没有超过 500 行阈值的 first-party Kotlin 文件。

## 31. Recommended Fix Order

### Fix Immediately

| Finding | Reason | Owner suggestion |
|---------|--------|------------------|
| F-12 | 可被任意应用停止 VPN | Security/App |
| F-13 | 发布签名与升级链风险 | Release/Security |
| F-15 | 构建输入可变且无完整性验证 | Build/Release |
| F-16 | 失败发布会污染 main/tag | Release |

### Fix Before Stable Release

| Finding | Reason |
|---------|--------|
| F-02 | 实时日志列表可异常/崩溃 |
| F-03 | 大日志导致白屏、ANR、OOM |
| F-14 | 所有关键修复都缺少回归门禁 |
| F-01 | 用户可见测速热路径卡顿与耗电 |
| F-07 | 不可撤销配置误删 |
| F-08 | 批量更新重复提交 |
| F-10 | 返回页面可能永久卡住 |

### Schedule Later

| Finding | Reason |
|---------|--------|
| F-04 | 日志配额和生命周期 |
| F-05 | 代理卡片无障碍语义 |
| F-06 | 全局触控目标/标签组件化 |
| F-09 | 大应用列表内存和首屏延迟 |
| F-17 | 敏感备份分类与文档 |
| F-18 | 展示层边界收紧 |

### Ignore for Now

| Finding | Reason |
|---------|--------|
| F-11 | 影响权限转化与可理解性，但不阻塞核心功能；可随权限状态组件一起修复 |

## 32. Quick Wins

| Quick win | Value | Effort |
|-----------|-------|--------|
| 修正 Logcat full snapshot appended 和插入起点 | 直接消除高风险列表错误 | 2–4 hours |
| `requestUpdateAll` 增加 running guard 并禁用按钮 | 消除重复网络任务 | 1–2 hours |
| Access Control 等待加 10 秒 timeout | 消除无法退出页面 | 1–2 hours |
| Profiles 同步按钮标签改为 `update_all` | 修复错误朗读 | 10 minutes |
| release 缺签名配置时 fail-fast | 阻止错误签名产物 | 1 hour |
| Geo URL 固定版本并增加 SHA-256 | 恢复构建输入可审查性 | 4–8 hours |
| 配置删除增加确认对话框 | 降低误删风险 | 2–4 hours |
| 代理测速刷新调到 300–500ms 并关闭测速中 move animation | 快速降低卡顿 | 2–4 hours |

## 33. Long-term Refactor Plan

1. **建立可测试的 UI state 层**：先从 Profiles、Proxy、Access Control 三个热点页面开始，把 `Idle/Loading/Success/Error` 和 single-flight 请求放入 controller/reducer；Design 只渲染 state 和发送 intent。风险是行为变化，测试策略是先为现状补 JVM/instrumentation 回归测试。
2. **统一列表更新协议**：将手写 `notifyItemRange*`、`swapDataSet`、`patchDataSet` 收敛到 `ListAdapter/DiffUtil` 或经过测试的少量 helper，支持 payload。风险是滚动位置/动画变化，测试策略覆盖头删尾增、移动、selection payload 和大列表。
3. **把日志作为有界数据产品**：增加 session、轮转、目录配额、分页读取、容错解析和导出；风险是兼容旧日志，测试策略使用多版本 fixture 和损坏尾行。
4. **重构发布为验证后发布**：固定所有输入、测试/构建/签名/指纹验证完成后再创建 tag，输出 checksum、SBOM 和 provenance。风险是发布流程迁移，测试策略是在临时仓库/预发布环境演练成功与故意失败路径。
5. **建立统一可访问组件库**：ToolbarAction、DestructiveAction、AsyncAction 和自绘卡片语义统一实现 48dp、标签、状态、焦点和字体缩放。风险是视觉变化，测试策略使用 AccessibilityChecks 与多字体/多尺寸截图。

---

审计结论：代码库不是不可救的“整体重写”状态，主要问题集中在少数高频用户流程、资源边界和发布信任链。优先修复 F-12/F-13/F-15/F-16，再处理日志与测试基础，能够以局部改动显著降低真实用户风险。
