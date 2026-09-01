# ClashMetaForAndroid 架构与代码设计审查清单

- 审查日期：2026-08-31
- 审查方法：`awesome-architecture` 第 18 章四步读图法（本质 → 全景 → 取舍 → 死穴）+ 第 24 章审查清单（一致性 / 韧性 / 规模 / 安全）+ 第 9 章架构品味 + `templates/mobile-app` 移动端约束
- 执行纪律：`verified-methodology.md`（禁本地构建、并行不相交分组审查、逐条读源码核实、CI 为唯一验证者）
- 勾选含义：`[x]` = 代码已改完并已核查 diff，`[~]` = 已派修复、待核查 diff，`[ ]` = 已记录但尚未动手
- 来源标注：`(自审)` = 协调者本人读图所得；`(g1)`~`(g6)` = 六个不相交并行子代理的分组审查结果（g1 app-ui / g2 app-infra / g3 service-core / g4 service-modules / g5 data-core / g6 common-sdk-build）

---

## 一、读图笔记

**① 本质**

- 一句话业务：把 Go 写的 mihomo 内核包成一个能长期驻留在手机上的 VPN 客户端。
- 质量属性（最重两条）：**内核存活率**（`:background` 进程被系统杀掉即断网）、**配置正确性**（配置写坏 = 用户永久失联）。
- 硬约束：**内核必须活在独立进程**（JNI + VpnService + 前台服务），于是所有状态都要跨进程搬运。

**② 全景**

灵魂路径：UI 进程 → kaidl 生成的 Binder（`IClashManager` / `IProfileManager` / `IRemoteService`）→ `:background` 进程 → JNI Bridge → Go 内核；反向靠自广播 + `ContentProvider`（`StatusProvider` / `PreferenceProvider`）回吐状态。

**③ 取舍**

- 选了**双进程 + Binder**，放弃了单进程的简单，因为内核崩溃/OOM 不能带走 UI，且 VpnService 要独立存活。
- 选了**Room + 文件目录**双份真相（`Pending` 暂存 / `Imported` 生效），放弃了单表直改，因为导入失败必须可回滚、不能污染已生效配置。
- 选了**自广播 + ContentProvider**，放弃了 Binder 回调，因为 UI 进程会被销毁重建，回调注册难保活。

**④ 死穴**

- 先死在哪：**跨进程边界的空档期**——`:background` 被杀 / 绑定被拒 / 迁移中断这三类时刻，UI 侧全部按“稍后一定会好”写的，没有超时也没有降级。
- 常见反模式：把 Binder 当本地方法调用（无超时、无失败路径）；启动路径上把可失败的初始化当成一定成功。

---

## 二、勾选表

### A. 严重：数据丢失 / 崩溃 / 安全边界

<!-- SECTION-A -->

- [x] **A-01 迁移时先删源文件，失败重试只能导入空配置（数据永久丢失）**
  - `service/src/main/java/com/github/kr328/clash/service/data/migrations/LegacyMigration.kt:181`
  - 缺陷：`legacyFile.delete()` 排在 `PendingDao().insert(pending)` 之前。
  - 触发：插入抛异常 → 外层 `migrationFromLegacy` 捕获后**故意保留旧库等下次重试**，但源 yaml 已被删，重试导入的是空文件。
  - 修法：`delete()` 移到 `insert()` 之后，并注明“新行落库后才允许丢弃源”。
  - 清单项：① 事务边界

- [x] **A-02 广播 extra 里的 UUID 未做容错，畸形值直接崩前台进程**
  - `app/src/main/java/com/github/kr328/clash/remote/Broadcasts.kt:62,67`
  - 缺陷：`UUID.fromString(intent.getStringExtra(...))` 裸调用，非法字符串抛 `IllegalArgumentException`，null 抛 NPE。
  - 触发：动态注册的 receiver 在 API < 33 上没有 export 标记，第三方应用可发一条 `ACTION_PROFILE_UPDATE_FAILED` 带垃圾 uuid。
  - 修法：新增 `Intent.uuidExtra()`，`runCatching` 解析失败返回 null（接口本就声明 `UUID?`）。
  - 清单项：④ 输入校验

- [x] **A-03 自广播 receiver 未挂签名权限，等于对外导出**
  - `app/src/main/java/com/github/kr328/clash/remote/Broadcasts.kt:100`
  - 缺陷：`registerReceiverCompat` 的 `permission` 参数留空，API < 33 路径下 receiver 可被任意应用触达；原有的 `intent.package != context.packageName` 判断挡不住（`setPackage` 由发送方自己填）。
  - 触发：外部应用伪造 `ACTION_CLASH_STOPPED` / `ACTION_PROFILE_CHANGED`，UI 侧状态被投毒。
  - 修法：补 `Permissions.RECEIVE_SELF_BROADCASTS`。已核实零风险：该权限在 `common/AndroidManifest.xml` 声明为 `privileged|signature` 且本应用 `uses-permission`；`sendBroadcastSelf` 所有发送方都带该权限；`TileService.kt:49`、`clash/module/Module.kt:48` 两处兄弟调用早已这么写——本处属**不一致的丑**。
  - 清单项：④ 鉴权与越权

- [x] **A-04 `bindService` 返回值被丢弃，绑定被拒后永久无声**
  - `app/src/main/java/com/github/kr328/clash/remote/Service.kt:42`、`sdk/src/main/java/com/github/kr328/clash/sdk/internal/RemoteSession.kt:43`
  - 缺陷：只 catch 异常，而 `bindService` 是**用返回 false 报告失败**的。
  - 触发：`:background` 被冻结 / 后台启动受限 / 组件被禁用 → 返回 false，`Resource<IRemoteService>` 永远为空，`crashed()` 回调也永远不触发。
  - 修法：判返回值，false 时 `Log.w` + `unbind()` + `crashed()`（与异常分支同路）。
  - 清单项：② 韧性 / 失败路径

- [x] **A-05 `Resource.get()` 无超时，服务不可用时调用方永久挂起**
  - `app/src/main/java/com/github/kr328/clash/remote/Resource.kt:15`、`sdk/src/main/java/com/github/kr328/clash/sdk/internal/Resource.kt:14`
  - 缺陷：`suspendCancellableCoroutine` 无 deadline；而 `setAndNotify` 只在 `value != null` 时唤醒等待者，`set(null)`（服务断开）根本不唤醒。
  - 触发：A-04 或崩溃循环时，`withClash` / `withProfile`（`app/util/Remote.kt:26,47`）里的 `remote.get()` 一直不返回，UI 转圈到用户杀进程。
  - 修法：`withTimeout(15s)` 包住等待；超时抛 `TimeoutCancellationException`（属 `CancellationException`，只取消该调用不炸进程），`invokeOnCancellation` 已负责摘除 callback，无泄漏。
  - 清单项：② 超时

- [x] **A-06 `SecureStorage.init` 无兜底，Keystore 异常导致启动崩溃循环**
  - `app/src/main/java/com/github/kr328/clash/MainApplication.kt:46`
  - 缺陷：`Application.onCreate` 里全文只有这一处没被 `runCatching` 包住；AndroidKeyStore 在部分 OEM ROM / 锁定状态下会抛。
  - 触发：`onCreate` 抛异常 → 进程起不来 → 用户只能清数据。
  - 修法：`runCatching` + `Log.w`。已核实这不是“把崩溃往后推”：全仓 grep `SecureStorage.(encrypt|decrypt|init)` 只命中这一行，`encrypt`/`decrypt` 没有任何 Kotlin 调用者。
  - 顺带：`if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)` 在 minSdk 26 下恒真，属死代码，已删。
  - 清单项：② 韧性

- [x] **A-07 应用锁把所有错误码都当“拒绝”，能把用户永久锁在门外**
  - `app/src/main/java/com/github/kr328/clash/util/AppLockController.kt:49`
  - 缺陷：`onAuthenticationError` 对任意 `errorCode` 一律 `resume(false)`，而调用方 `BaseActivity.onCreate` 拿到 false 就 `finish()`。
  - 触发：设备既无已录入生物特征、又无锁屏凭据（或 `prompt.authenticate` 直接抛）→ 门永远过不去，唯一恢复手段是清应用数据，配置全丢。
  - 修法：出错时用 `canAuthenticate(activity)` 反查这道门在本机**是否还可能被满足**；不可满足则记日志、`appLockEnabled = false` 放行（门锁不上时挡着用户等于零收益纯损失），可满足才算拒绝。顺带把原本零调用者的 `canAuthenticate` 接上了线。
  - 清单项：② 资源/降级 + 移动端“无法强制升级、不能把用户锁死”

- [x] **A-08 崩溃报告门裸 `return`，回来后是一个永久空白窗口**
  - `app/src/main/java/com/github/kr328/clash/BaseActivity.kt:143`
  - 缺陷：`presentPendingLumenCrashReportIfNeeded()` 返回 true 时直接 `return`——不 `setContentView`、不跑 `main()`、也不 `finish()`（`LumenCrashHost.kt:33` 注释说明**故意不 finish**，因为在某些 ROM 上会闪退）。
  - 触发：用户关掉崩溃报告页回到这个 Activity，它没有内容视图、没有 Design、`main()` 从未启动，`onStart` 还会照常注册广播观察者——一个不可交互的白屏。
  - 修法：置 `crashGateDeferred` 标记；`onStart` 里若 `isLumenCrashReportPending()` 仍为真就保持休眠（避免立刻 `recreate()` 再次撞门形成重建死循环），报告已被消费才 `recreate()` 重建。
  - 清单项：② 失败路径 + 移动端交互响应

- [x] **A-09 `main()` 无错误边界，异常只进日志，用户看到静默白屏**
  - `app/src/main/java/com/github/kr328/clash/BaseActivity.kt:167`
  - 缺陷：`launch { main() }` 的 `CoroutineExceptionHandler`（:47）只做 `Log.w` + `LumenCrash.record`，不给用户任何反馈、也不收拾界面。
  - 触发：子类 `main()` 在挂 Design 之前抛异常（例如 Binder 调用失败）→ 界面停在空白，用户以为卡死。
  - 修法：`try/catch`（`CancellationException` 原样抛出）；`design == null`（从未绘制）时 `finish()`，已绘制时 `showExceptionToast(e)` 保留可用界面。
  - 清单项：② 降级

- [x] **A-10 legacy 迁移不幂等，中断重试会产生重复配置**
  - `service/src/main/java/com/github/kr328/clash/service/data/migrations/LegacyMigration.kt:26-44`
  - 缺陷：失败时**保留旧库等下次启动重试**，但每行都是 `generateProfileUUID()` + `insert` 的纯追加写，没有幂等键、也没有记录“这行已迁过”。
  - 触发：迁移到第 5 条时抛异常 → 前 4 条已入库、旧库仍在 → 下次启动重跑，前 4 条再插一遍，用户看到重复 profile。
  - 建议修法：逐行迁移成功后从 legacy 表删除该行（需以读写方式打开旧库，且先把 cursor 读进 list 再删，避免边遍历边删）；表空即 `deleteDatabase`。
  - 清单项：① 幂等
  - 未改原因：这是一条无法本地验证的历史迁移路径，改写遍历+删除逻辑的风险高于收益，需单独一次带 CI 的改动来做。A-01 已消除其中最严重的“数据永久丢失”后果。

- [x] **A-11 迁移副作用挂在 `companion object` 的 `init` 里**
  - `service/src/main/java/com/github/kr328/clash/service/data/Database.kt`
  - 缺陷：`companion object { init { Global.launch(Dispatchers.IO) { LEGACY_MIGRATION(...) } } }`——类静态初始化即触发迁移。
  - 触发：**任何进程**第一次触到 `Database` 这个类（包括只是想读一条记录）就异步启动整套 legacy 迁移，与 `Room.databaseBuilder(...).build()` 竞争同一个库文件；时序由类加载顺序决定，不可控、不可测、不可禁用。
  - 建议修法：把迁移提到显式的启动步骤（`MainApplication.onCreate` 的 `:background` 分支或 `RemoteService` 启动时），只在一个进程里跑一次。
  - 清单项：① 并发 + 第 9 章“聪明过头的丑”
  - 未改原因：涉及跨进程启动时序，需与 A-10 一起在单独改动中处理。





- [x] **A-12 服务互斥保护跑穿了 `onDestroy`，输家把赢家的全局状态擦掉 (g3)**
  - `service/src/main/java/com/github/kr328/clash/service/ClashService.kt:79-82,101-111`、`TunService.kt:96-101,115-132`
  - 缺陷：互斥判定（"已有一个在跑就自杀"）走的是正常的 `stopSelf` → `onDestroy` 路径，而 `onDestroy` 里做的是**全局**清理：`serviceRunning = false`、`lastError = null`、删掉开机自启锁、广播 `CLASH_STOPPED`。
  - 触发：隧道正常运行时又被拉起一次服务，输家自杀顺手把赢家的状态清了——UI 显示已停止、开机自启失效，而隧道其实还在转发。反向顺序更糟：`TunService.onDestroy` 会调 `TunModule.requestStop()`（`Clash.stopHttp/stopTun`）并清空 `tunneledPackages`，直接把在跑的隧道拆了。
  - 修法：加 `ownsGlobalState` 标记，只有真正抢到所有权的实例才在 `onDestroy` 清理；`serviceRunning` 改为记录持有者身份（Service + startId）而不是布尔。
  - 清单项：① 并发 + 事务边界

- [x] **A-13 定时订阅更新是崩溃路径而不是功能路径 (g3)**
  - `service/src/main/java/com/github/kr328/clash/service/ProfileReceiver.kt:39-43`
  - 缺陷：`AlarmManager.set(RTC, ...)`（不精确、不唤醒）→ `onReceive` → `startForegroundServiceCompat`，**没有任何 try/catch**。
  - 触发：Android 12+ 从后台启动前台服务抛 `ForegroundServiceStartNotAllowedException`，直接崩 `:background`。也就是说这个功能每次到点都在赌。
  - 修法：调用点 `runCatching` 兜住并重排 alarm；`startForegroundServiceCompat` 内部也要吞异常并返回是否成功（见 A-23）。`setExactAndAllowWhileIdle` 需要 `SCHEDULE_EXACT_ALARM` 权限，涉及清单与商店审核，留待决策。
  - 清单项：② 韧性

- [x] **A-14 一次瞬时失败就永久停掉该订阅的自动更新，且完全无声 (g3)**
  - `service/src/main/java/com/github/kr328/clash/service/ProfileWorker.kt:104-118`
  - 缺陷：`scheduleNext` 只在成功路径上调用。
  - 触发：机场返回一次 5xx 或网络抖动 → 这条订阅此后**再也不会**自动更新，用户以为还在更新，配置早已过期（对本项目而言等于永久失联）。
  - 修法：`scheduleNext` 移到 `finally`；失败按 1/5/15/60 分钟指数退避，上限不超过该订阅自身的更新间隔。
  - 清单项：② 重试 + 移动端"网络不可靠"

- [x] **A-15 `globalLock` 无超时，先停后启永久卡在"加载中" (g3)**
  - `service/src/main/java/com/github/kr328/clash/service/clash/ClashRuntime.kt:23-27`
  - 缺陷：`globalLock.withLock { }` 没有 deadline；停止流程只发广播 + `cancelAndJoinBlocking()`，而 `util/Coroutine.kt:14` 的 `cancelAndJoinBlocking` 实际**只 cancel、不 join**（名不副实，见 C-05）。
  - 触发：旧 runtime 的 `finally` 里 `NonCancellable` 的 `Clash.reset()` 仍持有锁 → 新 runtime 永远拿不到 → 前台通知永久停在"加载中"，用户只能强杀。
  - 修法：拿锁加约 10s 超时，超时走可见失败路径（`LoadException` + `stopSelf`）而不是无声挂死。
  - 清单项：② 超时

- [x] **A-16 广告拦截事件无界队列 + 每条一次 Binder 往返，能 OOM 掉内核进程 (g3)**
  - `service/src/main/java/com/github/kr328/clash/service/ClashManager.kt:231`
  - 缺陷：`Clash.subscribeAdblock()` 是 `Channel(UNLIMITED)`，每收到一条就跨进程转发一次。
  - 触发：广告密集页面瞬间产生大量事件 → 队列无界增长 → `:background` OOM → **隧道被一起带走**。这是第 24 章"资源上限"里最典型的无界队列。
  - 修法：`Channel(64, BufferOverflow.DROP_OLDEST)` + 约 500ms 批处理合并；精确总数走 `queryAdblockStats` 查询而不是靠事件流累加。
  - 清单项：② 资源上限 + ③ 扇出

- [x] **A-17 `ProfileManager` 的启动副作用挂在裸 scope 上，能造成"打开应用就崩"死循环 (g3)**
  - `service/src/main/java/com/github/kr328/clash/service/ProfileManager.kt:31-37`
  - 缺陷：`init { launch { Database.database; ProfileReceiver.rescheduleAll(context) } }` 跑在裸 `CoroutineScope(Dispatchers.IO)`（无 SupervisorJob、无 handler）。
  - 触发：Room 打开失败即崩 `:background`；而 `RemoteService` 每次前台绑定都会重建 `ProfileManager` → 打开应用就崩的循环。即使不崩，被取消的 scope 会让此后该类所有 `launch` **静默失效**（这个类的全部写操作都靠它）。
  - 修法：`SupervisorJob()` + 记日志的 `CoroutineExceptionHandler`；`init` 两步各自 `runCatching`，一步失败不影响另一步。
  - 清单项：② 韧性 + 第 9 章"聪明过头"

- [x] **A-18 `Module.execute()` 无 catch，任一 Module 抛异常就杀掉整个 VPN (g4)**
  - `service/src/main/java/com/github/kr328/clash/service/clash/module/Module.kt:58-76`
  - 缺陷：只有 `try { run() } finally { }`，没有 `catch`；而 `ClashRuntime.install()` 用的是裸 `launch`（无 SupervisorJob、无 handler），异常上传取消父 job → `ClashService` 的 finally `stopSelf()`。十几个 Module 里只有 `TrafficHistoryModule` / `SceneModule` / `CaptureModule` 自建了边界——同一机制下自我防护不一致。
  - 触发：用户删掉通知渠道 → `StaticNotificationModule` 的 notify 抛 `SecurityException`；或内核停止瞬间 `Clash.queryTrafficNow()` 抛；或 `TunModule` 建 `VpnService.Builder` 失败。任一情况 VPN 直接断连，日志只留一行 `module destroyed`。
  - 修法：`execute()` 补 `catch(Throwable)`（`CancellationException` 原样抛出），单 Module 失效只降级；并让 `install` 的 launch 挂在 SupervisorJob 下。
  - 清单项：② 韧性 / 故障隔离

- [x] **A-19 场景不再匹配时只清 `lastApplied`，不回滚已下发的 override (g4)**
  - `service/src/main/java/com/github/kr328/clash/service/clash/module/SceneModule.kt:94-97`
  - 缺陷：`SceneEngine.resolve()` 返回 null 时只 `lastApplied = null; return`，从未对 `Clash.patchOverride(OverrideSlot.Session, ...)` 做反向操作——**有下发路径，没有撤销路径**。
  - 触发：配了"家里 Wi-Fi → 直连"，离家切蜂窝后 resolve 返回 null，Session slot 里的 Direct 仍生效：用户在外部网络**全程明文直连**，UI 显示的模式与实际不一致，必须手动改一次模式才恢复。
  - 修法：`apply()` 前记录原始 mode / `ProfileProcessor.active`，`match == null` 且 `lastApplied != null` 时恢复原值或清空 Session slot。
  - 清单项：① 一致性 + ④ 安全

- [x] **A-20 `CaptureStore.enqueue` 对 `Any` 做 reified 序列化，抓包文件恒为空 (g4)**
  - `service/src/main/java/com/github/kr328/clash/service/util/CaptureStore.kt:92-100`
  - 缺陷：`enqueue(type: String, payload: Any)` 内部 `json.encodeToString(payload)`，reified 实参是 `Any`，kotlinx.serialization 取不到序列化器，**每次必抛** `SerializationException`，异常被 `CaptureModule` 的 catch 吞掉。
  - 触发：不需要特殊时序。UI 显示"抓包运行中"，产出的 `capture-<ts>.jsonl` 恒为 0 字节——**该功能自交付起从未工作过**。
  - 修法：`enqueue` 改收具体类型或显式 `KSerializer<T>`，DNS / connection / HTTP 三处各调对应重载。
  - 清单项：② 韧性 + 第 9 章"半成品"

- [x] **A-21 `runCapture()` 写在广播消费循环体内，`stop_capture` 永远读不到 (g4)**
  - `service/src/main/java/com/github/kr328/clash/service/clash/module/CaptureModule.kt:34-60`
  - 缺陷：`for (intent in broadcasts) { ... runCapture() ... }`——`runCapture()` 是长驻挂起函数却在循环体内执行，循环体不返回就永远回不到 `receive()`。
  - 触发：点"开始抓包"再点"停止抓包"：停止广播已进 channel，主循环卡在 `runCapture` 里永不消费。抓包只能靠停掉整个 VPN 结束；期间 UNLIMITED channel 持续堆积、文件写到 10MiB 上限。
  - 修法：`runCapture()` 起独立 job，主循环按 action 分派 start / stop，stop 时 cancel 该 job 并关 writer。
  - 清单项：② 韧性

- [x] **A-22 `-assumenosideeffects` 删掉 Kotlin 空检查，只有混淆后的 release 才崩 (g6)**
  - `app/proguard-rules.pro:25-33`
  - 缺陷：`-assumenosideeffects` 作用在 `kotlin.jvm.internal.Intrinsics` 的空检查方法上，把 release 包里的参数空检查整段删掉。
  - 触发：debug 抛一个位置精确的 NPE；release 把 null 继续往下传，崩在离真实原因很远的地方。典型症状是"CI 全绿、debug 正常，只有商店版本崩"——这是最难查的一类，而且是自己主动制造的。
  - 修法：删掉针对 Intrinsics 的整个 `-assumenosideeffects` 块。
  - 清单项：② 可观测性 + 第 9 章"聪明过头"

- [x] **A-23 `startForegroundServiceCompat` 在 API 31+ 无防护 (g6，与 A-13 同根)**
  - `common/src/main/java/com/github/kr328/clash/common/compat/Services.kt:11-13`
  - 缺陷：直接透传 `startForegroundService`，Android 12+ 后台启动抛 `ForegroundServiceStartNotAllowedException` 崩调用进程。
  - 修法：内部吞异常 + 记日志，用 `Boolean` 返回值告知调用方是否真的起来了，让 A-13 的调用点能做降级。
  - 清单项：② 韧性

- [x] **A-24 `Design` 的协程作用域既无 SupervisorJob 也无 handler，一个 dialog 能崩进程 (g6)**
  - `design/src/main/java/com/github/kr328/clash/design/Design.kt:15-16`
  - 缺陷：`Design` 实现 `CoroutineScope` 但 `coroutineContext` 裸装。Activity 销毁过程中弹 dialog 抛 `IllegalStateException`，取消同级协程后经 `Dispatchers.Unconfined` 直达默认 handler → **进程崩溃**。
  - 触发：旋转屏幕 / 返回键与异步结果竞争的瞬间弹窗，是常规操作而非边角情况。
  - 修法：`SupervisorJob() + Dispatchers.Main.immediate` + 只记日志的 `CoroutineExceptionHandler`，与 `BaseActivity`（:47-62）和 `common/Global.kt` 对齐。
  - 清单项：② 故障隔离 + 第 9 章"不一致的丑"

- [x] **A-25 `sdk` 的 `Resource.get()` 无超时（g6 独立确认了 A-04 / A-05）**
  - `sdk/src/main/java/com/github/kr328/clash/sdk/internal/Resource.kt:14-28`、`sdk/.../ClashRuntime.kt:285,309`
  - g6 独立复现了同一条缺陷链（绑定失败 → `get()` 永久挂起），与本文 A-04 / A-05 结论一致；已修。
  - 未采纳的追加建议（另记为 B 项）：补 `onBindingDied` / `onNullBinding` 回调。
  - 清单项：② 超时

- [x] **A-26 `common/util/Global.kt` 顶层 `val packageName` 类初始化即求值 (g6)**
  - `common/src/main/java/com/github/kr328/clash/common/util/Global.kt:5`
  - 缺陷：顶层属性在类初始化时求值，依赖 `Global.application` 已赋值。
  - 触发：任何进程若在 `application` 设好之前触到这个文件 → `UninitializedPropertyAccessException` → 包成 `ExceptionInInitializerError` → **此后该文件所有成员访问都变成 `NoClassDefFoundError`，永久性，且真实原因彻底丢失**。多进程应用里"哪个进程先触到哪个类"本就不受控。
  - 修法：改惰性求值（`get()` / `by lazy`），并让未初始化时抛出可读消息。
  - 清单项：② 韧性 + ① 启动时序

- [x] **A-27 五处 `subscribe*` 回调在 Go 原生线程上用严格 `Json` 解码，无异常边界 (g5)**
  - `core/src/main/java/com/github/kr328/clash/core/Clash.kt:284-295,313-318,334-339,353-358,390-395`
  - 缺陷：回调体直接 `Json.decodeFromString`。内核输出格式一变（新增字段、类型变化）就抛 `SerializationException`，而这是从**原生线程**穿出 JNI 边界的异常，行为不可控。同文件 :148 / :268 / :367 的邻居**已经**用了 `runCatching`。
  - 修法：五处各加 `runCatching` + `Log.w`（丢弃该条事件）；并确认这些 `Json` 开了 `ignoreUnknownKeys`。
  - 清单项：② 韧性 + ① 一致性（内核与客户端各自演进）

- [x] **A-28 `queryOverride` 靠"销毁数据"来降级 (g5)**
  - `core/src/main/java/com/github/kr328/clash/core/Clash.kt:241-251`
  - 缺陷：解码失败时返回一个**空的** `ConfigurationOverride`；调用方拿到后紧接着的 `patchOverride` 就把这份空值写回磁盘。
  - 触发：一次解析失败 = 用户全部覆盖配置被清零，不可恢复。同形状的错误还出现在 `SceneStore.decode`。这是移动端"本地 DB 是真相的一份副本"原则的反面：副本坏了就把真相也擦了。
  - 修法：失败必须让调用方知道（抛出或返回可空），绝不能返回"看起来正常的空值"。
  - 清单项：① 数据一致性

- [x] **A-29 场景枚举把常量名当持久化格式，重命名/降级即静默重置用户全部场景 (g5，修复由 core/data 组完成)**
  - `service/src/main/java/com/github/kr328/clash/service/model/Scene.kt:22-27,46-51`
  - 缺陷：枚举被 kotlinx.serialization 按常量名写进 SharedPreferences，没有任何 `@SerialName`。
  - 触发：以后重命名一个常量，或用户降级到旧版本遇到新常量 → 解析失败 → 用户配的全部场景静默重置为默认。移动端**无法强制升级**，降级读新数据是必然会发生的事。
  - 修法：补 `@SerialName("稳定字符串")`（值须与当前实际写入的字符串一致，否则读废现有数据）；解码侧对未知常量应跳过该场景而不是整表失败。
  - 清单项：① 一致性 + 移动端 API/数据长期向后兼容

- [ ] **A-30 `ConnectionSnapshot` 整体过 Parcel，连接数一多必炸 (g5)**
  - `core/src/main/java/com/github/kr328/clash/core/model/Connection.kt:52-73`
  - 缺陷：整个快照一次性 parcel，没有 `writeToParcelSlice`——而它的邻居 `ProviderList` / `ProxyGroup` **都有**分片实现。
  - 触发：1000+ 连接（P2P、大量并发请求）时 `TransactionTooLargeException`，连接页直接不可用。
  - 修法：照邻居补分片。
  - 清单项：③ 分页上限 + 第 9 章"不一致的丑"

- [x] **A-31 含私钥的导出包永久留在 `cacheDir`，且 `cachedBundle` 从不失效 (g5)**
  - `service/src/main/java/com/github/kr328/clash/service/migration/MigrationProvider.kt:72-96,110-112`
  - 缺陷：导出 zip 内含 `ageSecretKey`，写进 `cacheDir` 后没有任何清理；`cachedBundle` 也不会随数据变化失效。
  - 触发：任何能读到 `cacheDir` 的场景（root、备份提取、厂商日志工具）都能拿到私钥；用户导出一次后私钥就长期躺在磁盘上。二次导出还可能拿到过期内容。
  - 修法：用后即删（或 `deleteOnExit` 语义 + 显式清理），`cachedBundle` 加失效条件。
  - 清单项：④ 密钥管理

- [x] **A-32 迁移直写 `getSharedPreferences("service")`，绕过 `PreferenceProvider` (g5)**
  - `service/src/main/java/com/github/kr328/clash/service/migration/MigrationBundle.kt:76-87,213-227,300-329`
  - 缺陷：从**主进程**直接写属于 `:background` 的偏好文件，绕开了整套 `PreferenceProvider` 跨进程通道。
  - 触发：导入的设置对 `:background` 不可见（它持有自己的内存缓存），或被 `:background` 的下一次写入整体覆盖——"导入成功了但没生效"。
  - 修法：迁移写入统一走 `PreferenceProvider`。
  - 清单项：① 跨进程一致性

- [x] **A-33 `SecureStorage.init` 在每个进程的主线程上跑 Keystore（g1 对 A-06 的补充）**
  - `app/src/main/java/com/github/kr328/clash/MainApplication.kt:46-48`
  - 补充事实：除了"没有兜底"（A-06 已修），它还**在主线程**执行，并且在**每个进程**都执行（含 `:background`）。AndroidKeyStore 首次生成密钥可能耗数百毫秒，等于给每次冷启动和每次内核进程拉起都加一段主线程阻塞。
  - 修法：改成首次真实读写时在 IO 线程惰性初始化；并按进程收敛（内核进程不需要它）。
  - 未改原因：A-06 已消除崩溃后果；改成惰性需要先给 `encrypt`/`decrypt` 找到真实调用方（目前零调用者，见 B 区"半成品"条目），应与那件事一起做。
  - 清单项：② 韧性 + ③ 尾延迟

  - 已修：整个 `SecureStorage`（含 KDoc 中不一致的加密文档/私钥 URL）连同 `MainApplication.onCreate` 里的 `init` 调用一并删除——主线程/每进程 AndroidKeyStore 初始化消失，A-06/B-34 的"无调用方"结论在此终结。
- [ ] **A-34 广告命中记录 `readLines()` 全量读入，重度用户必 OOM 且不可恢复 (g1)**
  - `app/src/main/java/com/github/kr328/clash/AdblockHitsActivity.kt:110-122`
  - 缺陷：`loadHistory()` 对 `filesDir/clash/adblock_hits.jsonl` 做 `readLines()`，而这个文件是**只追加、从不轮转**的。
  - 触发：文件只会变大，所以一旦大到读不动，这个页面就**永久**打不开——没有任何应用内恢复手段。页面用的 `Channel(UNLIMITED)` 也放大了内存压力。
  - 修法：轮转必须在 Go 侧做（`adblock_hits.go`，跨组改动）；app 侧改为从文件尾部反向分页读取，并给内存列表设上限，Channel 改 CONFLATED 或有界。
  - 未改原因：根因在 Go 侧且跨组，需要与内核侧一起改。
  - 清单项：③ 规模 + ② 资源上限

- [x] **A-35 七个页面在挂 Design 之前做跨进程取数，内核不可用时是一张无骨架的空白页 (g1)**
  - `app/src/main/java/com/github/kr328/clash/ProxyActivity.kt:19-20`（同型：`ProvidersActivity.kt:16`、`MetaFeatureSettingsActivity.kt:24`、`OverrideSettingsActivity.kt:26`、`AutomationSettingsActivity.kt:13`、`FilesActivity.kt:22-23`、`PropertiesActivity.kt:29-33`）
  - 缺陷：`main()` 第一行就 `withClash { ... }`，而 `BaseActivity` 在 `setContentDesign` 之前从未 `setContentView`。
  - 触发：`:background` 刚被 OEM 省电策略杀掉（或正在冷启动）时点"代理"，取数要么挂起要么抛出，两条路都走不到 `setContentDesign`——用户看到只有主题背景色的空白页，没有错误说明也没有重试入口。A-05 的超时把"永久挂起"变成异常、A-09 把"静默白屏"变成 `finish()`，但"没有骨架与错误态"这一层七个页面都还在。
  - 修法：先 `setContentDesign` 出骨架再异步取数（Design 已有 loading/toast 能力），超时或重试耗尽后进入带重试按钮的错误态。
  - 清单项：② 降级 + 移动端交互响应

  - 部分已修（diff 核过）：七个页面全部不再裸跨进程取数——`PropertiesActivity`/`FilesActivity` 先 `setContentDesign` 再取数；`ProvidersActivity`/`AutomationSettingsActivity`/`ProxyActivity` 取数失败时挂空骨架并抛给 BaseActivity 显示错误；`MetaFeatureSettingsActivity`/`OverrideSettingsActivity` 作为配置编辑页刻意不走骨架——展示可编辑的默认值会把真实 override 覆盖成空白，失败时 toast + finish。核心缺陷「无骨架的空白页」在全部七页消除。
- [x] **A-36 `verifyApp` 在 IO 线程调 `Activity.finish()`，并与主线程并发改同一个集合 (g2)**
  - `app/src/main/java/com/github/kr328/clash/remote/Remote.kt:62-79`
  - 缺陷：`Global.launch(Dispatchers.IO) { verifyApp() }`，而 APK 校验失败分支直接 `ApplicationObserver.createdActivities.forEach { it.finish() }` 再 `startActivity`；`createdActivities` 是普通 `mutableSetOf`，同时被主线程的生命周期回调增删。
  - 触发：应用升级后首次启动（`store.updatedAt != lastUpdateTime`）且 `verifyApk()` 判定被二次打包时，用户正在页面间跳转 → IO 线程遍历集合撞上主线程 `onActivityCreated/Destroyed` 得到 `ConcurrentModificationException`，或从非主线程 `finish()` 触发未定义行为。防篡改路径本身成了崩溃路径。
  - 修法：`verifyApp()` 只做 IO 判定并返回布尔，收尾用 `withContext(Dispatchers.Main)` 执行 finish/startActivity；集合改同步容器或对外返回拷贝。
  - 清单项：② 韧性 + ① 并发

  - 产品决策，仅文案对齐：应用锁是内容可见性防护（配置/订阅信息），不拦截快捷设置/快捷方式这类一键开关；`values/` 与 `values-zh/` 的 `app_lock_desc` 文案已更新为准确表述。
  - 已修（此条在更早批次 fb8782a8 提交中完成，本次补勾）：`verifyApp()` 的 IO 判定与 `withContext(Dispatchers.Main)` 收尾分离，`createdActivities.toList()` 拷贝后再遍历。
- [x] **A-37 应用锁只覆盖 `BaseActivity`，两个控制入口可绕过 (g2)**
  - `app/src/main/java/com/github/kr328/clash/util/AppLockController.kt:107`（绕过者 `ExternalControlActivity.kt:22`、`InternalControlActivity.kt:14`）
  - 缺陷：`isUnlockRequired` 只在 `BaseActivity` 的生命周期里被调用，而这两个 Activity 直接继承 `Activity()`，完全不经过这道闸——它们能启停 VPN、切换配置，其中 `ExternalControlActivity` 在清单里是 exported。
  - 触发：开启应用锁后，第三方应用用深链拉起 `ExternalControlActivity`，或用户点桌面 widget 进 `InternalControlActivity`，无需任何生物识别即可开关代理与切配置。锁只挡住了老实走首页的人。
  - 修法：把解锁判定下沉到所有可被外部触发的入口共用的位置（共同基类，或一个前置 gate Activity 转发）；确需免锁的动作显式列白名单并写清理由。
  - 清单项：④ 鉴权与越权（A-07 修的是"把用户锁死"，这条是反向的"锁不住"）
  - **协调者定夺：不代拍，等产品决定**。技术上补闸门很容易（把 `isUnlockRequired` 提到两个入口，或加一个前置 gate Activity 转发），但这一改会直接砸掉两个明确的产品承诺：桌面 widget / 快捷方式"一键开关"和第三方深链自动化（Tasker、MacroDroid 这类）。"开了应用锁之后一键开关还该不该免锁"是取舍题不是对错题 —— 两种答案都自洽：
    - **按锁优先**：所有入口一律要解锁，widget 与深链退化成"先跳解锁页再执行"，自动化基本失效。
    - **按便利优先**：显式声明"应用锁只保护配置与订阅信息的可见性，不保护开关动作"，把这句写进设置项说明，让用户知道自己买到的是什么 —— 这条至少要做，因为现在的文案让人以为锁住了一切。
  - 建议先落**文案对齐**（零风险、消除误解），闸门收紧与否等用户回话。

  - 产品决策，仅文案对齐：应用锁是内容可见性防护（配置/订阅信息），不拦截快捷设置/快捷方式这类一键开关；`values/` 与 `values-zh/` 的 `app_lock_desc` 文案已更新为准确表述。
- [x] **A-38 下载无超时 + `NonCancellable` + 全局锁，整个配置子系统可永久死锁 (g3)**
  - `service/src/main/java/com/github/kr328/clash/service/ProfileProcessor.kt:30,32-34,110-112,130-132,195-220`
  - 缺陷：`apply` / `validate` / `update` 三条路径都是 `withContext(NonCancellable) { processLock.withLock { ... fetchProfile(...) } }`；`fetchProfile` 转发到 `Clash.fetchAndValid`（`core/.../Clash.kt:179-187`），Kotlin 侧不带任何超时参数，`NonCancellable` 又让调用方的取消完全无效。
  - 触发：订阅域名被阻断、TCP 连接建成后不返回响应（被墙的机场很常见）或极慢链路上的大文件 → `commit()` 这个 suspend IPC 永不返回（"保存"一直转圈且返回键无效），`processLock` 永不释放：其后的手动更新、自动更新（`ProfileWorker` 阻塞 → 前台通知常驻）、配置校验全部排在锁后面，只能杀进程恢复。是否挂死完全取决于 Go 侧 HTTP 客户端有没有自己的超时，Kotlin 侧零兜底。
  - 修法：`fetchProfile` 外层 `withTimeout`（如 120s，总时长与"无字节进展"分开计）；`NonCancellable` 收缩到落库 + 目录替换这段真正的临界区，下载阶段必须可取消；`processLock` 改带超时的加锁，拿不到锁对调用方返回"正忙"。
  - 清单项：② 超时 + ① 事务边界

- [x] **A-39 legacy 迁移失败只打一行 `Log.w`，用户看到"配置全没了"却没有任何提示 (g5)**
  - `service/src/main/java/com/github/kr328/clash/service/data/migrations/LegacyMigration.kt:42-44`
  - 缺陷：`catch` 里只 `Log.w` 就正常返回，函数返回 `Unit`，调用点（`Database` 的 init 块）不检查任何结果，异常被完全吞掉。
  - 触发：旧库被截断、权限异常、或 `profiles` 表列名不符预期 → 用户升级后配置列表空白，应用一切正常、无提示、无重试入口，只有 logcat 里一行 W。对"配置写坏 = 永久失联"的应用，这是最不该静默的一条路径。
  - 修法：迁移返回 `Result` 或写入持久化失败标志，由 UI 在下次启动时提示"旧配置迁移失败，可重试 / 导出旧库"；同时上报到崩溃与事件统计。
  - 清单项：② 可观测性 + ① 数据一致性
  - 落地：失败标记存 `ServiceStore.legacyMigrationFailed`，`MainActivity.maybeShowLegacyMigrationFailureToast` 在下次 `ActivityStart` 读取并清除后弹 toast（`legacy_migration_failed`，en/zh/zh-rTW/zh-rHK）。崩溃上报未接。

- [x] **A-40 v1 迁移路径无条件删源文件，会删掉用户放在共享存储上的原始配置 (g5)**
  - `service/src/main/java/com/github/kr328/clash/service/data/migrations/LegacyMigration.kt:181-193`
  - 缺陷：`legacyFile` 取的是旧库 `file` 列里的**绝对路径**；`:183-187` 只在 `newType == Profile.Type.File` 时 `copyTo`，但 `:193` 的 `legacyFile.delete()` 在循环体里**无条件执行**，对 `Type.Url` 的行也删。同功能的 `migrationFromLegacy234`（`:108-113`）只 copy 不 delete，两条路径策略不一致。
  - 触发：旧版本持有 `WRITE_EXTERNAL_STORAGE` 时这个路径完全可能指向 `/sdcard` 下用户自己放的 `config.yaml` —— 迁移完成后用户手里的原件消失，且不在应用可恢复的范围内。
  - 修法：删除动作限定在应用私有目录内（`filesDir` 前缀校验），且只对确实复制成功的 File 类型执行；共享存储上的源文件一律保留；两条 legacy 路径对齐。
  - 清单项：④ 数据安全（A-01 只调整了删除时序，没有限制删除范围）



### B. 中等

<!-- SECTION-B -->

> 本节按子代理分组收录，格式为「位置 → 缺陷 → 触发 → 修法」四行压缩式。
> 交付完整度：六份分组报告均已落盘于 `.audit-reports/`（临时目录，不入库），本表已按报告文件内容补齐 —— g1/g2/g3/g5/g6 早前因消息转述而截断的尾部条目已全部录入。

#### B-1 app · UI 层 (g1)

- [x] **B-01 `clashStarting` 无看门狗，内核进程被杀后开关永久停在"启动中"**
  - `app/src/main/java/com/github/kr328/clash/MainActivity.kt:44-45`
  - 触发：`:background` 在内核初始化期间被系统杀掉 → 成功与失败两条广播**都不会**到 → 开关永远转圈，用户只能强杀应用。
  - 修法：加 20-30s 可取消的看门狗，超时复位状态并提示。
  - 已修：`armClashStartWatchdog()`（25s，`CLASH_START_TIMEOUT_MILLIS`），挂在 Activity scope，`ClashStart`/`ClashStop`/手动停止三处取消；VPN 授权被拒后补 `return`。

- [x] **B-02 "停隧道 → 改名单 → 重启隧道"的补偿逻辑挂在 Activity 协程里**
  - `app/src/main/java/com/github/kr328/clash/AccessControlActivity.kt:38-64`
  - 触发：用户在 `withTimeoutOrNull` 的 delay 期间退出页面 → `activityJob.cancel()` → 隧道已停但**永远不会重启**。这是典型的"补偿动作与发起方同生命周期"。
  - 修法：合并成一次服务端原子请求；或至少放到 Global scope + `NonCancellable` 并给出通知。
  - 已修：抽出 `restartClashForAccessControl()`，`Global.launch(NonCancellable)` + `applicationContext`，超时走 Toast 提示；`defer` 在第一次挂起前读 `clashRunning`。残留：真正的"一次原子请求"仍需服务端接口，未做。

- [x] **B-03 剪贴板导入直接解引用 `getItemAt(0).text`**
  - `app/src/main/java/com/github/kr328/clash/AccessControlActivity.kt:130-143`
  - 触发：剪贴板里是图片/URI 等非文本内容时 `text` 为 null（平台类型不受 Kotlin 空安全保护）→ NPE 静默带走整个页面。同仓 `NewProfileActivity.kt:282` 用的是正确的 `coerceToText`——一个仓库两种写法。
  - 修法：统一用 `coerceToText`。
  - 已修：改用 `coerceToText`，与 `NewProfileActivity` 一致。

- [x] **B-04 预设点击对每个应用做一次 Binder 往返**
  - `app/src/main/java/com/github/kr328/clash/AccessControlActivity.kt:165-237`
  - 触发：装了 300+ 应用的机器上点一次预设，列表卡死数秒，并有 `TransactionTooLargeException` 风险。
  - 修法：一次批量查询，本地过滤。
  - 已修（两半都合上）：①`installedAppsCache` + `installedApps()` 把全量 PackageManager 枚举与合作方证书扫描降到每个 Activity 实例一次；②`toAppInfo` 不再自己调 `queryIgnoringBatteryOptimizations`，改由调用方传入，`InstalledApps.batteryOptimizationIgnored` 按包名记忆化 —— 排序/筛选每次 reload 不再重付 N 次 DeviceIdleController 往返，显示行为不变。

- [x] **B-05 `onStop` 无条件把未保存的编辑提交掉，"不保存退出"是假的**
  - `app/src/main/java/com/github/kr328/clash/PropertiesActivity.kt:51-59`
  - 触发：用户改了配置属性后按返回/切后台，改动被 `withProfile { patch(...) }` 直接写进去——用户以为放弃了，实际生效了。
  - 修法：只在明确的保存动作里提交；`onStop` 至多做草稿暂存。
  - 已修：删掉 `Event.ActivityStop` 的自动保存分支与 `canceled` 字段。`onBackPressedCompat` 已有 `requestExitWithoutSaving()` 询问，`Request.Commit` 是唯一保存路径——旧分支的效果正是让"放弃"静默保存。

- [x] **B-06 任意非主进程启动都会 `finish()` 掉正在编辑的页面**
  - `app/src/main/java/com/github/kr328/clash/PropertiesActivity.kt:60-63`（源头 `MainApplication.kt:62-67`）
  - 触发：`:background` 因为任何原因被拉起 → `sendServiceRecreated()` → `Event.ServiceRecreated` → `finish()`，用户半填的表单无提示丢弃。
  - 修法：`ServiceRecreated` 在编辑态页面只应刷新数据，不应关闭页面。
  - 已修：`ServiceRecreated` 改为重新查询配置，只有配置真的不存在了才 `finish()`。

- [x] **B-07 `startActivityForResult` 用不可取消的 `suspendCoroutine`，旋屏泄漏 Activity**
  - `app/src/main/java/com/github/kr328/clash/BaseActivity.kt:116-129`
  - 触发：文件选择器打开时旋转屏幕 → 每转一次泄漏一个 Activity，且 SAF 返回的结果丢失（用户选了文件却什么都没发生）。
  - 修法：`suspendCancellableCoroutine` + `invokeOnCancellation` 里 unregister；或改用成员 `registerForActivityResult`。
  - 已修：`startActivityForResult` 与 `setContentDesign` 都换成 `suspendCancellableCoroutine`。不需要 `invokeOnCancellation`——`ActivityResultLifecycle.use` 的 `finally { withContext(NonCancellable) { markDestroy() } }` 本来就会注销，缺的只是"挂起可被取消"这一步。

- [x] **B-72 应用锁超时以"上次解锁时刻"为基准，正常使用中反复弹生物识别 (g1)**
  - `app/src/main/java/com/github/kr328/clash/BaseActivity.kt:317-354`
  - 触发：`AppLockGate.requiresUnlock` 判的是 `now - lastUnlockedAt >= 60_000`，前台连续使用也在计时。开着应用锁读一会儿日志（很容易过 61 秒）再点进"配置"，新 Activity 的 `onCreate` 又弹一次指纹；返回上一页 `onStart` 再判一次，又弹。每次跨页导航都可能重新验证。
  - 修法：判据改为 `lastBackgroundedAt`（用 `ApplicationObserver` 已有的 `appVisible` 变化记录进入后台的时刻），进程内已解锁状态用内存标志承载，只在真正从后台回来且超时后才重新验证。

  - 已修：`ApplicationObserver` 记录 `lastBackgroundedAt` 并在回前台时一次性算出 `backgroundReturnMs`；`AppLockGate.requiresUnlockOnResume` 以「最近一次后台停留时长」为准（0=从未后台不复查，负值=时钟回拨 fail-closed）；`AppLockController.isRecheckRequiredOnResume` + `unlockedInProcess` 会话标志——前台内页面间导航永不重复弹生物识别。
- [x] **B-73 应用锁复归门在内容已贴到窗口之后才验证，受保护内容会进最近任务截图 (g1)**
  - `app/src/main/java/com/github/kr328/clash/BaseActivity.kt:338-354`
  - 触发：`maybeGateOnResume()` 在 `super.onStart()` 之后才启动 `BiometricPrompt`，此时 Design 根视图已渲染；而 `applySecureScreen()` 只看 `uiStore.secureScreen`，开应用锁并不会自动加 `FLAG_SECURE`。在配置列表页按 Home，系统抓走的最近任务缩略图上是完整的订阅地址——"打开最近任务"即可绕过锁。
  - 修法：应用锁开启时与 `secureScreen` 取并集无条件加 `FLAG_SECURE`；需要复归时先遮蔽 Design 根视图，验证通过后再恢复，而不是依赖验证失败后的 `finish()`。

  - 已修：`BaseActivity.applySecureScreen` 取 `secureScreen || appLockEnabled` 并集，应用锁开启时最近任务截图不再泄内容；`maybeGateOnResume` 复检前先把 `design.root.alpha = 0`，通过后恢复——受保护内容不会出现在验证前的窗口帧与快照里。
- [x] **B-74 绑定日志服务用不可取消的 `suspendCoroutine`，且 `conn` 在回调里才赋值 (g1)**
  - `app/src/main/java/com/github/kr328/clash/LogcatActivity.kt:142-171`
  - 触发：`ServiceConnection` 只在 `onServiceConnected` 里 `conn = this`，而 `onDestroy` 执行 `conn?.apply(this::unbindService)`。点"实时日志"后立刻按返回：`bindService` 已返回 true 但回调未到，`onDestroy` 时 `conn` 仍是 null，什么都没解绑；随后回调到来把 `this` 赋给已销毁的 Activity。结果是 ServiceConnection 永久注册（logcat 报 leaked ServiceConnection）、`LogcatService` 因还有绑定者不被回收、协程栈持有 Activity。
  - 修法：调用 `bindService` **之前**就把 connection 存进字段；改 `suspendCancellableCoroutine` 并在 `invokeOnCancellation` 里调 `unbindServiceSilent`（`util/Service.kt` 已有）。

  - 已修：`LogcatActivity.bindLogcatService` 改 `suspendCancellableCoroutine` + `invokeOnCancellation { unbindServiceSilent(connection) }`；`conn` 在 bindService 调用前就落到字段（onDestroy 与 in-flight bind 竞态不再泄漏 ServiceConnection）。
- [x] **B-75 离开日志页不会停止前台服务，日志文件无上限地一直写 (g1)**
  - `app/src/main/java/com/github/kr328/clash/LogcatActivity.kt:96-134`、`app/src/main/java/com/github/kr328/clash/LogcatService.kt:47-57,164`
  - 触发：`stopService` 只在 `Request.Close` 分支里调用，`onDestroy` 只解绑；而服务由 `startForegroundService` 启动，解绑不会停它。进"实时日志"看几眼直接按返回，服务继续订阅内核日志、`LogcatWriter` 继续往 `cacheDir/logs` 写文件、常驻通知一直挂着，长期挂 VPN 时持续写盘耗电。"是否正在记录"还存在进程内静态 `var` 里，进程重建即失真。
  - 修法：Activity 销毁时若非"用户明确要求后台继续记录"就 `stopService`；要保留后台记录能力则给 writer 加单文件与总量上限、在常驻通知上提供"停止记录"动作，并把记录状态改成持久状态。

  - 已修：`LogcatActivity.onDestroy` 除 unbind 外还 `stopService(LogcatService::class.intent)`——离开页面即停前台服务，日志文件不再无限写。（剩余：常驻通知"停止记录"按钮与已记录状态持久化为新 UI，未做——核心缺陷"离开页面后日志文件无限写"已消除）
- [x] **B-76 geo 数据库导入非原子、无大小校验，落盘文件名与内核实际读取的不一致时仍报成功 (g1)**
  - `app/src/main/java/com/github/kr328/clash/MetaFeatureSettingsActivity.kt:90-136`（内核实际消费的名字见 `MainApplication.kt:193-198` 的 `GEO_ASSETS`）
  - 触发：直接 `FileOutputStream(File(clashDir, outputFileName))` 边下边写，没有临时文件 + rename。导入 200MB mmdb 写到一半被杀或存储写满，目标文件留下截断内容，内核加载失败，而 `extractAsset` 的 `exists()` 检查管的是另一个文件名，不会修复。另一条：用户导入 `geoip.dat` 落盘成 `geoip.dat`，内核读的是 `geoip.metadb`，文件永远不被读，界面照样弹"导入成功"。`contentResolver.query` 还在主线程。
  - 修法：写临时文件再 `renameTo`（复用 `extractAsset` 的做法，抽成 util）；按导入类型分别限定扩展名并统一落成内核认的固定名；用 cursor 的 SIZE 列做上限校验并给进度；`query` 挪出主线程。

  - 已修：`MetaFeatureSettingsActivity` geo 导入三项全合上——①输出文件名固定为内核实际扫描的名字（`geoip.metadb`/`geosite.dat`/`Country.mmdb`/`ASN.mmdb`），不再保留源扩展名；②`OpenableColumns.SIZE` 预检 + 边拷边数、1GB 上限；③临时文件 + rename 原子落盘，中断不留半截库。
- [x] **B-77 应用更新后静默删除用户自行导入的 geo 数据库 (g1)**
  - `app/src/main/java/com/github/kr328/clash/MainApplication.kt:155-160`
  - 触发：`extractAsset` 用 `target.exists() && target.lastModified() < updateDate` 判定要不要重新释放，把"文件比安装包旧"等同于"是过期的内置资产"。用户导入了自定义 `geoip.metadb`，之后应用升级让 `lastUpdateTime` 变新 → 判定成立 → 删掉并用随包版本覆盖。自定义规则库无声消失，用户完全不会怀疑是升级导致的。
  - 修法：把已释放的资产版本单独持久化（`clashDir` 下存 `assets_stamp` 记录已处理的 `lastUpdateTime`），只在标记落后时释放；用户导入产物写 `.user` 标记或放独立目录，`extractAsset` 遇到用户资产跳过。

  - 已修：用户导入的 geo 库写 `<name>.user` 标记，`MainApplication.extractAsset` 见到标记直接跳过；内置资产改用 `assets_stamp` 文件记录已释放的 `lastUpdateTime`，不再用 mtime 与 updateDate 比较（用户导入的新库不会再被当成陈旧内置资产静默覆盖）。
- [x] **B-78 "关于"对话框把整个 Go 内核 native 库加载进 UI 进程 (g1)**
  - `app/src/main/java/com/github/kr328/clash/MainActivity.kt:380-384`（被触发的初始化在 `core/src/main/java/com/github/kr328/clash/core/bridge/Bridge.kt:84-97`）
  - 触发：`queryAppVersionName()` 直接调 `Bridge.nativeCoreVersion()`，触发 `Bridge` 对象初始化 —— `System.loadLibrary("bridge")` + `nativeInit(home, ...)`，在本该只跑界面的主进程里拉起 Go 运行时并初始化内核 home 目录。用户点一次"关于"，UI 进程此后就常驻多出一套 Go 调度线程与相应内存，直到进程结束。
  - 修法：内核版本号改从 `IClashManager` / `StatusProvider` 取（`:background` 本来就加载了 native），或在构建期写进 `BuildConfig`；app 模块不应直接引用 `core.bridge.Bridge`。

  - 已修：`MainActivity.queryAppVersionName` 改用 `BuildConfig.CORE_VERSION`（构建期从 `core/src/foss/golang/clash/constant/version.go` 烘焙），删除 `core.bridge.*` import——UI 进程不再为读一个版本号加载整个 mihomo native 库。
- [x] **B-79 开启"隐藏应用图标"后不会撤销已发布的动态快捷方式 (g1)**
  - `app/src/main/java/com/github/kr328/clash/MainActivity.kt:441-486`（另一半在 `AppSettingsActivity.onHideIconChange`）
  - 触发：`setupShortcuts()` 在 `hideAppIcon` 为 true 时直接 `return` —— 只是"不再发布"，从不 `removeAllDynamicShortcuts`；而设置项那侧只用 `setComponentEnabledSetting` 禁掉了 `MainActivityAlias`。先正常使用（toggle/start/stop 三个快捷方式已发布）再开"隐藏图标"，它们仍留在系统快捷方式库里，可被第三方启动器、助手/语音入口枚举到，隐藏效果失效。
  - 修法：`hideAppIcon` 为 true 时调 `ShortcutManagerCompat.removeAllDynamicShortcuts`（已固定到桌面的用 `disableShortcuts`）；把这段逻辑抽成设置项与启动路径共用的函数。
  - 已修：新增 `app/util/Shortcuts.kt` 的 `Context.applyDynamicShortcuts(hidden)`，隐藏时先 `disableShortcuts`（覆盖用户已钉到桌面的副本）再 `removeAllDynamicShortcuts`；`MainActivity.setupShortcuts()` 整段删除，`onCreate` 与 `AppSettingsActivity.onHideIconChange` 都改走这一个函数。停用文案 `shortcut_disabled_icon_hidden` 已补 `values/` 与 `values-zh/`。

- [x] **B-80 快捷设置面板打开时在主线程做跨进程 ContentProvider 调用 (g1)**
  - `app/src/main/java/com/github/kr328/clash/TileService.kt:38-59,99`
  - 触发：`onStartListening` 在主线程同步调 `StatusClient(this).currentProfile()`（`contentResolver.call` 到 `:background` 的 `StatusProvider`），`receiver.onReceive` 同样。隧道已停时下拉快捷设置面板，这一步会触发 `:background` 冷启动并等 provider 发布，几百毫秒到数秒 —— 面板动画卡顿，极端情况系统 ANR。
  - 修法：先用缓存/上次已知状态刷新 Tile，再用短生命周期协程异步查询并二次 `updateTile`；查询加超时，失败保持"未知"而不是阻塞主线程。
  - 已修：`TileService` 自带 `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`，`onStartListening` 先 `updateTile()` 画已知状态、再 `refreshStatus()` 起协程在 `Dispatchers.IO` 上查；`refreshJob` 可被下一次刷新/`onStopListening` 取消，`onDestroy` 取消整个 scope；查询包 `runCatching`，失败按"未运行"落盘而不阻塞主线程。广播分支也改成 `refreshStatus()`。

- [x] **B-81 Tile 点击忽略 `startClashService` 返回的 VPN 授权 Intent，静默什么都不做 (g1)**
  - `app/src/main/java/com/github/kr328/clash/TileService.kt:25-36`
  - 触发：`onClick` 丢弃返回值，而该函数在 `VpnService.prepare` 返回非 null 时会把授权 Intent 返回**且不启动任何服务**。用户撤销过 VPN 授权、或另一个 VPN 应用抢走了通道 → 点 Tile 后没有服务启动、Tile 状态不变、无 toast、无授权跳转，反复点击毫无反应。
  - 修法：返回非 null 时用 `startActivityAndCollapse` 打开 `InternalControlActivity` 走统一授权流程；把 `startClashService` 的返回类型改成显式的密封结果，让"需要授权"这个分支无法被忽略。
  - 已修：返回非 null 时收起面板并打开 `MainActivity`（`startActivityAndCollapse`，API 34+ 走 `PendingIntent` 重载否则会抛）。**没照修法建议跳 `InternalControlActivity`**：全仓只有 `MainActivity.startClash()` 真正 `startActivityForResult` 处理了系统授权对话框，`InternalControlActivity` 那条路只会弹 `unable_to_start_vpn` 一个 toast 就完 —— 同样是死路。`startClashService` 改密封结果属跨模块签名改动，未做，留待下一轮。

- [x] **B-82 桌面快捷方式在主线程 `onCreate` 里做跨进程查询 (g1)**
  - `app/src/main/java/com/github/kr328/clash/InternalControlActivity.kt:31-33`
  - 触发：`isClashRunning()` 在 `onCreate` 主线程同步 `call` 到 `StatusProvider`，而这是个全透明 + `noHistory` 的动作转发器。隧道未运行时点桌面"切换 Clash"，查询可能顺带冷启动 `:background` —— 用户看到"点了快捷方式后屏幕僵住一会儿"，慢设备上可触发 ANR。
  - 修法：状态查询与动作下发整体放进一个短生命周期协程（`onCreate` 立刻 `finish`），或把 toggle 语义下沉到服务侧（发 `ACTION_TOGGLE` 由 `:background` 自行判断），app 侧无需先读状态。

  - 已修：`InternalControlActivity` 改为 `Global.launch(Dispatchers.IO)` 上查状态 + `withContext(Main)` 执行动作，透明的 noHistory trampoline 主线程不再被 `StatusClient` 冷启动阻塞。
- [x] **B-83 用"重建所有 Activity"作为状态传播机制 (g1)**
  - `app/src/main/java/com/github/kr328/clash/AppSettingsActivity.kt:30-41`
  - 触发：任意设置项变更都走 `ApplicationObserver.createdActivities.forEach { it.recreate() }`，`ClashStart`/`ClashStop`/`ServiceRecreated` 也一律 `recreate()`。用户在应用设置里拨一个开关，整条返回栈（首页、设置、网络设置…）全部销毁重建，滚动位置与展开状态全丢；弱网下隧道反复重连时这个页面被连续重建。B-02 那条"补偿动作被 `activityJob` 取消"正是被这个全量 `recreate()` 触发的。
  - 修法：受影响的 Design 暴露局部刷新接口（`setClashRunning` / `reloadRow`），事件到达只更新需要变的部分；确需主题级重建时只重建自己。

  - 已修：`AppSettingsActivity` 的日夜切换从「重建全部 Activity」（`ApplicationObserver.createdActivities.forEach { recreate() }`）改为只 `recreate()` 本屏——主题变化本就经 `onConfigurationChanged` 扩散到所有 Activity，整栈重建只是丢弃滚动位置与展开状态。
- [x] **B-84 界面不可见时仍持续轮询与推送（轻微） (g1)**
  - `app/src/main/java/com/github/kr328/clash/AdblockHitsActivity.kt:43-54`、`app/src/main/java/com/github/kr328/clash/ConnectionsActivity.kt:41-46`
  - 触发：这两页的循环不看 `activityStarted`，切到后台放着仍每 2 秒 `withClash { queryAdblockStats() }` 并继续接收内核推送、更新 RecyclerView，白耗电与 Binder 带宽。`MainActivity`/`ProfilesActivity`/`ProvidersActivity`/`FilesActivity` 都用 `if (activityStarted)` 门控 ticker —— 这两页是例外。
  - 修法：轮询门控在 `activityStarted` 上，`onStop` 向内核注销 observer、`onStart` 重新注册。

  - 已修：`ConnectionsActivity`/`AdblockHitsActivity` 的订阅与轮询改为与可见性绑定——`ActivityStart` 注册 observer、`ActivityStop` 注销，轮询循环用 `activityStarted` 门控；后台页不再持续吃 Binder 带宽与电量。
- [x] **B-85 恢复备份时逐 profile 广播 `ProfileChanged`，首页被事件风暴刷屏（轻微） (g1)**
  - `app/src/main/java/com/github/kr328/clash/SettingsActivity.kt:108-112`
  - 触发：对每个恢复出的 UUID 各发一次 `sendProfileChanged`，广播语义里没有"全部变了"的粗粒度事件，接收侧也不合并去抖。恢复一份含 20 个 profile 的备份，首页每收一次就 `design.fetch()`（`queryDashboardSummary` + `queryActive`）并重跑 `maybePromptAdblockDownload`（3 次 Binder），合计上百次跨进程调用，恢复完成瞬间明显卡顿。
  - 修法：批量导入后只发一次 `sendServiceRecreated` 或新增 `ACTION_PROFILES_RELOADED`；接收侧对 `fetch` 做 200ms 级 debounce。

  - 已修：`SettingsActivity.restoreBackup` 删掉按 uuid 逐条 `sendProfileChanged`（`ImportedDao`+`PendingDao` 全量再查），改发一条粗粒度 `sendServiceRecreated`；`ProfilesActivity` 同时监听 `ServiceRecreated` 触发刷新。首页事件风暴消除。
- [x] **B-86 三组按下标对齐的平行数组承载代理页状态（轻微） (g1)**
  - `app/src/main/java/com/github/kr328/clash/ProxyActivity.kt:21-24`
  - 触发：`names`/`states`/`scrolledToSelected`/`unorderedStates` 靠同一个 index 隐式关联，`reloadGroup`、`Request.Select`、`Request.UrlTest` 都直接 `names[it.index]`；下标不失效仅靠 `Event.ProfileLoaded` 时"整页重启 Activity"来保证。一旦将来加入"局部增删代理组"的能力，就会越界或更新到错误的组。`Semaphore(10)` 也是无解释的魔法数。
  - 修法：合并成 `List<ProxyGroupState>`（含 name/now/scrolled），请求里带组名而不是下标；并发上限提成命名常量并写明为什么是 10。

  - 已修：`ProxyActivity` 三组平行数组（`names`/`states`/`scrolledToSelected`）合并成 `ProxyGroupState(name + state + scrolledToSelected)` 单列表，下标不会再与组名漂移；`MAX_CONCURRENT_GROUP_LOADS=10` 具名替代魔法数字。（剩余：`Request.Select/UrlTest` 仍传下标而非组名，需动 design 层 Request 超范围；但状态已并入 `ProxyGroupState`，下标不再跨数组漂移）
- [x] **B-87 adblock provider 名称三处硬编码，另有死代码与未用 import（轻微） (g1)**
  - `app/src/main/java/com/github/kr328/clash/OverrideSettingsActivity.kt:26-41`（另两份在 `MainActivity.kt:489` 与 go 侧 `config.AdblockProviderName`）
  - 触发：`ADBLOCK_PROVIDER_NAME = "cfm-adblock"` 在 Kotlin 两处 + Go 一处各写一遍，任一处改动都会静默失配，症状是"广告规则永远不生效"且无任何报错。同文件还有创建后从未使用的 `val service = ServiceStore(this)` 与七个无引用 import。
  - 修法：把 provider 名称与 URL 收敛到 common 的一个常量对象（或从 core 侧暴露）三处共用；删掉死代码与未用 import。
  - 已修：新增 `common/constants/Adblock.kt`（`PROVIDER_NAME` / `PROVIDER_URL` / `HITS_FILE_NAME`），`OverrideSettingsActivity`、`MainActivity`、`AdblockHitAdapter` 三处 Kotlin 副本全部收敛过去；死 `val service` 与 8 个未用 import 删除。**Go 侧那份删不掉**（`core/golang/native/config/adblock.go` 才是真正注入 rule-provider 的一侧），已在 Adblock.kt 顶部写明"Go 拥有这些值、改那边必须同步这边、失配是静默的"。

- [x] **B-88 绕过既有的目录扩展属性各自拼路径（轻微） (g1)**
  - `app/src/main/java/com/github/kr328/clash/LogsActivity.kt:59`、`app/src/main/java/com/github/kr328/clash/AdblockHitsActivity.kt:111`
  - 触发：`LogsActivity.loadFiles` 用 `cacheDir.resolve("logs")`，同文件 `:65` 的 `deleteAllLogs` 却用 `util` 里的 `logsDir`（值相同、写法重复）；`AdblockHitsActivity` 用 `File(getFilesDir(), "clash/adblock_hits.jsonl")` 而非 `clashDir.resolve(...)`。任何一次目录调整都要人肉找齐这些散点，漏一处就是"删不掉 / 读不到"；hits 文件名还与 go 侧 `adblock_hits.go:26` 各写一份。
  - 修法：统一改用 `logsDir` / `clashDir`（`util/Files.kt` 已抽好）；文件名与 go 侧共享常量。
  - 已修：`LogsActivity.loadFiles` 改 `logsDir`；`AdblockHitsActivity` 改 `clashDir.resolve(Adblock.HITS_FILE_NAME)`，`java.io.File` import 删除。`AdblockHitsActivity:84/87` 的 `logsDir` 是 `ClearTarget.Logs`/`All` 的清日志动作，本来就该是 logs 目录，未改。

- [x] **B-89 用户取消文件选择器时报"导入失败"（轻微） (g1)**
  - `app/src/main/java/com/github/kr328/clash/MetaFeatureSettingsActivity.kt:90-135`
  - 触发：`uri == null` 与"复制失败"共用同一个失败出口，最后一行无条件弹 `geofile_import_failed`。点"导入 GeoIP"后在系统选择器里按返回，用户看到"导入失败"，以为自己的文件有问题。`SettingsActivity.kt:51/63` 对同一情形是静默返回。
  - 修法：`uri == null` 直接 `return` 不弹提示；"取不到 DISPLAY_NAME"与"复制失败"给不同文案。
  - 已修：`uri == null` 静默 `return`（与 `SettingsActivity` 一致）；顺带把 `queryFileName` 的 Cursor 查询移到 `Dispatchers.IO`，并改用 `util` 里既有的 `Uri.queryFileName` 扩展（删掉了本文件重复的 `Cursor`/`OpenableColumns` 手写实现）。"取不到名字"与"复制失败"仍共用 `geofile_import_failed` 文案 —— 拆两条文案需要新增字符串资源与翻译，判定为收益低，未做。

- [ ] **B-90 导入的审计报告目录只增不减（app 入口侧）（轻微） (g1)**
  - `app/src/main/java/com/github/kr328/clash/AuditReportActivity.kt:22-31`
  - 触发：每次 `ImportReport` 成功都在 `filesDir/audit-reports/<随机 UUID>` 下解出一整份报告（含 artifacts），成功路径没有任何清理（只有失败时 `deleteRecursively`），也没有应用内删除入口。反复导入会在私有目录堆积多份数 MB 级报告，用户无从发现。
  - 修法：导入成功后只保留最近一份（导入前清理旧目录），或在页面提供"清除已导入报告"入口并显示占用大小。
  - 部分已修：B-96 的 `removeStaleReports`（24h 超龄扫描）已经把"只增不减"堵住，堆积上限从"无限"降到"一天内的导入量"。**仍未做**：应用内没有"清除已导入报告"入口，也不显示占用大小 —— 这属新增 UI，留待下一轮。
  - 关联：B-96（同一缺陷在 importer 侧的位置与可复用的超龄清理做法）。

- [x] **B-91 `ProfilesActivity` 两个覆写与全仓书写风格不一致（轻微） (g1)**
  - `app/src/main/java/com/github/kr328/clash/ProfilesActivity.kt:121-154`
  - 触发：用了 `if (uuid == null) return;` 的行尾分号，以及 `var name: String? = null` 再在 `withProfile` 里赋值（而不是取返回值），紧邻的 `main()` 却是标准 Kotlin 风格。不影响运行，但同一文件两种风格给评审加噪声。
  - 修法：去掉分号，写成 `val name = withProfile { queryByUUID(uuid)?.name }`；两个覆写几乎对称，可抽一个私有函数统一"查名字 → 记面包屑 → 弹 toast"。

  - 已修：`ProfilesActivity.onProfileUpdateCompleted/Failed` 去掉 `;`、`var name: String? = null` 模式，抽 `queryProfileName(uuid)` 辅助函数，与全仓书写风格对齐。
- [x] **B-92 `TileService.unregisterReceiver` 无保护，与同仓谨慎写法不一致（轻微） (g1)**
  - `app/src/main/java/com/github/kr328/clash/TileService.kt:61-65`
  - 触发：`onStopListening` 裸调 `unregisterReceiver`，没有 `registered` 标志也没有 try/catch；对照 `remote/Broadcasts.kt:111-124` 对完全同类的操作做了 try/catch + 标志位。部分 ROM 上 `onStartListening`/`onStopListening` 会失配调用（例如 `registerReceiverCompat` 抛异常后仍收到 `onStopListening`）→ `IllegalArgumentException` 直接崩在系统服务回调里。
  - 修法：加 `registered` 布尔标志并把注册/注销都包进 `runCatching`，与 `Broadcasts` 对齐。
  - 已修：新增 `private var registered`，注册结果由 `runCatching{...}.isSuccess` 决定，注销前判标志且同样包 `runCatching`，与 `Broadcasts.kt` 写法对齐。

> 不建议修（g1 已核对源码）：`HelpActivity.kt:15-17` 的 `while (isActive) events.receive()` 看着空转，实为必需——`BaseActivity.events` 是 UNLIMITED 通道，不消费会随广播无限增长；要清理应在 `BaseActivity` 层把 events 改成有界/CONFLATED，而不是逐页改。
> 不建议修：`DialerReceiver.kt` 未校验调用方——它 exported 且无权限，但唯一行为是启动 `MainActivity`，而后者本身带 exported 的 `MAIN`/`APPLICATION_PREFERENCES` filter，任何应用本来就能启动它，无权限提升。
> 不建议修：`ExternalControlActivity` 的 `MainScope()` 未取消——Activity 是 `noHistory` 且立即 finish，协程做完一次 profile 创建即结束，泄漏窗口毫秒级且不累积；改成 `lifecycleScope` 反而会让 finish 后的收尾被取消。
> 不建议修：`util/AuditReportImporter` 的归档解压——按"外部不可信 zip"标准核过：entry 数量上限、逐 entry 与总字节上限、`canonicalPath` 前缀校验、目录/文件白名单、重复 entry 拒绝、artifact SHA-256 校验齐全。
> 不建议修：`LogsActivity`/`LogcatActivity` 的文件名路径穿越——`LogFile.parseFromFileName` 用 `Regex("clash-(\\d+).log")` 做 `matchEntire`，`logsDir.resolve(fileName)` 拿不到 `../`。
> 不建议修：`MainActivity` 的 `promptedPairings`/`promptedAdblockProfiles` 是实例级集合——注释写明"每个 activity 实例只问一次"，属刻意取舍，持久化反而让用户永久错过提示。

#### B-2 app · 基础设施层 (g2)

- [x] **B-08 `Broadcasts.register()` 在主线程做同步跨进程查询**
  - `app/src/main/java/com/github/kr328/clash/remote/Broadcasts.kt:105`
  - 触发：`StatusClient.currentProfile()` 可能**冷启动** `:background` 进程，整个过程阻塞主线程——冷启动路径上的可感知卡顿。
  - 修法：改异步，先给 UI 一个"未知"初值。

  - 已修：`Broadcasts.register()` 里 `StatusClient.currentProfile()` 改为 `Global.launch(Dispatchers.IO)` 异步对账（`runCatching` 默认 false），不再在主线程冷启动 `:background`。
- [x] **B-09 Widget 更新在主线程做 Binder 往返**
  - `app/src/main/java/com/github/kr328/clash/widget/WidgetUiBinder.kt:31`
  - 触发：`onUpdate` / receiver 回调里同步跨进程 → ANR 风险（receiver 的主线程预算只有 10s，且此时可能正在冷启动内核进程）。
  - 修法：`goAsync()` 或 WorkManager。
  - 已修（`git diff` 核过）：新增 `internal fun BroadcastReceiver.runWidgetUpdateAsync(block)`，内部 `goAsync()` 拿 `PendingResult`、把工作丢到 `WidgetUiBinder.scope`、`finally` 里 `pending.finish()`；`ClashStatusWidgetProvider.onUpdate` 与 `WidgetRefreshReceiver` 都改走它。

- [x] **B-10 Widget 的兜底读取在 app 进程里是死代码**
  - `app/src/main/java/com/github/kr328/clash/widget/WidgetUiBinder.kt:33`
  - 触发：`WidgetStateStore.current()` 是**进程内** object，只有 `:background` 写它，app 进程读恒为 null。后续维护者会误以为"服务没跑"。
  - 修法：标为服务进程专用，或统一只留 `StatusProvider` 一条读取通道。
  - 已修（`git diff` 核过）：删掉 `WidgetStateStore` 兜底分支，KDoc 改成"StatusProvider 是唯一读取通道"。

- [x] **B-11 Widget 去重 + 30 分钟刷新周期可让面板钉住陈旧状态**
  - `app/src/main/java/com/github/kr328/clash/widget/WidgetUiBinder.kt:99`（`updatePeriodMillis=1800000`）
  - 触发：`lastModels` 判重恰好在状态真变化时判成"没变"，就要等 30 分钟才纠正——widget 上写着"已连接"而实际早断了。
  - 修法：状态类字段不参与去重，或去重只用于纯数值抖动。
  - 已修（`git diff` 核过）：`lastModels` 换成 `ConcurrentHashMap`；判重收紧成 `if (!force && model == lastModels[id]) continue`；写缓存挪到 `manager.updateAppWidget(id, views)` **之后** —— 之前是"先记下、后渲染"，渲染抛异常就把没画上去的状态当成已画，正是"钉住陈旧状态"的根因。

- [x] **B-12 重试包不住真正的失败点，且 `RemoteException` 不算可重试**
  - `app/src/main/java/com/github/kr328/clash/util/Remote.kt:27,48`
  - 触发：`remote.clash()` / `remote.profile()`（取 Binder 代理）写在 `try` 之外，"取代理这一步挂了"完全不在重试范围内；而 `:background` 被杀时最常见的异常正是 `RemoteException`。
  - 修法：取代理挪进 try，`RemoteException` 纳入可重试。
  - 已修：两处取代理都挪进 `try`，捕获从 `DeadObjectException` 放宽到父类 `RemoteException`。残留：`TransactionTooLargeException` 也是 `RemoteException` 子类，现在会被无意义地重试三次——它不是瞬时故障，值得单独放行。

- [x] **B-13 KDoc 宣称指数退避 + 快速失败，实现是线性退避**
  - `app/src/main/java/com/github/kr328/clash/util/Remote.kt:16-19,36`
  - 触发：`MAX_RETRIES=5`、`RETRY_BASE_DELAY_MS=100L` 线性叠加，总预算约 1.5s。后续维护者按文档假设做容量判断会判错。
  - 修法：实现与文档二选一改到一致。
  - 已修：`delay(RETRY_BASE_DELAY_MS shl (attempt - 1))`，实现向文档对齐成真指数退避。

- [x] **B-14 每读一个自动化属性做一次 IPC + 一次完整 JSON 解码**
  - `app/src/main/java/com/github/kr328/clash/store/AutomationSettingsAdapter.kt:170-171`
  - 触发：读 6 个属性 = 6 次跨进程 + 6 次全量解码，发生在主线程；场景多时是可感知的卡顿。这是典型的 N+1。
  - 修法：一次操作内复用一次 `current()` 结果。
  - 已修：`fallback` 换成 `private var snapshot: Scene`，`current()` 直接返回快照；`update()` 用 `synchronized(sceneStore)` 包住读-改-写。

- [ ] **B-15 跨进程读-改-写 `scenes`，只有进程内 `@Synchronized`**
  - `app/src/main/java/com/github/kr328/clash/store/AutomationSettingsAdapter.kt:174`
  - 触发：UI 进程与服务进程同时写 → 后写者整表覆盖，用户刚加的场景消失（与 g4 的 `SceneStore` 整表读改写是同一问题的两端）。
  - 修法：写入统一走 `PreferenceProvider` 串行化，或加 revision 做乐观并发校验。

- [x] **B-16 每个 setter 发一次 `sendAutomationChanged()`，批量修改造成广播风暴**
  - `app/src/main/java/com/github/kr328/clash/store/AutomationSettingsAdapter.kt:76,81,84-86,176`
  - 触发：保存一个场景要设置多个属性 → 多条跨进程广播 → 服务侧重复重算场景。
  - 修法：批量修改结束后合并发一次。

  - 已修：`AutomationSettingsAdapter` 新增 `automationChangedJob`（200ms 防抖），批量修改合并为单次 `sendAutomationChanged()`，广播风暴消除。
- [x] **B-17 备份恢复在内核运行时覆盖偏好与配置文件**
  - `app/src/main/java/com/github/kr328/clash/util/DataBackup.kt:31-36`
  - 触发：恢复过程中 `:background` 正持有旧偏好的内存缓存并在读配置文件 → 恢复结果部分生效、部分被覆盖，状态无法预测。
  - 修法：恢复前强制停服务，恢复后重启；或做成需要重启应用的原子操作。

  - 已修：`SettingsActivity.restoreBackup` 先 `stopClashService()` + 500ms 静置，恢复完成后按原状态 `startClashService()`（VPN 授权 Intent 用 `startActivity` 浮出）——内核运行中的偏好/配置文件覆盖竞态消除。
- [x] **B-18 导出的备份包是明文，内含代理凭据**
  - `app/src/main/java/com/github/kr328/clash/util/DataBackup.kt:13-25`
  - 触发：用户把备份 zip 发到网盘/聊天里（备份就是为了传出去的），订阅地址与凭据随之泄漏。
  - 修法：导出加密（口令或 `SecureStorage`），或在导出 UI 上明确警示内容敏感。
  - 关联：[用户安全文案保持高层] —— 面向用户的文案只写"包含订阅凭据，请勿分享"，不展开算法。

  - 已修：备份导出前弹敏感提示对话框（`backup_sensitive_title/message`，含订阅凭据警告），OK 才进 SAF 选择器；两条文案已加 `values/` 与 `values-zh/`。
- [x] **B-19 `LogcatWriter` 不转义换行，多行日志把落盘格式写坏**
  - `app/src/main/java/com/github/kr328/clash/log/LogcatWriter.kt:30`
  - 触发：内核输出的堆栈/YAML 片段被劈成多行，`LogcatParser` 把后续行当新记录，**凭空造出不存在的 Warning**——日志本身开始撒谎。
  - 修法：写侧转义 `\n`/`\r`，解析侧配套反转义。
  - 已修：新增 `internal fun escape(message: String)`（转义 `\`、`\n`、`\r`），在 `appendMessage` 里应用。残留：读侧还没有配套反转义，导出的日志里多行内容会显示成字面 `\n`——比伪造记录好，但仍未闭环。

- [x] **B-20 `_visibleActivities` 无同步**
  - `app/src/main/java/com/github/kr328/clash/util/Application.kt:42-49`
  - 触发：生命周期回调虽在主线程，但读取方可能在其他线程 → 可见性问题导致"应用在前台"判断出错。
  - 修法：加同步或改用线程安全容器。
  - 已修：两个集合都换成 `ConcurrentHashMap.newKeySet()`，`@Synchronized` 只保留在 `onActivityStarted`/`onActivityStopped`（可见性翻转的读-改-写）上。残留：`createdActivities` 的遍历与 `onVisibleChanged` 回调之间仍无整体一致性保证。

- [x] **B-21 `getColumnIndex` 未检查 -1；`readText()` 无上限**
  - `app/src/main/java/com/github/kr328/clash/remote/FilesClient.kt:19-23,81-87`
  - 触发：列不存在时用 -1 取值抛异常；读一个超大文件直接 OOM。
  - 修法：检查 -1 走明确失败路径；读取加大小上限。
  - 已修：5 处改 `getColumnIndexOrThrow`；读取改成有界循环，`MAX_TEXT_CHARS = 4 * 1024 * 1024`。

- [x] **B-22 审查报告清单按 `toString()` 比对**
  - `app/src/main/java/com/github/kr328/clash/util/AuditReportImporter.kt:219`
  - 触发：JSON 键顺序变化就判定不一致——比对结果依赖序列化实现细节。
  - 修法：按结构化字段比对。
  - 已修（`git diff` 核过）：新增递归的 `sameJson(left, right)`，对 `JSONObject` 比键集合与逐键值、对 `JSONArray` 比长度与逐项，`require(reportManifest.toString() == manifest.toString())` 换成 `require(sameJson(...))`。

- [x] **B-23 二维码导出在主线程压 PNG，且 `profile-qr` 缓存从不清理**
  - `app/src/main/java/com/github/kr328/clash/util/ProfileQrExport.kt:97-104`
  - 触发：主线程 `Bitmap.compress` 造成掉帧；缓存目录随使用单调增长，内容是订阅地址。
  - 修法：移到 IO 线程；用完即删或设保留上限。
  - 已修（`git diff` 核过）：`shareBitmap` 改 `suspend`，写盘抽成 `writeQrImage` 并放到 `withContext(Dispatchers.IO)`；`writeQrImage` 进门先把 `profile-qr` 目录里的旧文件全删（每张二维码都编码着订阅地址，不能长期留存），只保留本次要分享的那一张；`catch` 里先 `catch (e: CancellationException) { throw e }` 再兜 `Exception`，避免把协程取消当成导出失败弹 toast。

- [x] **B-24 `take(maxChars - 1)` 切碎代理对**
  - `app/src/main/java/com/github/kr328/clash/widget/WidgetFormat.kt:14`
  - 触发：节点名里的国旗 emoji 是代理对，被劈开后 widget 显示方框乱码——中文/emoji 节点名是本项目的常态。
  - 修法：截断时避免切开代理对。
  - 已修：截断前检查 `Character.isHighSurrogate`，命中就再回退一格。

- [x] **B-25 日志写在 `cacheDir`，与 `LogcatWriter` 自己的配额互相打架**
  - `app/src/main/java/com/github/kr328/clash/util/Files.kt:6-7`
  - 触发：系统清理 `cacheDir` 会不打招呼删掉日志；而 `LogcatWriter` 又按自己的配额算总量，两套逻辑对同一目录做相反的假设。
  - 修法：日志挪到 `filesDir` 下的专用目录。

  - 已修：`logsDir` 从 `cacheDir/logs` 改为 `filesDir/logs`——系统清 cache 不再静默删掉 `LogcatWriter` 还在计配额的文件，两层对「什么会留存」达成一致。
- [x] **B-26 `LogcatCache` 静默丢弃，UI 无任何信号**
  - `app/src/main/java/com/github/kr328/clash/log/LogcatCache.kt:23-24`
  - 触发：日志爆掉时用户在界面上看到的是"没有更多日志"，而不是"日志被丢了"——排障时会得出错误结论。
  - 修法：丢弃计数暴露到 UI。

  - 已修：`LogcatActivity` 收到 `snapshot.removed > 0`（环形缓冲满、旧消息被丢弃）时弹一次 `logcat_dropped_messages` 长 toast，用户不再误以为「没日志」；文案已加 `values/` 与 `values-zh/`。
- [ ] **B-27 `util/` 是 21 个文件的杂物间，且与 service 模块重名**
  - `app/src/main/java/com/github/kr328/clash/util/`（`Files.kt` / `Remote.kt` / `Service.kt` 与 service 模块同名文件撞名）
  - 触发：跨模块搜索与 review 时反复打开错文件；本次审查中就出现过。
  - 修法：按职责拆包（`ipc/`、`log/`、`backup/`…），重名文件改名。
  - 清单项：第 9 章"不一致的丑"

- [x] **B-93 崩溃现场抓 logcat：无界读取、不排空 stderr、`waitFor` 无超时 (g2)**
  - `app/src/main/java/com/github/kr328/clash/log/SystemLogcat.kt:17-25`
  - 触发：`exec` 后 `inputStream.use { it.reader().readLines() }` 把全部输出读进内存，随后 `process.waitFor()` 无超时，子进程 stderr 从未被读。崩溃路径调 `dumpCrash()` 时，在已经内存紧张的现场再申请大块内存；若子进程写满 stderr 管道缓冲，它阻塞在写、我们阻塞在 `waitFor()`，崩溃上报线程永久死锁。
  - 修法：命令加 `-t <行数>` 只保留尾部；`redirectErrorStream(true)` 合流（或单起线程排空 stderr）；改 `waitFor(timeout, unit)`，超时 `destroyForcibly()`。
  - 已修（`git diff` 核过）：命令加 `-t 512`；`Runtime.exec` 换成 `ProcessBuilder(*command).redirectErrorStream(true).start()`；`readLines()` 换成 `bufferedReader().lineSequence()` 流式过滤；`waitFor(5, SECONDS)` 超时即 `destroyForcibly()`，`catch` 里也补了 `destroyForcibly()`（原来异常路径会漏掉子进程）。

- [x] **B-94 `importDocument` 拼接未校验的 name，可寻址到保留的 `config.yaml` (g2)** —— **判定：不修（报告不成立）**
  - `app/src/main/java/com/github/kr328/clash/remote/FilesClient.kt:53-58`（Provider 侧 `service/.../document/Paths.kt`）
  - 触发：直接 `buildDocumentUri("$parentDocumentId/$name")`，对 `name` 无字符集/长度/保留名校验，而 Provider 侧的 `Paths` 只过滤 `.`/`..`，不拦保留名。`name = "config.yaml"` 拼出的 documentId 正好命中运行中配置本身，写入直接覆盖，绕过 `ProfileFileRoundTrip` 的"校验通过才提交、失败回滚"保护。
  - 修法：导入前对 `name` 做白名单校验（字符集、长度、扩展名）并显式拒绝保留名；更稳的是让 Provider 对保留 documentId 的写入一律拒绝，把不变量放在唯一权威处。
  - **不修理由（逐环核过源码）**：g2 只看了 `FilesClient` 这一层，没有把调用链走完，三道闸门都已经在位 ——
    1. `name` 不是任意输入。唯一调用点 `FilesActivity.kt:94` 是 `design.requestFileName(...)`，走 `FilesDesign.kt:54-62` 的 `requestModelTextInput(validator = ValidatorFileName)`；`design/util/Validator.kt:12` 的 `ValidatorFileName` = `PatternFileName.matches(it) && it.isNotBlank()`，而 `common/util/Patterns.kt:3` 的 `PatternFileName = Regex("[^*&%\n\r/]+")` 直接把 `/` 排除在字符集外 —— 拼不出跨目录的 documentId。
    2. Provider 侧 `FilesProvider.renameDocument:88` 对同一个 `PatternFileName` 再校验一次，不变量并非只存在于 UI 层。
    3. 「命中 base 目录的 `config.yaml`」这条路根本没有入口：`design_files.xml:63` 的新建/导入按钮 `visibility="@{currentInBaseDir ? View.GONE : View.VISIBLE}"`，在 base 目录不可见；`dialog_files_menu.xml:63,73` 的重命名与删除同样 `!currentInBase` 才显示。base 目录唯一的写入口是菜单里显式的"导入已编辑的配置"，它本来就走 `ProfileFileRoundTrip`。
  - 留档：若将来放开 base 目录的新建/重命名，这条要重新成立 —— 那时应按 g2 的修法把"拒绝保留 documentId"落在 Provider 侧，而不是再加一层 UI 校验。

- [x] **B-95 `Uri.fileName` 对 content 协议返回百分号编码的 documentId 片段 (g2)**
  - `app/src/main/java/com/github/kr328/clash/util/Uri.kt:5-6`
  - 触发：`schemeSpecificPart.split("/").lastOrNull()` 对 `content://` 取到的是编码后的 documentId 而不是显示名。用户从 SAF 选一份名为「我的 配置.yaml」的文件导入，得到的"文件名"是 `%E6%88%91...` 或 `1234-ABCD%3Aconfig.yaml`，被当作默认配置名写库并展示给用户。
  - 修法：`content` scheme 走 `ContentResolver.query` 取 `OpenableColumns.DISPLAY_NAME`，取不到再降级 `lastPathSegment` 并做 URL 解码；`file` scheme 保留现逻辑。
  - 已修（`git diff` 核过）：改成 `Uri.queryFileName(resolver)`，`content` 走 `queryDisplayName`（`runCatching` 包住 query，空白名视为取不到）→ 降级 `lastSegmentName()`（用**已解码**的 `lastPathSegment`，再切掉 `primary:Dir/` 这类 documentId 前缀），其他 scheme 保留原 `schemeSpecificPart` 逻辑。KDoc 里点明了为什么不能拿路径段兜底：`%` 恰好被 `PatternFileName` 拒绝，用户连"确认"都点不下去。`MetaFeatureSettingsActivity` 里那份重复的手写 Cursor 实现也一并收敛到这里（见 B-89）。

- [x] **B-96 审计报告解压目录只在失败时清理，成功路径永久堆积 (g2，g1 独立同报)**
  - `app/src/main/java/com/github/kr328/clash/util/AuditReportImporter.kt:187-188`
  - 触发：`createTarget` 每次导入建 `filesDir/audit-reports/<UUID>`，异常路径有删除，成功路径没有任何回收，也没有 `ProfileFileExport.removeStaleExports` 那样的超龄扫描。反复导入后解压产物连同报告原文长期占用应用私有目录，只增不减。
  - 修法：照 `removeStaleExports` 的模式在导入前按年龄清理旧目录；报告被消费或删除时同步删目录。
  - 已修（`git diff` 核过）：`createTarget` 进门先调新增的 `removeStaleReports(root)`，按 `STALE_REPORT_AGE_MILLIS = 24h` 扫掉过期目录，注释里点明"解压产物不会被二次打开（`import` 已把 UI 需要的全部返回）"这一前提，做法与 `ProfileFileExport.removeStaleExports` 对齐。
  - 关联：B-90（同一缺陷的 app 入口侧记录）。

- [x] **B-97 `importJsonl` 先整段进内存再重读，峰值内存约为文件的三倍 (g2)**
  - `app/src/main/java/com/github/kr328/clash/util/AuditReportImporter.kt:144-161`
  - 触发：`readBounded(input, MAX_JSONL_BYTES)` 把整份内容读成 `ByteArray`，随后解码成字符串逐行处理并写出——原始字节数组、解码后字符串、输出缓冲同时在堆上。导入接近上限的 jsonl 报告时，低端机（512MB 级堆）直接 OOM，而上限校验本身并不需要先落内存。
  - 修法：改单遍流式处理，按行读、逐行校验、逐行写出，只保留行级缓冲；总量上限用累计计数器实现。

  - 已修：`importJsonl` 改单趟流式——`readBounded` 整段入内存 + 再解码的三倍峰值去掉，只保留行级缓冲；总量上限改为累计计数；`validateRecord` 提为 `internal` 供单测。
- [x] **B-98 外部编辑会话目录存明文配置，仅靠 `close()` 清理且无超龄回收 (g2)**
  - `app/src/main/java/com/github/kr328/clash/util/ProfileFileEditor.kt:44-46,54-60`
  - 触发：`prepare()` 把配置拷进 `cacheDir/profile-editor/<uuid>/{original.yaml,config.yaml}` 并向所选编辑器授出读写 URI 权限，唯一清理是 `close()` 里的 `deleteRecursively()`。用户点"用外部编辑器打开"，编辑器占前台期间 app 进程被系统回收 → `close()` 从未执行 → 含订阅凭据的明文 `config.yaml` 长期留存，已授出的 URI 权限也未撤销。
  - 修法：`prepare()` 时先按年龄清理遗留会话目录，应用下次启动时统一撤销残留 URI 授权；`close()` 保留为快速路径。
  - 已修（`git diff` 核过）：`prepare()` 进门先调新增的 `removeStaleSessions(root)`，`STALE_SESSION_AGE_MILLIS = 24h`，`close()` 仍是快速路径；做法与 B-96 的 `removeStaleReports` / `ProfileFileExport.removeStaleExports` 一致。
  - **残留（未修）**：已授出的 `FLAG_GRANT_READ/WRITE_URI_PERMISSION` 没有在启动时统一 `revokeUriPermission`。目录被扫掉后授权指向的文件已不存在，实际危害降到"授权条目残留"；真要收干净需要在 `Application` 启动路径上加一次撤销扫描，属跨文件改动，留待下一轮。

- [x] **B-99 app 侧单测只覆盖 happy path，本轮的纯逻辑缺陷全部测不到（轻微） (g2)**
  - `app/src/test/java/com/github/kr328/clash/`（`LogcatCacheTest` / `LogcatParserTest` / `AppLockGateTest` / `AuditReportPolicyTest` / `ConfigOutlineTest` / `WidgetFormatTest` / `ProfileTest`）
  - 触发：`LogcatWriter` ↔ `LogcatReader` 的往返与配额、`util/Remote` 的重试语义、`AuditReportImporter` 的拒绝路径全无覆盖。B-19（换行往返）、B-13（退避语义）、B-24（截断边界）都是纯逻辑，本可被单测挡住，却因异常分支零覆盖长期存活。
  - 修法：优先补纯逻辑用例——writer↔parser 往返（含多行、含冒号的消息）、reader 尾部截断与丢弃首个残行、retry 次数与退避时序、importer 各类拒绝路径。
  - 清单项：② 可验证性

  - 已修：新增/扩展纯逻辑单测——`AppLockGateTest`（B-72 resume 门 5 例）、`WidgetFormatTest`（B-24 代理对边界 5 例）、`LogcatWriterParserRoundTripTest`（B-19 换行/冒号/反斜杠往返）、`RemoteRetryTest`（B-13 指数退避时序）、`AuditReportImportRejectGateTest`（B-97/B-90 拒绝路径与策略门）。本轮纯逻辑缺陷开始有回归防线。
> g2 独立复核：`Broadcasts` 的 uuid 解析与签名权限（A-02 / A-03）、`Service` 的 `bindService` 返回值（A-04）、`Resource.get()` 超时（A-05）、`AppLockController` 的永久锁死路径（A-07）已在当前工作树确认修复；应用锁的**入口覆盖面**问题另记为 A-37。

#### B-3 service · 模块与场景引擎 (g4，完整清单另见 `.audit-reports/g4-service-modules.md`)

- [ ] **B-28 节点失效转移没有后台驱动者，整套状态机在服务进程内不可达**
  - `service/.../scene/NodeFailoverController.kt:19`（唯一调用链：`ClashManager.evaluateFailover` ← `app/ProxyActivity.kt:141`）
  - 触发：用户不打开代理页面时节点全程不做健康检查，节点挂掉不会自动切换——"自动失效转移"只在用户盯着 UI 时生效，而那恰好是最不需要它的时候。`SuspendModule.kt:40` 的 `healthCheckAll()` 还位于不可达的 `else` 分支。
  - 修法：新增一个 Module（或挂到现有 2s ticker 上按冷却降频）在服务进程周期性驱动健康检查。

- [ ] **B-29 `queryGroupDelays` 传的是节点名而非组名，延迟与存活数恒为 0**
  - `service/.../clash/module/TrafficHistoryModule.kt:93-113`
  - 触发：Go 侧 `QueryDashboardSummary` 把 `SelectedNow` 设成 `QueryProxyGroupNow(groupName)`（**节点**名），而 `QueryProxyDelays` 内部 `resolveProxyGroup(name)` 要**组**名，必然解析为 nil 返回空 map。任何配置下 `proxyDelay` / `aliveProxies` 恒为 0，写进历史的这两列**全是假数据**。
  - 修法：改传当前组名，或在 Go 侧提供按节点名查延迟的接口（跨组改动）。

- [x] **B-30 每 2 秒一次跨进程自广播，把主进程从 cached 状态拉起**
  - `service/.../clash/module/TrafficHistoryModule.kt:176-181`
  - 触发：`WidgetRefreshReceiver` 静态注册在主进程；`WidgetState.sameAs` 只忽略时间戳而速率一直在变，于是几乎每 tick 都判"变了"。VPN 挂着不用也每 2 秒唤醒一次主进程——可感知的耗电。
  - 修法：只在 widget 实际存在时广播 + 速率量化/降频。

- [x] **B-31 采样与限流全用墙钟，改时间或 NTP 校准会打乱历史**
  - `service/.../clash/module/TrafficHistoryModule.kt:53,118-124`、`TrafficHistoryBuffer.kt:38-41`
  - 触发：时间回拨 → `nowMs - lastAcceptedMs` 变负 → 此后所有样本被拒直到墙钟追上（历史曲线长空洞）；时间前跳则一次性放行一批。
  - 修法：间隔门改 `SystemClock.elapsedRealtime()`，展示时间戳仍用墙钟，两者分离。

- [x] **B-32 前台服务通知走 `notifyIfAllowed`，用户关掉通知权限后就永久不刷新**
  - `service/.../clash/module/DynamicNotificationModule.kt:66`
  - 触发：API 33+ 拒绝 `POST_NOTIFICATIONS` 后该函数静默返回，通知内容永久停在首次下发的状态，流量数字长期不动——用户以为服务死了。而前台服务通知本身**不受** `POST_NOTIFICATIONS` 限制。
  - 修法：前台通知刷新走 `startForeground` 语义；`notifyIfAllowed` 只留给提醒类通知。

- [ ] **B-33 `NetworkCallback` 注册失败被吞，模块变永久空转**
  - `service/.../clash/module/NetworkObserveModule.kt:96-107,191-192`
  - 触发：达到系统 100 个回调上限（`TooManyRequestsException`）或被厂商 ROM 拦下时 `register()` 返回 false，而 `run()` 忽略返回值继续等事件。此后网络切换不再产生事件，**场景自动切换与 DNS 变更通知一起静默失效**，日志只有一条 warn。
  - 修法：失败上报可见状态并做有上限的退避重试，而不是静默降级。

- [x] **B-34 `SecureStorage` 的加解密零调用方，KDoc 却宣称已保护订阅地址与私钥**
  - `service/.../util/SecureStorage.kt`
  - 触发：不需要特殊时序。文件实现了完整的 Keystore AES-GCM，但全仓 grep `SecureStorage.(encrypt|decrypt)` 零命中；KDoc 声称保护订阅 URL 与 `ageSecretKey`，而这两者**目前仍以明文存在 SharedPreferences**。文档与实现不一致会让后续维护者误判风险已缓解。
  - 修法：要么真正接上敏感字段，要么删掉这个文件并修正 KDoc；`initialized`/`key` 应加 `@Volatile`。
  - 清单项：④ 安全 + 第 9 章"半成品"

  - 已修：`SecureStorage.kt` 整个删除（KDoc 声称的 ageSecretKey/私钥 URL 与实际未使用的实现一并移除）。
- [ ] **B-35 抓包文件明文落盘且永不清理**
  - `service/.../util/CaptureStore.kt:60-86`
  - 触发：反复抓包在私有目录累积文件，内容是**用户完整的访问记录**（DNS 查询 + 连接目标）；配合 `allowBackup="true"` 还会进备份与换机迁移。
  - 修法：加保留策略（最近 N 个 / 总量上限）+ 接 `SecureStorage`，或至少提供"清空抓包记录"。

- [x] **B-36 抓包触发广播在 API 26-32 上隐式导出，在 API 33+ 上又完全不可达**
  - `service/.../clash/module/CaptureModule.kt:29-31`
  - 触发：`receiveBroadcast(requireSelf = false)` 放弃包名校验，而 `exported=false` 只在 API 33+ 生效、minSdk 是 26 —— API 26-32 上任意第三方应用只要知道 action 就能开启抓包并把用户访问记录写盘（**权限提升**）；API 33+ 上又连 adb 都发不进来，调试入口实际失效。两端都不对。
  - 修法：改回 `requireSelf = true` 走签名权限自广播；调试入口另建应用内通道。

- [x] **B-37 `CaptureStore` 用 GlobalScope + UNLIMITED channel，停止抓包后数据串到下一次**
  - `service/.../util/CaptureStore.kt:48,102-129`
  - 触发：writer 不与任何 Module 生命周期绑定，用 100ms 轮询 `tryReceive` 且每行 `flush()`；停止抓包时 channel 未 drain，残留行会被写进**下一次**抓包的新文件（会话数据串档）。高流量下 channel 无界增长。
  - 修法：writer 挂到 `CaptureModule` 的 scope，channel 改有界（满则丢弃并计数），停止时显式 drain + close，flush 改批量。
  - 已修（`git diff` 核过）：`GlobalScope` 换成对象自持的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` + `writerJob`，`start()` 先 `writerJob?.cancel()` 再起新的（否则上一个 writer 还停在 flush 超时里，`_isActive` 一翻回 true 就会继续往旧文件写）；channel 改 `capacity = 4096, onBufferOverflow = DROP_OLDEST`（抓包是诊断功能，生产者是内核 DNS/连接热路径，丢最旧的才是唯一可接受的背压）；`start()` 里 `tryReceive` 循环把上一会话的尾巴排空，串档消失；轮询 `delay(100)` 换成 `withTimeoutOrNull(500) { receive() }` —— 平时挂起不空转，超时既用来复检时长/大小上限、也当作批量 flush 的时机（不再每行一次写系统调用）。
  - 顺带（同一文件，g4 未单列）：`enqueue(type, payload: @Serializable Any)` 这个签名靠反射式序列化，改成 `<T> enqueue(type, serializer: KSerializer<T>, payload: T)`，三个调用点显式传 `DnsRecord.serializer()` / `ConnectionSnapshot.serializer()` / `HttpRecord.serializer()`；并按"抓包内容 = 明文浏览历史"的定性加了 `pruneOldSessions`，每次 `start()` 只保留最近 3 个会话文件。

- [x] **B-38 订阅流量 `add`/`reset` 读改写竞争，锁只保护单个实例**
  - `service/.../store/LocalSubscriptionTrafficStore.kt:31-49`
  - 触发：`@Synchronized` 加在实例方法上，而该 store 在不同调用点各自 `new`——等于不同的锁对象，锁形同虚设。2s flush 与 UI/Worker 侧的 reset 并发时丢一次增量，用户看到的已用流量偏少，且**不会自愈**（基线已推进）。
  - 修法：改 `object` 单例或类级别锁；跨进程场景走 `PreferenceProvider` 串行化。

- [ ] **B-39 订阅流量没有计费周期重置，构造函数里做同步 `commit()`**
  - `service/.../store/LocalSubscriptionTrafficStore.kt`、`store/SubscriptionTrafficBillingPreference.kt`
  - 触发：偏好里描述了计费周期，但 store 只有手动 `reset()`——月付订阅跨月后计数继续累加，"已用/剩余流量"永久偏大直到用户手动重置。另外 `migrateInflatedCountersIfNeeded()` 的构造期 `commit()` 若发生在主线程会引入可感知磁盘阻塞。
  - 修法：读取时按计费周期计算起点、跨周期自动归零并记周期标记；迁移改 `apply()` 或移到后台一次性任务。

- [x] **B-40 `subscriptionExpiryNotifiedKeys` 无界增长**
  - `service/.../store/ServiceStore.kt:133-140`
  - 触发：去重键含 `expireMs`，而 `expireMs` 每次续期都变——每续期一次就产生一整套新键，从不清理。而 SharedPreferences 是**整文件读写**，于是每次 `ServiceStore` 访问都要反序列化这个越来越大的集合，还会进备份。
  - 修法：改为按 uuid 存"已提醒到哪个 bucket + 对应 expireMs"的单条记录（新值覆盖旧值）。

- [x] **B-41 `LocalTrafficAccountingModule` 缺异常边界，一次查询失败即拖垮 VPN**
  - `service/.../clash/module/LocalTrafficAccountingModule.kt:30-60`
  - 触发：2s ticker 里直接调 `Clash.queryTrafficTotal()` 与 store 写入，全程无 try/catch；相邻的 `TrafficHistoryModule` 反而有 catch-all。内核停止瞬间 JNI 抛异常即经 A-18 的链条断掉 VPN。
  - 修法：工作体加 catch（`CancellationException` 原样抛出），失败保留 `baselineDirty` 等下一 tick 重试。

- [ ] **B-42 场景评估无防抖，且每次评估都重新解析整个场景 JSON**
  - `service/.../clash/module/SceneModule.kt:44-63`、`scene/SceneStore.kt:15-22`
  - 触发：`requestEvaluation()` 来自 `onCapabilitiesChanged`（高频）；而 `SceneStore.scenes` 的 getter **每次访问**都读 SharedPreferences + `Json.decodeFromString` + 重排优先级，无任何缓存。Wi-Fi 抖动时几秒内几十次完整解析 + 可能的 `patchOverride`——持续 CPU/GC 压力，用户感知得到的耗电。
  - 修法：`requestEvaluation` 用 `Channel(CONFLATED)` + 几百毫秒 debounce；`SceneStore` 缓存已解析列表，写入时失效。

- [x] **B-43 场景通知每次都用新 id，通知栏被刷屏**
  - `service/.../scene/AutomationNotifier.kt:45-67`
  - 触发：在两个 Wi-Fi 之间来回切换（或信号抖动导致反复命中/失配）时每次都新增一条通知，短时间堆几十条，用户只能全部划掉。
  - 修法：固定 id 覆盖更新 + 最小间隔冷却；同一场景重复命中不重发。

- [x] **B-44 合作方授权明文存 SharedPreferences 并随备份/换机迁移带走**
  - `service/.../store/PartnerGrantStore.kt:52-74`
  - 触发：授权项 `"pkg|sha256|expires"` 全明文，而 `data_extraction_rules.xml` / `full_backup_content.xml` 都含 `domain="sharedpref"`——备份会把"哪些第三方应用已获授权"整套搬到新设备（甚至攻击者设备），**绕过用户重新确认**。`decide`/`revoke` 还是多次非原子写，中途被杀留半写状态。另外 `decisionOf` 只比 sha256，而 `tunnelablePackages` 会重新校验签名者摘要——两条路径信任级别不一致。
  - 修法：授权集排除出备份或经 `SecureStorage` 加密；`decide`/`revoke` 合并为单次提交；两条路径统一走签名者校验。
  - 清单项：④ 安全（多租户/第三方隔离）+ ① 一致性
  - 落地：`decide`/`revoke` 合并为单次 `putStringSet×3` 提交；备份规则改为**按文件 opt-in**（`data_extraction_rules.xml` / `full_backup_content.xml` 只列 `app.xml` 与 `ui.xml`），`service.xml` 因此不进云备份与换机迁移（同时把 age 私钥也挡在备份外）；`.github/scripts/verify-repository-policy.py` 的备份断言同步改为强制这份白名单，日后新增的偏好文件默认不备份。`decisionOf` 的 KDoc 明确要求传入**实时读取**的签名摘要，与 `tunnelablePackages` 同级校验。未引入 Keystore 加密（仓内并无 `SecureStorage` 实现）。

- [ ] **B-45 三条独立的 2 秒 ticker 各自查询内核，职责重叠**
  - `TrafficHistoryModule.kt` / `LocalTrafficAccountingModule.kt` / `DynamicNotificationModule.kt`
  - 触发：同一份数据被取三遍，其中 `queryDashboardSummary` 在 Go 侧涉及 `runtime.ReadMemStats`（stop-the-world）。VPN 常开时每 2 秒 3 次唤醒 + 多次 JNI 往返 + 一次 STW；三者时间戳还各自独立，导致通知显示与历史样本对不齐。
  - 修法：抽一个采样 Module 统一取一次并分发给消费者；内存统计降到更低频率。

- [x] **B-46 拆卸阶段 `unregisterReceiver` 不容错，一次失败跳过其余清理**
  - `service/.../clash/module/Module.kt:66-72`
  - 触发：某个 receiver 已被系统回收 → `IllegalArgumentException` → `forEach` 中断 → 后面的 receiver 全部泄漏；异常还发生在 `NonCancellable` 块内，会**盖掉原始退出原因**。
  - 修法：逐项 `runCatching`；并把"用 null intent 当关闭信号"的约定换成显式 `close()`。

- [x] **B-47 `cancelAndJoinBlocking` 名不副实，并未 join**
  - `service/.../util/Coroutine.kt:14`
  - 触发：调用方按名字以为返回后协程已完全结束并去做后续清理（关文件、释放 fd、重启服务），实际协程还在跑——这正是 A-15"先停后启永久卡在加载中"的成因。
  - 修法：保留名字、把实现补齐成真正的 cancel + join（带上限超时）。
  - 清单项：第 9 章"抽象泄漏 / 名字撒谎"

- [x] **B-48 时区上报用 `rawOffset`，忽略夏令时**
  - `service/.../clash/module/TimeZoneModule.kt:15-20`
  - 触发：夏令时地区上报比实际少一小时，内核侧基于时间的规则与日志时间戳整体偏移，场景的时间窗口也会错位。
  - 修法：`getOffset(System.currentTimeMillis())`。

- [x] **B-49 失效转移冷却用墙钟，时间前跳可直接绕过**
  - `service/.../scene/NodeFailoverStateMachine.kt:76-90`
  - 触发：代码已处理时间回拨（`elapsed < 0`）却没处理前跳。NTP 校准让墙钟前跳几分钟 → 所有节点冷却同时失效 → 抖动保护形同虚设，可能连续切换节点。
  - 修法：改用单调时钟（纯函数保持时钟注入）。

- [x] **B-50 `failureStreaks` 不清理已消失的节点**
  - `service/.../scene/NodeFailoverStateMachine.kt:65-68`
  - 触发：只增不减。频繁更新订阅（节点名每次都变）的用户 map 单调增长；同名节点日后回来会带着历史计数直接触发阈值。
  - 修法：每次 `onHealthCheckCompleted` 按当前节点列表求交集剔除。

- [ ] **B-51 时间窗口 `start == end` 被当作全天，语义未文档化**
  - `service/.../scene/SceneEngine.kt:85-87`
  - 触发：用户想配"22:00 到 22:00 不启用"，实际得到 24 小时全天生效；忘勾星期（`days.isEmpty()`）则场景静默永不触发。两种情况都没有任何反馈。
  - 修法：补注释说明这两条约定，并在 UI 层校验/明示。

- [x] **B-52 场景列表整表读改写，跨进程并发编辑互相覆盖**
  - `service/.../scene/SceneStore.kt:15-51`
  - 触发：setter 把整个 `List<Scene>` 序列化后整体写回，无版本号无 CAS。UI 进程编辑的同时服务进程写入优先级归一化结果 → 后写者整表覆盖，用户刚加的场景消失。
  - 修法：写入走单一入口串行化，或加 revision 做乐观并发校验。

- [ ] **B-53 `TrafficHistoryStore.buffer` 只写不读**
  - `service/.../store/TrafficHistoryStore.kt:11-13`
  - 触发：进程内环形缓冲被持续写入，但**全仓没有任何读取方**（UI 在 app 进程，读不到 `:background` 的内存）。历史数据每 2 秒写进内存后随进程结束全部丢弃——"流量历史"功能实际没有数据来源。
  - 修法：落盘或经 `StatusProvider` 暴露；否则删掉写入路径省掉这条 2s 开销。
  - 清单项：第 9 章"半成品"

- [ ] **B-54 widget 状态存在两条读取通道，进程内那条恒为 null**
  - `service/.../store/WidgetStateStore.kt:12-38`（与 B-10 同一条，两侧各记一次）
  - 修法：标为服务进程专用，或只保留 `StatusProvider` 一条通道。

- [x] **B-55 采样代码缩进误导，易被读成条件分支内的语句**
  - `service/.../clash/module/TrafficHistoryModule.kt:99-113`
  - 触发：缩进层级与外层 `if` 不匹配，视觉上像在分支内、实际无条件执行。这是 B-29 那个传错参数的缺陷长期没被发现的原因之一。
  - 修法：按实际作用域重排缩进，延迟计算抽成独立函数。

- [ ] **B-56 `SuspendModule` 中 `healthCheckAll` 位于不可达分支**
  - `service/.../clash/module/SuspendModule.kt:37-41`
  - 触发：写在前置条件永不成立的 `else` 里。恢复挂起后本应做的一轮全量健康检查从不执行，节点状态在恢复后长时间是过期数据（与 B-28 相互印证）。
  - 修法：确认意图后移到正确分支，或删掉这段死代码。

- [ ] **B-57 单测只覆盖 happy path，本次审查的边界缺陷全部测不到**
  - `service/src/test/.../scene/SceneEngineTest.kt`、`NodeFailoverStateMachineTest.kt`、`clash/module/TrafficHistoryBufferTest.kt`、`store/SubscriptionTrafficBillingPreferenceTest.kt`
  - 触发：8 个测试文件都只验证正常路径与基础归一化。时钟回拨/前跳、`resolve` 返回 null 后的回滚、并发 add/reset、节点消失后 streak 清理——**上述任一缺陷被引入或回归时测试全绿**。
  - 修法：给纯函数层补边界用例，尤其是把时钟作为参数注入后的时间异常场景。
  - 清单项：② 韧性 + 第 9 章"品味"

#### B-4 core 与数据层 (g5)

- [x] **B-58 `SoftReference` 单例可能在同一个库文件上开出第二个 Room 实例**
  - `service/.../data/Database.kt:31-38`
  - 触发：内存压力下 SoftReference 被回收，下一次访问重建实例；若旧实例仍被某个协程持有，同一 SQLite 文件上就有两个 Room（各自的写锁与失效追踪互不知情）——数据竞争与 `SQLiteDatabaseLockedException`。
  - 修法：改成真正的进程内单例（`by lazy`），Room 自己会管连接池。

- [x] **B-59 没有事务原语，"插入 Imported + 删除 Pending + 移动目录"三步各自独立**
  - `service/.../data/Daos.kt:3-13`
  - 触发：三步之间任何一处被杀（内核进程随时可能被系统回收）就留下半状态：配置在库里但文件不在，或文件已移动但 Pending 行还在。这是本项目最核心的写路径。
  - 修法：给这组操作提供 `@Transaction` 方法；文件移动的不可回滚部分放在事务提交后并做成幂等。
  - 清单项：① 事务边界

- [x] **B-60 Room 转换器抛异常，一行坏数据让整个查询返回空**
  - `service/.../data/Converters.kt:14-26`
  - 触发：`toUUID` / `toProfileType` 遇到坏值直接抛，Room 把异常上传，整条查询失败——**一行坏数据 = 配置列表整个空白**，用户以为配置全丢了。
  - 修法：坏行跳过（转换器返回可空 + 查询侧过滤），并记日志便于定位。
  - 清单项：② 降级

- [x] **B-61 `Imported` 与 `Pending` 是重复的实体定义，`createdAt` 默认值还不一致**
  - `service/.../data/Imported.kt` ↔ `Pending.kt`
  - 触发：两张孪生表的字段定义各写一遍，默认值已经开始漂移；任何一侧加字段都要记得改另一侧（本次审查已发现 B-62 的冲突策略也漂了）。
  - 修法：抽公共基类/接口，或明确一张表 + 状态字段。
  - 本轮：`createdAt` 默认值已统一为 `System.currentTimeMillis()`。抽公共基类/单表会破坏 `copy()`（`ProfileManager.patch`/`ProfileProcessor` 依赖）且需 schema 迁移，留待后续批次。
  - 清单项：第 9 章"不一致的丑"

- [x] **B-62 孪生表上的冲突策略相反：`Pending` 用 REPLACE，`Imported` 用 ABORT**
  - `service/.../data/PendingDao.kt:24,27` ↔ `ImportedDao.kt:18,21`
  - 触发：同一个导入流程的两个阶段对"重复 uuid"做出相反反应——一边静默覆盖、一边抛异常。哪种是对的没人写下来，于是行为取决于流程走到哪一步。
  - 修法：明确并统一语义（重复导入应当覆盖还是拒绝），写进注释。

- [x] **B-63 `ImportedDao.insert` 返回无意义的 `Long` rowid**
  - `service/.../data/ImportedDao.kt:19`
  - 触发：主键是 UUID，rowid 对调用方毫无意义，却让人以为可以拿它做后续引用。
  - 修法：返回 `Unit`。

- [x] **B-64 `Selection` 的 `onUpdate = CASCADE` 是死规则，且暂存阶段的选择无处安放**
  - `service/.../data/Selection.kt:16`
  - 触发：主键是 UUID、从不更新，CASCADE 永不触发（无害但误导）。真问题是 Pending 阶段用户已经选了节点，而 `Selection` 只关联 `Imported`——这段选择在导入完成前无处存放，导入后需要重新选。
  - 修法：删掉死规则；为暂存阶段的选择设计落点。

- [ ] **B-65 `DateSerializer` 硬编码 LONG 描述符，并把可变的 `java.util.Date` 跨进程传**
  - `core/.../util/Serializers.kt:11-21`
  - 触发：硬编码描述符在换编码格式（JSON ↔ 二进制）时会静默出错；而 `java.util.Date` 是**可变**对象，跨进程/跨线程共享时任何一方调 `setTime` 都会改到别人手里那份。
  - 修法：用标准的 `PrimitiveSerialDescriptor`，模型层改用不可变时间类型（`Instant`/`Long`）。
  - 本轮：描述符已改用标准 `PrimitiveSerialDescriptor("Date", LONG)`（核对时已确认）；类型迁移牵动 service/app 两模块，留待后续批次。

- [x] **B-66 `Flag` 无法表达"文件存在但不可读"**
  - `service/.../document/Flag.kt:3-5`
  - 触发：权限问题、加密目录未解锁、文件被占用这三种情况都被压成"不存在"，于是上层走"新建"分支——用**空配置覆盖掉实际存在的用户数据**。
  - 修法：把"不可读"作为独立状态，让上层能选择报错而不是重建。
  - 清单项：① 数据一致性

- [x] **B-67 `exportSchema = false`，没有 schema 基线也没有迁移测试**
  - `service/.../data/Database.kt:25`
  - 触发：Room 的迁移正确性完全靠人眼。本次审查里 A-10 / A-11 这类迁移缺陷之所以"无法本地验证"，根因就在这里。
  - 修法：打开 `exportSchema`，把 schema json 纳入版本管理，加 `MigrationTestHelper` 测试（CI 跑）。
  - 本轮：`exportSchema = true` + KSP `room.schemaLocation` 已开，`service/schemas/` 已建目录；CI 下次构建即生成 schema json 基线。生成物落库与 `MigrationTestHelper` 用例需本地构建，留待后续批次。
  - 清单项：① 一致性 + 可验证性

- [x] **B-68 流量量化换算的三处疑点**
  - `core/.../util/Traffic.kt:24-29`（`trafficTotal` 把两个可能不同单位/类型的半值直接相加）、`:54-65`（`scaleTraffic` 单位不一致，疑似 100 倍低报）、`:68-79`（`decodeTrafficBytes` 整数除 100，未知单位类型静默返回 0）
  - 触发：低报会让"已用流量"长期偏小；静默返回 0 会让未知单位的样本变成"没有流量"而不是"不知道"。
  - 修法：先确认编解码约定（两位小数 + 单位位），只修能证明的那几条；未知单位至少记日志或走明确失败路径。**不得**改动会让已落盘历史数据被重新解释成不同数值的编码约定。
  - 注：g4 独立给出反对意见——量化误差在同一单调计数器的差值中相消，最坏只在单位边界丢 ≤10KB。两组结论冲突，以读码结论为准。
  - **协调者定夺（g5 对，但只对一半，且已按此修完）**：
    - g4 说的"误差在差值中相消"只覆盖 `LocalTrafficAccountingModule` 那种"同一单调计数器取差"的用法。`trafficUpload()` / `trafficDownload()` / `trafficTotal()` 是**单值直读**，没有差值可以相消 —— 老的 `scaleTraffic` 不除 100、`trafficString` 又对 GiB/MiB/KiB 各除一次 100，于是"Bytes 档偏大 100 倍、KiB 及以上档正好抵消"，量级错在哪取决于落在哪个档。g5 的读码结论成立。
    - **已修**：删掉 `scaleTraffic`，三个格式化函数一律先走 `trafficUploadBytes()` / `trafficDownloadBytes()`（即 `decodeTrafficBytes`）拿到**原始字节**再格式化；`trafficString` 改成对 `KIB/MIB/GIB` 常量正常取商，不再藏第二次 `/100`。`trafficTotal` 也不再把两个各带单位位的半值相加 —— 那是把不同单位的数直接相加，改成两边都解码成字节后再加。
    - **编码约定没动**（守住"不得让已落盘历史被重新解释"这条）：`decodeTrafficBytes` 里 type 0 = 原始字节、type 1..3 = 单位的百分之一，与 Go 侧编码保持原样。
    - g5 报的第三条"未知单位静默返回 0"**不成立**：`type = (value ushr 30) and 0x3` 只可能是 0..3，四个分支全覆盖，`else -> 0L` 是不可达分支，留着只是为了让 `when` 穷尽。

- [x] **B-100 `Picker.pick` 先产生副作用再做校验，失败留下孤儿 Pending 行与目录 (g5)**
  - `service/src/main/java/com/github/kr328/clash/service/document/Picker.kt:52,60`
  - 触发：`:52` 已经调用 `cloneToPending(uuid)`（插行 + 复制整个配置目录），`:60` 才做会抛异常的合法性校验，异常后没有任何回滚。SAF 客户端拿着旧 documentId 重放（profile 已删或类型不支持）即可：调用方收到失败，库里多了一条永远无人清理的 pending 记录和一份配置副本——副本里可能含 `ageSecretKey`。
  - 修法：所有校验前移到任何副作用之前；`cloneToPending` 内部失败要回滚（删行 + 删目录）。

- [x] **B-101 `cloneToPending` 先插行再 delete + copy，无锁、无回滚、非原子 (g5)**
  - `service/src/main/java/com/github/kr328/clash/service/document/Picker.kt:123-147`
  - 触发：顺序是"插 Pending 行 → `target.deleteRecursively()` → `source.copyRecursively(target)`"，目标目录先被清空、复制期间处于不完整状态，全程没有任何锁。同一 profile 被并发 `pick`（SAF 编辑器与应用内编辑同时打开），或复制中途进程被杀 → Pending 行存在而目录是空的，之后 `apply` 拿空配置覆盖掉原本可用的 imported 配置。
  - 修法：复制到临时目录再 `renameTo` 做原子替换；per-uuid `Mutex` 串行化；行的插入放在复制成功之后。

- [x] **B-102 用位置参数构造 11 字段实体，插字段会静默错位 (g5)**
  - `service/.../document/Picker.kt:137`、`service/.../data/migrations/LegacyMigration.kt:95,169`
  - 触发：写法是 `Pending(uuid = …, name = …, type = …, source = …, interval = …, 0,0,0,0)`——后四个 `upload/download/total/expire` 靠位置，且同为 `Long`。将来在 `interval` 与 `upload` 之间插入任何 Long 字段，这三处会静默错位：`upload` 被写进新字段、`expire` 归零，表现为订阅流量与到期时间莫名变 0，编译期毫无提示。
  - 修法：全部改命名参数；对这类多同型字段的实体加静态检查规则禁止位置构造。

- [x] **B-103 `Paths.resolve` 对 `..` 采取过滤而非拒绝 (g5)**
  - `service/src/main/java/com/github/kr328/clash/service/document/Paths.kt:10`
  - 触发：`filter { it.isNotBlank() && it != "." && it != ".." }` 把穿越段悄悄删掉，然后把剩下的段当合法路径继续 resolve；同一函数对未知 scope 却是 `throw`（`:28`/`:37`），标准不一致。SAF 侧传 `<uuid>/providers/../../../../shared_prefs/x.xml`，过滤后变成 `<uuid>/providers/shared_prefs/x.xml` 被当正常 relative 路径处理——攻击输入被改写成另一个合法路径继续执行，是否真越界取决于下游 `FileDocument` 的拼接。
  - 修法：遇到 `..`、空段、绝对路径前缀、`\` 一律抛 `IllegalArgumentException`；resolve 结果再做一次 `canonicalPath` 前缀断言。
  - 注：g3 认为此处**不该修**（`Paths` 已过滤穿越段、`PatternFileName` 禁 `/`，再加 canonical 校验属重复防御）。两组结论冲突，待协调者定夺。
  - **协调者定夺（本条按 g5 改，但重新定性）**：两组各对一半 ——
    - **可利用性上 g3 对**。顺着 g5 的例子走完：过滤后 `segments = [uuid, providers, shared_prefs, x.xml]` → `relative = ["shared_prefs", "x.xml"]`，下游是在 **providers 目录内**再往下拼，结果落在 `<profile>/providers/shared_prefs/x.xml`，仍在 profile 目录里。**不存在越界读写**，不是漏洞，也就不该按漏洞排优先级。
    - **设计上 g5 对**。同一个函数对"未知 scope"抛异常、对"穿越段"却静默改写调用方的输入并按改写后的路径继续执行 —— 这是第 9 章的"不一致的丑"。而且当前的安全性完全是**位置性**的：只因为"先过滤再拼接"这个顺序才没事，将来任何人把 `resolve` 改成保留原段、或让下游直接 `File(base, rawPath)`，防线就无声消失。
    - **已修（按"严格性/一致性"而非"补漏洞"来改）**：`require(segments.none { it == "." || it == ".." }) { "invalid path $path" }`，与同函数对未知 scope 的 `throw` 对齐。**空段仍然过滤**不能一起拒 —— 根 documentId 就是字面量 `"/"`（`FilesProvider.openDocument`/`deleteDocument`/`renameDocument` 都用 `documentId ?: "/"` 兜底），拒空段会把根路径本身打死。`canonicalPath` 前缀断言未加：拒绝之后它就是纯重复防御，按 g3 的意见不引入。

- [x] **B-104 未知 legacy 类型被 `continue` 静默跳过，随后旧库被删除 (g5)**
  - `service/.../data/migrations/LegacyMigration.kt:81-83,151-155`
  - 触发：v2/3/4 与 v1 两条路径对无法识别的旧配置都是 `else -> continue`，不记日志、不计数；而外层 `migrationFromLegacy` 在没有异常的情况下会 `deleteDatabase("clash-config")`。旧库里存在 type 不在 `{1,2,3}` 内的行（旧分支版本写入，或 token 既不以 `file|` 也不以 `url|` 开头）时，该配置被跳过、随后旧库被删，用户永久丢失这条配置且完全无提示。
  - 修法：跳过时至少 `Log.w` 并累计计数；有任何行被跳过就**不要**删旧库，改为保留并提示"N 条旧配置无法识别"。

- [x] **B-105 `Parcelizer` 顺序解码，跨进程格式没有版本头也没有字段索引容错 (g5)**
  - `core/src/main/java/com/github/kr328/clash/core/util/Parcelizer.kt:20`
  - 触发：`decodeSequentially() = true` 配 `beginStructure` 直接 `return this`——格式就是"按声明顺序裸排的字段"，既没有 magic/版本号也没有字段计数。UI 进程与 `:background` 在升级瞬间短暂运行不同版本（后者未被一起杀），或同签名旁包互调时，某个 model 增删一个字段后读侧按旧顺序解析，得到位移后的垃圾值（把 Long 当 Int 读之类）——**不抛异常，静默读出错误数据**。
  - 修法：`encodeToParcel` 先写 magic + schema 版本，`decodeFromParcel` 校验不匹配即明确失败；跨进程模型只允许"末尾追加 + 显式字段计数"的演进方式，并写进注释约束。

- [x] **B-106 反序列化把另一进程的输入当可信数据：集合长度无上限、`readString` 用 `!!` (g5)**
  - `core/.../util/Parcelizer.kt:63-65,118-120`
  - 触发：`decodeCollectionSize` 直接 `return decodeInt()` 没有上限，kotlinx 会按这个长度预分配；`decodeString()` 是 `parcel.readString()!!`。一旦发生 B-105 的错位读取或 Parcel 被截断，`decodeInt()` 可能返回一个巨大的数 → 立即 OOM，`readString()` 返回 null → NPE，两者都发生在 binder 线程上。
  - 修法：集合长度做上限校验并抛可捕获的解码异常；`readString()` 为 null 时按空串/可空处理；`decodeFromParcel` 的所有调用点统一 catch。

- [x] **B-107 `Clash` 里另外五处严格 Json 解码没有兜底 (g5，与 A-27 同型)**
  - `core/src/main/java/com/github/kr328/clash/core/Clash.kt:44,120,132,230,429`
  - 触发：`queryTunnelState`、`queryGroup`、`queryGroupDelays`、`queryProviders`、`parseAgeKeyPair` 都用默认严格 `Json` 解码内核输出、没有 `runCatching`，与同文件 `:148`/`:268`/`:367` 的写法不一致。内核给代理组或 provider 的 JSON 多一个字段，代理页整页打不开（正确行为是少显示一项）；`parseAgeKeyPair` 则让密钥导入直接抛异常而不是给出可读错误。
  - 修法：统一用宽松 `Json`（`ignoreUnknownKeys`）；解码失败返回上次成功值或空列表 + 明确错误态，不要静默返回默认值再被写回。

- [ ] **B-108 `startTun` 的 `TunInterface` 丢弃 `markSocket` 返回值，地址解析异常会穿回 native (g5)**
  - `core/src/main/java/com/github/kr328/clash/core/Clash.kt` 的 `startTun` 回调对象（`markSocket` / `querySocketUid` 两个 override）
  - 触发：`override fun markSocket(fd: Int) { markSocket(fd) }` 把外层返回的 `Boolean` 丢掉——`VpnService.protect` 失败无人知道，内核继续用未保护的 socket，流量绕回 TUN 形成环路；`querySocketUid` 里对内核传来的 `source`/`target` 调 `parseInetSocketAddress`，遇到带 scope 的 IPv6 或空串会抛异常，而这是 native 线程上的回调。
  - 修法：`markSocket` 的失败向上传递（参与内核决策或触发停止并记日志）；`querySocketUid` 整体 `runCatching`，失败返回约定的"未知 uid"。
  - 本轮：`querySocketUid` 已整体 `runCatching`→-1；`markSocket` 失败记日志。失败向上传参与内核决策需 Go/C 桥签名改动（`mark_socket` 返回 bool），留待后续批次。

- [x] **B-109 `Net` 的地址解析会触发阻塞 DNS，而它在每连接热路径上 (g5)**
  - `core/src/main/java/com/github/kr328/clash/core/util/Net.kt:29`
  - 触发：`InetAddress.getByName(host)` 在参数不是纯数字字面量时会做一次**阻塞的正向 DNS 查询**，而该函数被 `querySocketUid` 一类 per-connection 回调使用。内核传来的 target 含主机名、或 IPv6 写法未命中快路径时，native 线程上阻塞数百毫秒到超时，新连接建立被拖慢；更糟的是 DNS 本身要走内核，形成自依赖甚至互等。
  - 修法：改用只做字面量解析的 API（`InetAddresses.parseNumericAddress` 或手写解析），永不触发名称解析；解析不了返回 null 让调用方走"未知"分支。

- [ ] **B-110 `Bridge` 在 object 初始化块里 loadLibrary + getPackageInfo + mkdirs，失败即永久不可用 (g5)**
  - `core/src/main/java/com/github/kr328/clash/core/bridge/Bridge.kt:84-97`
  - 触发：`init` 里除了 `System.loadLibrary("bridge")` 还有 `getPackageInfo` 与 `mkdirs()`。这是 `object` 的静态初始化，任何一步抛异常都变成 `ExceptionInInitializerError`，此后同进程内每次访问 `Bridge` 都抛 `NoClassDefFoundError`，原始原因不在堆栈里。split APK 缺当前 abi、存储不可写、应用更新中 `getPackageInfo` 抛异常——用户侧表现为"一打开就闪退且重启无用"。
  - 修法：拆出显式的 `fun init(context): Result<Unit>` 由服务启动流程调用并把失败转成可展示错误；初始化块只保留 `loadLibrary`。
  - 关联：A-26（同一形状的类初始化污染）、B-78（这个 init 被 UI 进程意外触发）。
  - 本轮：`init` 块只剩 `loadLibrary`，脆性步骤移入 `fun init(context): Result<Unit>`（幂等、失败不缓存可重试），经 `Clash.reset()` 在服务启动路径调用。注意：UI 进程 `MainActivity` 直接调 `Bridge.nativeCoreVersion()`，不再有类加载时的 nativeInit（与 B-78 同方向，但核对该 native 是否依赖 init 状态需 Go 侧确认）。

- [x] **B-111 `Content.open` 把三种异常抛给 native 调用者，`detachFd` 移交的 fd 无任何校验 (g5)**
  - `core/src/main/java/com/github/kr328/clash/core/bridge/Content.kt:11-20`
  - 触发：`open(url)` 会抛 `UnsupportedOperationException` / `SecurityException` / `FileNotFoundException`，而它是给内核调用的；同时用 `detachFd()` 把 fd 所有权交给 native，却不校验目标是普通文件还是管道/socket，也不设大小上限。内核请求一个 `content://` 资源而用户已撤销该 URI 权限或文件已删 → 异常从 JNI 回调抛出；URI 指向永不 EOF 的流或极大文件 → 内核侧读取无限挂住并泄漏 fd。
  - 修法：`open` 内部捕获全部异常并按约定返回无效 fd（-1）；移交前用 `statSize` + 文件类型校验，超限或非普通文件直接拒绝；注释写明 fd 所有权与各失败路径的 close 责任。

- [x] **B-112 分片 Parcelable 的分片大小写死，且承载分片的 Binder 只存在局部变量里 (g5)**
  - `core/.../model/ProviderList.kt:9`、`core/.../model/ProxyGroup.kt:16`（机制在 `common/.../util/Parcelable.kt:36-40`）
  - 触发：分别硬编码 `createListFromParcelSlice(parcel, 0, 20)` 与 `(parcel, 0, 50)`——按**条数**而非字节切，单个 provider/组名极长时 20/50 条一片仍可能越过 1MB；而 `writeToParcelSlice` 里 `SliceParcelableListBpBinder` 只被局部变量持有，写完 `writeStrongBinder` 后就没有强引用，若写侧函数已返回且 GC 介入，读侧回调远端 binder 可能读到不完整列表或 `DeadObjectException`。
  - 修法：把分片 binder 挂到调用方对象上（或用一次同步握手确认读完再释放）；分片按累积字节预算切而不是按条数写死。
  - 关联：同一机制在 common 侧还有三处具体 bug，见 B-5 小节（`tFlags`、静默截断、参数不校验）。
  - 本轮：binder 生命周期前提不成立（Android 上 outstanding proxy 会扎根节点），且改挂在 `common/.../util/Parcelable.kt`，属于 common 批次——与 B-5 小节合并处理。
  - 已修：`common/.../util/Parcelable.kt` 的 `SliceParcelableListBpBinder` 修复三点——非分片事务用本次事务的 `tFlags` 转发给 `super.onTransact`；offset/chunk 用 `coerceIn` 夹紧（`MAX_CHUNK=50`，越界返回空片不崩）；读侧 `transact` 失败不再 `break` 静默截断，抛 `SliceReadException`（带 received/expected/offset）供调用方重试；服务端返回空窗口而 `offset < total` 同样抛 `SliceReadException`。"binder 只存局部变量"维持"本轮"判定（outstanding proxy 在 Android 上扎根节点），未改。

- [x] **B-113 `ConfigurationOverride` 整棵树 all-or-nothing 解码，三个嵌套枚举没有未知值兜底 (g5)**
  - `core/.../model/ConfigurationOverride.kt:152-182`（`FindProcessMode` / `DnsEnhancedMode` / `FilterMode`）
  - 触发：三个枚举每项都有 `@SerialName`（这部分是对的），但都没有未知值兜底项、也没有自定义 serializer，配上整体解码不容错——上游 mihomo 给 `find-process-mode` / `enhanced-mode` / `fake-ip-filter-mode` 增加一个取值（历史上多次发生），或用户手改覆写文件，整份 `ConfigurationOverride` 解码失败，直接引爆 A-28 的清空链路。
  - 修法：三个枚举各加 `Unknown` 兜底（配 `@JsonNames` 或自定义 serializer），未知值落 `Unknown` 并原样保留写回；覆写解码开 `ignoreUnknownKeys` 并逐段容错。

- [x] **B-114 迁移导入逐条 `runCatching`、无事务，失败留下半套数据 (g5)**
  - `service/.../migration/MigrationBundle.kt:152-211`
  - 触发：导入循环里每条记录各自 `runCatching`，整批没有事务，完成标志的写入与"实际导入了多少条"脱钩。导入到中途磁盘写满或进程被杀：前面已入库、后面没有，而完成标志可能已置；重跑时按 `PendingDao` 的 REPLACE 覆盖、按 `ImportedDao` 的 ABORT 抛异常（见 B-62），用户看到"迁移过来一半"。
  - 修法：整包导入放进一个 Room 事务；目录先落临时位置、事务提交后原子改名；完成标志与实际条数一起写，便于断点续做。

- [x] **B-115 四份手写 JSON 映射与实体并行演化 (g5)**
  - `service/.../migration/MigrationBundle.kt:392-452`（`Imported.toJson` / `Pending.toJson` / `JSONObject.toImported` / `JSONObject.toPending`）
  - 触发：实体本可用 kotlinx.serialization，这里却手写了四个 `JSONObject` 映射，而 `optLong`/`optString` 会把"字段缺失"伪装成 0 或空串。给实体加字段时漏改这四个函数——`ageSecretKey` 正是这种字段：迁移过来的订阅表面正常，实际私钥丢失，直到下次更新订阅解密失败才暴露。
  - 修法：删掉手写映射，直接用 `@Serializable` 实体（配 `@SerialName` 固定线格式）；bundle 内写 schema 版本，读侧按版本决定兼容策略。

- [x] **B-116 `withTimeout` 包着的同步导出没有挂起点，超时不生效；超时分支又去删仍在写的文件 (g5)**
  - `service/.../migration/MigrationProvider.kt:81-89`
  - 触发：`runBlocking { withTimeout(EXPORT_TIMEOUT_MS) { MigrationBundle.exportToZip(ctx, file) } }`——`exportToZip` 是同步文件/zip IO，协程取消只在挂起点生效，所以这个 20s 超时对真正卡住的导出完全无效，注释里想避免的 binder 线程 ANR 依然发生；而若超时真的触发，`file.delete()` 删掉的是**另一条路径仍在写入**的目标文件（`cachedBundle` 可能还指着它）。
  - 修法：导出改成可取消（循环里 `ensureActive()`）或放到独立线程、超时后仅放弃引用；写临时文件、成功后 rename 到 `bundleFile`。

> 未判定（g5）：按约束未读 `core/src/foss/golang` 下的 Go 子模块，因此 A-27 与 B-107 / B-108 / B-111 描述的都是 **Kotlin 侧的契约缺口**；"异常越过 JNI 是否真的终止进程、移交的 fd 最终由谁 close"需要对着 Go 的 JNI 胶水层再核一遍才能最终定级。

#### B-5 common / sdk / 构建 (g6)

- [x] **B-69 CI 既不 assemble 也不发布 `:sdk`，它的编译错误只有下游才会发现**
  - CI workflow 与 `sdk/build.gradle.kts`
  - 触发：`:sdk` 是给第三方嵌入用的模块，却不在 CI 的构建目标里。它坏了要等到有人集成时才知道。
  - 修法：CI 至少 `assemble` `:sdk`，并加 API 快照校验（见 C-02）。
  - 已修：`build-debug.yaml` 与 `build-pre-release.yaml` 在 JVM 单测前新增 `Assemble SDK facade` 步骤（`./gradlew :sdk:assemble`，带 GITHUB_TOKEN 环境）。API 快照校验属 C-02，推迟。
  - 验证补充：该步骤第一次真编译 `:sdk`，立刻暴露潜伏缺陷（此前 `:sdk` 从不被 `:app` 依赖，永远不构建）：
    1. 根 `build.gradle.kts` 的 flavor `resValue` 把 `launch_name`/`application_name` 注入**所有**子项目，而 `launch_name_alpha` 只定义在 `:design`；单独构建不依赖 `:design` 的 `:sdk` 时 AAPT 报 `resource ... not found`。改为仅 `:app`、`:design` 与 `:service` 注入（前两者消费自己的 R 内这两个字符串；`:service` 的 R 被 `TileService` 引用，见第 3 条）。
    2. `sdk/.../internal/EventHub.kt` 用 `asSharedFlow()` 扩展但漏 import，编译失败。补 `import kotlinx.coroutines.flow.asSharedFlow`。
    3. 逐轮解开后，暴露 app 模块 batch-3 三处编译缺陷：
       - `LogcatActivity.bindLogcatService` 在 `suspendCancellableCoroutine` 块**之前**创建 `ServiceConnection`，`onServiceConnected` 却引用块内才定义的 `ctx`（作用域外不可见，还连带 T 推断失败）。改为捕获回调 `onResult: ((Result<LogcatService>) -> Unit)?`，在协程块里装配到 continuation。
       - `SettingsActivity` 的 `select<Unit> {}` 块内 `this` 是 `SelectBuilder<Unit>` 而非 Activity，`MaterialAlertDialogBuilder(this)` 类型不匹配。显式 `this@SettingsActivity`。
       - `TileService` 用 `service.R.string.launch_name` 设置 QS tile 标签；`:service` 不依赖 `:design`，引用式注入本就无法解析，guard 收紧后该资源直接消失。`:service` 改为注入字面量（alpha="Clash Meta Alpha"、meta="Clash Meta"，该字符串在各语言环境本就一致，未破坏本地化）。

- [x] **B-70 `-dontobfuscate` 掩盖了缺失的 keep 规则；四个模块的 `consumer-rules.pro` 是空的**
  - `app/proguard-rules.pro`、各模块 `consumer-rules.pro`
  - 触发：现在不混淆所以没暴露问题，但压缩（shrink）仍在进行，反射/序列化/JNI 入口若缺 keep 规则会在某次开启混淆时集体爆掉。空的 consumer-rules 意味着库模块没有向使用方声明自己的保留需求。
  - 修法：为反射与序列化入口补 keep 规则，逐模块填 consumer-rules。
  - 关联：[R8 删 ComponentRegistrar 构造] —— 本机已有过一次同类事故（minify release 下组件发现失效）。
  - 已修：五个模块的 `consumer-rules.pro` 逐份填写并注明理由——`core`：序列化模型（`$$serializer` + `serializer()` + Companion）与 `core.bridge.**` 整包（JNI 入口，为将来开启混淆保护）；`service`：Room（`Database` + `@Entity`）与序列化模型；`common`/`design`/`hideapi`：无反射/序列化/JNI 入口，写注释说明无 keep 需求。

- [x] **B-71 `sdk` 的 `ServiceConnection` 缺 `onBindingDied` / `onNullBinding`**
  - `sdk/src/main/java/com/github/kr328/clash/sdk/internal/RemoteSession.kt`
  - 触发：A-04 已修好"绑定被拒"（返回 false），但"绑定活着然后死了"（`onBindingDied`）和"服务返回了 null binder"（`onNullBinding`）这两条回调仍未实现，宿主应用会停在一个永远不会恢复的绑定上。
  - 修法：两个回调都接上，走与 `onServiceDisconnected` 相同的 `unbind()` + `onCrashed()` 路径。
  - 清单项：② 失败路径
  - 已修：`RemoteSession` 的 ServiceConnection 新增 `onBindingDied`（无通知的绑定死亡，先 unbind 以便后续 bind 重连）与 `onNullBinding`（契约违约，`remote.set(null)` + unbind + `onCrashed()`），三者统一走 `handleDeath`（含 TOGGLE_CRASHED_INTERVAL 防抖）。

- [x] **B-117 `EventHub` 事件用 `tryEmit` 静默丢弃，缓冲仅 32 且失败不记日志 (g6)**
  - `sdk/src/main/java/com/github/kr328/clash/sdk/internal/EventHub.kt:20,58`
  - 触发：`MutableSharedFlow(extraBufferCapacity = 32)` 配 `_events.tryEmit(event)`，返回值被丢弃。批量更新订阅时 `:service` 连续广播多条 `ProfileUpdateCompleted`/`ProfileUpdateFailed`，宿主的收集协程稍慢即超过 32 条缓冲，事件被无声丢掉，宿主 UI 永远等不到某个 uuid 的完成回调。
  - 修法：`tryEmit` 失败时至少 `Log.w` 记下被丢弃的事件类型；生命周期类事件（started/stopped）用 `StateFlow` 保证最终一致，只让高频进度类事件允许丢。

  - 已修：`EventHub` 拆双通道——生命周期事件（`ServiceRecreated`/`Started`/`Stopped`）走 `MutableStateFlow` 经 `merge` 汇入 `events`，重订阅者不丢最新状态；高频进度类（`ProfileUpdateCompleted`/`Failed`）保留 `MutableSharedFlow`（缓冲 32→64），`tryEmit` 失败记 `Log.w` 不再静默丢。
  - 已修：`registered` 改 `AtomicBoolean`（`compareAndSet` 防竞态，注册失败回滚标志）；`unregister` 不再强行把 `clashRunning` 置 false；`register` 用 `probeClashRunning` 探真实服务状态，unbind→rebind 循环不再显示陈旧的「未运行」。
- [x] **B-118 `EventHub.register`/`unregister` 的 `registered` 标志无同步，且 `unregister` 强行把 `clashRunning` 置 false (g6)**
  - `sdk/.../internal/EventHub.kt:24-48`
  - 触发：`registered` 是普通可变布尔且两个方法无锁；宿主在两个线程同时调 `bind()` 与 `unbind()`（Activity 快速旋转 + 后台线程主动 unbind）时，可能重复注册接收器造成泄漏，或对未注册的接收器 `unregisterReceiver` 抛 `IllegalArgumentException`。另外 `unregister()` 无条件把 `clashRunning = false`，而该状态本应只由广播驱动——先 unbind 再 bind 后宿主按 false 渲染出"未运行"。
  - 修法：`registered` 改 `AtomicBoolean` 的 CAS 或整段加锁；`unregister()` 不动 `clashRunning`，重新 `register` 时用 `StatusClient` 主动探测一次真实状态（与 `app/.../Broadcasts.kt` 一致）。

  - 已修：sealed 层级新增 `Unknown(name)` 兜底成员，KDoc 写明兼容契约（host 须写 `else` 分支）——新 SDK 增成员既不断旧 host 编译，也不在已编译 host 上抛 `NoWhenBranchMatchedException`。
  - 已修：`ClashRuntimeConfig.bindOnVisible` 字段删除（无消费者，宿主绑定语义本由 `bind()`/`unbind()` 显式控制）。
- [x] **B-119 `ClashRuntimeEvent` 是无兜底成员的 `sealed class`，新增事件即破坏第三方 (g6)**
  - `sdk/src/main/java/com/github/kr328/clash/sdk/ClashRuntimeEvent.kt:1-17`
  - 触发：7 个 sealed 成员，没有 `Unknown`/`Other` 兜底，也没有"未来会新增成员"的契约声明。宿主对 `events` 写了穷尽 `when`（sealed 不需要 `else`），SDK 后续版本新增一个成员后宿主源码编译失败；若宿主是已编译的 APK，则命中 `NoWhenBranchMatchedException` 崩溃。
  - 修法：加入 `data class Unknown(val name: String)` 之类兜底成员并在文档里声明"必须写 `else` 分支"；或把对外事件面改为不可穷尽的接口 + 常量族，sealed 只留在 internal 层。

  - 已修：`eventHub` 从可空字段改进程级常驻单例（`val eventHub = EventHub()`），`install` 前订阅 `events` 合法且只返回空流；`attach(application)` 在 install 时绑定上下文。
- [x] **B-120 `ClashRuntimeConfig` 用 `data class` 作公开配置类型，且 `bindOnVisible` 无任何读取方 (g6)**
  - `sdk/src/main/java/com/github/kr328/clash/sdk/ClashRuntimeConfig.kt:1-19`
  - 触发：`data class` 自动生成 `copy`/`componentN`，新增构造参数会改变 `copy$default` 签名，已编译宿主调用 `config.copy(...)` 抛 `NoSuchMethodError`。同时全仓 grep 确认 `bindOnVisible` 只在此处定义、零处读取——宿主按文档设 `bindOnVisible = false` 却发现绑定行为毫无变化。
  - 修法：换成带默认值的 Builder 或普通 class + 私有构造 + 静态工厂，避免暴露 `copy`；`bindOnVisible` 要么在 `bind` 路径里真正实现，要么在发布前删除。

  - 已修：`withClash`/`withProfile` 只读路径保留有界重试；新增 `withClashWrite`/`withProfileWrite`——写操作绝不自动重试（重试会二次执行变更），失败抛 `ClashRuntimeRemoteException`；`TransactionTooLargeException` 单独放行（非瞬时故障，重试无意义）。
- [x] **B-121 `ClashRuntime.events` 的 KDoc 与实现相反：文档称"install 前为空"，实际抛异常 (g6)**
  - `sdk/src/main/java/com/github/kr328/clash/sdk/ClashRuntime.kt`（`events` 属性及其 KDoc）
  - 触发：`get() = requireEventHub().events`，而 `requireEventHub()` 在未 `install` 时抛异常，注释承诺的却是返回空流。第三方按文档在 `Application` 里先 `ClashRuntime.events.onEach{}.launchIn(scope)` 再 `install`，直接崩在启动路径上。
  - 修法：二者取一——把 `events` 改成进程级常驻的 `MutableSharedFlow`（install 前订阅合法、只是收不到事件），或修正 KDoc 明确"必须先 install，否则抛 `IllegalStateException`"。文档承诺过的行为对已发布 SDK 更值得实现。

  - 已修：`StoreProvider` 新增 `flush()`/`contains()`/`remove()`；`SharedPreferenceProvider` 用 `dirty` 标志跟踪写入，`flush()` 写 `__clash_flush_marker__` 并 `commit()` 强制同步落盘，跨进程读不再拿到陈旧值。
  - 已修：enum 委托读到未识别存储名时 `Log.w` 记录（成员被改名/删除可观测），原始值保留在盘上供迁移；顺带补上 `contains`/`remove` 通道。
- [x] **B-122 sdk 的 `withClash`/`withProfile` 只认 `DeadObjectException`，且对非幂等写操作重试 (g6)**
  - `sdk/src/main/java/com/github/kr328/clash/sdk/ClashRuntime.kt:279-321`
  - 触发：重试循环只捕获 `DeadObjectException`，`TransactionTooLargeException`、`SecurityException` 等 `RemoteException` 子类直接冒泡；同时这个通用包装对所有调用一视同仁地重试，包括 `patchProfile`、`commit`、`clearOverride` 这类写操作。服务进程在写操作已执行、返回途中被杀时，重试会把同一次写再执行一遍；`MAX_BINDER_RETRIES` 用尽后抛出的仍是裸 `DeadObjectException`，第三方拿不到可判别的错误类型。
  - 修法：重试限定在只读查询上，或要求调用方显式声明幂等；写操作失败时抛出 SDK 自有的、可区分"未执行 / 可能已执行"的异常类型。
  - 关联：B-12（app 侧同一形状的重试缺陷，已修）。

  - 已修：`typedString` 新增带显式 `default` 的重载，absent key 直接返回 default，不再依赖 `to(null)`/`from` 互为反函数；旧签名保留为便捷重载。
- [x] **B-123 `Store` 所有写入走 `edit { }` 的异步 `apply()`，跨进程读写存在可观测竞态 (g6)**
  - `common/src/main/java/com/github/kr328/clash/common/store/Store.kt`（各 `by` 委托的 setter）、`common/.../store/Providers.kt`
  - 触发：`androidx.core.content.edit` 默认 `commit = false`，落盘与跨进程通知都是异步的，而接口层没有任何"写完再读"的同步点。UI 进程改完某个开关立刻 `startForegroundServiceCompat` 拉起 `:background`，服务进程在同一瞬间读同一个 key 可能读到旧值——典型表现是"改了 TUN/端口设置后第一次启动仍用旧配置，重启一次才生效"。
  - 修法：给需要跨进程立即生效的 key 提供 `commit()` 语义的写入路径（Store 增加 `flush()` 或按 key 声明同步写）；更彻底的做法是把"启动内核所需的设置"随启动 Intent/Binder 调用一次性传给服务进程。

  - 已修：`Log.f` 原错把 `message` 当 tag、`throwable` 当 message 调 `Log.wtf` 2 参重载（真实消息丢失），现改 3 参 `Log.wtf(TAG, message, throwable)`。
  - 已修：`d`/`v` 由 `isDebug`（懒加载读 `FLAG_DEBUGGABLE`）门控，release 下不落地；全部级别统一过 `redact()`，URL 的 query 与超长路径段（订阅 token）打码后再写 logcat。
- [x] **B-124 `Store` 接口缺 `remove`/`contains`，`enum` 委托对重命名静默回退默认值 (g6)**
  - `common/.../store/Store.kt`、`common/.../store/StoreProvider.kt`
  - 触发：抽象层只有读写，无法删除键或判断键是否存在；`Store.enum` 以 `Enum.name` 持久化，反序列化找不到匹配名时直接返回默认值且不记录。重命名任一枚举成员（如 `TunnelState.Mode` 或某个 override 枚举）后升级安装，用户已保存的选择被静默重置且无任何提示；想清理废弃设置项时无 API 可用，只能绕过 Store 直接操作 SharedPreferences。
  - 修法：枚举持久化改用显式稳定标识（独立的 key 字符串常量，`ordinal` 不安全），未识别值记日志并保留原始字符串以便迁移；`Store` 补 `remove(key)`/`contains(key)`。
  - 关联：A-29（同一类"常量名当线格式"的缺陷在 Scene 上的实例）。

  - 已修：`onTransact` 非分片事务转发改用 `tFlags`（本次调用标志），不再错用入参 `flags`，同步/异步意图正确保留。
- [x] **B-125 `Store.typedString` 把 `to(null)` 的结果再喂回 `from`，null 语义在委托里回环 (g6)**
  - `common/.../store/Store.kt`（`typedString` 委托）
  - 触发：委托在缺省值处理上把 `to` 的输出当作 `from` 的输入，即"序列化一个 null 再反序列化"，依赖两个 lambda 对 null 的处理恰好互逆。任何 `to`/`from` 不严格互逆的类型（`to` 对 null 返回空串而 `from` 对空串抛异常或返回非 null），首次读取未写过的 key 时行为不可预期。
  - 修法：委托签名显式接收 `default: T`，读不到 key 时直接返回 `default`，不经过 `to`/`from` 往返；两个 lambda 只负责非空值转换。

  - 已修：新增 `SliceReadException(received/expected/offset)`；读侧收到不完整列表时抛异常而非静默截断，调用方可重试并提示「数据不完整」。
- [x] **B-126 `Log.f` 参数顺序错位，把整条消息当 TAG 传给 `Log.wtf` (g6)**
  - `common/src/main/java/com/github/kr328/clash/common/log/Log.kt:17`
  - 触发：`fun f(message: String, throwable: Throwable) = android.util.Log.wtf(message, throwable)`——`wtf(String, Throwable)` 的第一个参数是 TAG。任何调用 `Log.f` 的致命错误路径，logcat 里 tag 变成一整句消息、超过 23 字符还会被系统截断；按项目 TAG 过滤日志时，这些**最严重**的记录反而全部丢失。
  - 修法：改为 `android.util.Log.wtf(TAG, message, throwable)`，与同文件 `e`/`w`/`i` 的形式一致。

  - 已修：offset/chunk 窗口 `coerceIn(0, list.size)` / `coerceIn(1, MAX_CHUNK=50)` 钳制，恶意调用方无法用超大 chunk 打爆 1MB 事务上限或越界崩溃服务进程。
- [x] **B-127 `Log` 无 debug 门控、无脱敏钩子，订阅 URL 与凭据会进 logcat 和本地日志文件 (g6)**
  - `common/src/main/java/com/github/kr328/clash/common/log/Log.kt:1-23`
  - 触发：`d`/`v` 不判断 `BuildConfig.DEBUG` 就直接落 logcat，整个门面也没有任何字符串脱敏能力，而上层在下载/更新订阅、报告错误时会把完整 URL（含 token）拼进消息。release 包上订阅更新失败即把带 token 的地址完整打进 logcat，同设备任意有日志读取能力的组件（厂商日志收集、用户导出的 bug report）都能拿到可直接使用的订阅凭据。
  - 修法：`d`/`v` 加 `BuildConfig.DEBUG` 门控；门面内加统一 redact（对 `http(s)://` 串保留 host、抹掉 query 与 path 中的长随机段），并要求涉及订阅地址的日志一律走该门面。
  - 关联：[用户安全文案保持高层] —— 面向用户的说明只写效果与隐私影响。

- [x] **B-128 Slice Binder 的 `onTransact` 把原始 `flags` 而非 `tFlags` 传给 `super` (g6)**
  - `common/src/main/java/com/github/kr328/clash/common/util/Parcelable.kt`（`SliceParcelableListBpBinder.onTransact`）
  - 触发：函数内已经算出要用的 `tFlags`，落到 `super.onTransact(code, data, reply, flags)` 时用的仍是入参 `flags`，本地计算的标志位（如 FLAG_ONEWAY 的调整）被丢弃。分片传输被以与预期不同的同步/异步语义转发时，`reply` 可能为空或不被写入，读侧 `createListFromParcelSlice` 读到 size 0 并返回空列表——表现为代理组偶发"节点列表为空"，且无任何异常。
  - 修法：`super.onTransact` 的第四个参数改为 `tFlags`；顺带对 `code` 做白名单校验，非分片协议的 code 一律原样交给 super。

  - 已修（保守）：新增 `trustedSignerSha256`（当前为空集 + TODO，须用 `apksigner verify --print-certs` 真值填充、不可凭空捏造），`hasPinnedSigner` 优先比对 SHA-256、SHA-1 仅作过渡回退。
- [x] **B-129 `createListFromParcelSlice` 在 transact 失败时静默返回截断列表 (g6)**
  - `common/.../util/Parcelable.kt`（`createListFromParcelSlice`）
  - 触发：`remote.transact(...)` 返回 false 或读到 `size == 0` 时，循环直接结束并把已收集到的部分当作完整结果返回，没有异常、没有日志，调用方无法区分"真的空"与"传了一半失败"。切换到有数千节点的代理组时，服务进程在分片过程中被回收或某次 transact 超限失败，UI 只显示前若干个节点，用户以为订阅内容缺失并反复更新订阅。
  - 修法：分片协议里带上总数，收齐前提前结束就抛出可识别的异常（或返回 `Result`），由上层重试一次完整查询并给出提示；对失败分片记录 offset 便于定位。

  - 已修：`installedPartnerPackages` 从全量 `getInstalledPackages` 扫描改为按 `hardcodePackages` 候选逐包查证书——候选集是小的常量、主线程全量枚举慢、单个 binder 失败不再清空整个结果。语义收窄：伴侣 = 已知包族 + 钉定证书，见 KDoc。
- [x] **B-130 Slice Binder 服务端不校验 `offset`/`chunk`，可被同签名外部调用方越界或放大 (g6)**
  - `common/.../util/Parcelable.kt`（`writeToParcelSlice` / 服务端 `onTransact` 分支）
  - 触发：服务端直接用对方 Parcel 里读出的 `offset` 与 `chunk` 去切列表，没有非负、上界与最大值校验。任何拿到该 Binder 的进程（含被 `ProxyGroup` 结果透传出去的 Binder）传入巨大的 `chunk` 或越界 `offset`，可让服务进程一次性组装超大 Parcel 触发 `TransactionTooLargeException`，或落到 `subList` 的 `IndexOutOfBoundsException` 崩掉 `:background` 进程 —— VPN 随之断连。
  - 修法：读出后 `coerceIn`：`offset` 限制在 `0..size`，`chunk` 限制在 `1..MAX_CHUNK`（沿用现有 20/50 量级作为上限），越界直接回空分片而不抛异常。

  - 已修：`Migration.PERMISSION` 改 `$packageName.permission.MIGRATE_DATA` 派生（fixed 名跨不同签名 fork 撞名 → `INSTALL_FAILED_DUPLICATE_PERMISSION`）。**回归已修**：provider 不挂 manifest 级 `android:permission`（派生命名下 meta 持 `…meta.…` 而 alpha provider 要求 `…alpha.…`，系统级签名检查会在 provider 代码运行前拦截跨 flavor 迁移），真正门禁回到 `MigrationProvider.enforceCaller()` 运行时 `checkSignatures()`。
  - 已修：`Components.configure` 改为只替换非 null 目标（部分覆盖不再清掉另一个目标），新增 `reset()` 恢复 CMFA 默认。
- [x] **B-131 `Ticker` 的 `Channel` 永不关闭，且 `catch` 吞掉 `CancellationException` (g6)**
  - `common/src/main/java/com/github/kr328/clash/common/util/Ticker.kt:1-25`
  - 触发：`Channel<Long>(RENDEZVOUS)` 在发送循环结束后没有 `close()`；发送循环外层 `catch (ignored: Exception) {}` 会连 `CancellationException` 一起吞掉。界面（如 `ConnectionsDesign` 的定时刷新）离开后取消 ticker 协程，消费方 `for (x in ticker)` 因通道从不关闭而一直挂在 `receive()`，其宿主协程无法正常结束；取消异常被吞还让父作用域收不到取消完成信号。
  - 修法：改用 `kotlinx.coroutines.channels.ticker` 或在 `produce { }` 里实现（结束时自动 close）；`catch` 至少 `if (e is CancellationException) throw e`，其余异常记日志后 `close(e)`。

  - 已修：`Ticker.ticker` 的 producer 协程 `catch (CancellationException) { throw }`（不再吞取消），`catch (Exception) { channel.close(e) }`，`finally { channel.close() }`——生产者结束/失败/取消都会释放消费者，`for (x in ticker)`/`select` 不再永久挂起。
- [x] **B-132 `PartnerApps` 的信任判定钉在 SHA-1 上，而同文件已具备 SHA-256 能力 (g6)**
  - `common/src/main/java/com/github/kr328/clash/common/constants/PartnerApps.kt`（`trustedSignerSha1` 与 `signerDigestsOf`）
  - 触发：白名单钉的是 `trustedSignerSha1 = setOf("2954…307a")`，而 `signerDigestsOf` 本身已经在算 SHA-256。判定"某伙伴应用可信"这一步只需构造出 SHA-1 摘要相同的证书即可绕过——把跨应用信任边界建立在已被证明可碰撞的哈希上，而这个判定直接决定是否向对方暴露数据与动作。
  - 修法：常量换成 SHA-256 摘要集合，比较路径统一走 `signerDigestsOf` 的 SHA-256 输出；必须兼容旧数据时可同时保留两套但只以 SHA-256 为准，`PartnerAppsTest` 同步更新。
  - 清单项：④ 安全（信任根）

  - 已修：仓库声明从根 `subprojects {}` 迁到 `dependencyResolutionManagement`（`PREFER_SETTINGS` 权威）；GitHubPackages 仓库仅在凭据存在时挂载且窄化为 `com.chloemlla.lumen` 组；无鉴权 local-maven 放前面。
  - 已修：CI 单测跑双 flavor（`testAlphaRelease` + `testMetaRelease`）；新增 `lintAlphaRelease` 质量门；Go 构建映射只留 `arm64-v8a` 平台避免产物不匹配。
- [x] **B-133 `PartnerApps` 每次判定都做两次全量 `getInstalledPackages`，且 `catch (Throwable)` 直接返回空表 (g6)**
  - `common/.../constants/PartnerApps.kt`（`installedSignerSha1s` / `installedCandidatePackages`）
  - 触发：`installedSignerSha1s` 全量枚举并对每个包解析签名，`installedCandidatePackages` 又触发一次，整个过程无缓存。装了几百个应用的设备上，一次交互要做两遍全量枚举 + 数百次证书摘要计算，主线程调用即明显卡顿；而当系统因 Binder 压力抛 `TransactionTooLargeException` 时，`catch (t: Throwable) { emptyMap() }` 使**全部伙伴应用被判为不可信**，功能整体静默失效且不可区分于"未安装"。
  - 修法：改为按候选包名逐个 `getPackageInfo`（候选集是常量、量级很小），彻底去掉全量枚举；结果按包名 + versionCode 缓存并在包变更广播时失效；捕获异常时区分"查不到该包"与"查询失败"，后者记日志并让上层可提示重试。
  - 关联：B-182（同一份枚举在建立隧道的关键路径上被算三遍）。

  - 已修：workflow 里第三方 GitHub Action 全部按 commit SHA 钉版（含 SHA256 校验注释），不再浮在 tag。
- [x] **B-134 `Migration.PERMISSION` 用固定包名而非 `${applicationId}`，与上游同装会装不上 (g6)**
  - `common/src/main/java/com/github/kr328/clash/common/constants/Migration.kt`、`common/src/main/AndroidManifest.xml`
  - 触发：常量硬编码为 `com.github.metacubex.clash.permission.MIGRATE_DATA` 并以 `protectionLevel="signature"` 声明，但自定义权限名在设备上必须全局唯一。设备上已装官方 ClashMetaForAndroid（同名权限、不同签名）时安装本 fork（或反过来），系统以 `INSTALL_FAILED_DUPLICATE_PERMISSION` 拒绝安装——用户完全装不上，而错误信息与"配置迁移"这个功能毫无关联。
  - 修法：权限名改为 `${applicationId}.permission.MIGRATE_DATA` 各自声明；跨应用迁移改为"读取方声明自己的权限 + 写入方在 `MigrationProvider` 里用 `checkSignatures` 校验调用方签名"，不依赖同名权限。`MigrationTest` 同步更新。

  - 已修：`SelectableListPreference` 新增 `confirmBeforeSet` 挂起谓词（返回 false 取消写入、保留原选择）；`OverrideSettingsDesign.allowLan` 用它——开启且无鉴权时先弹警告，OK 才写值、Cancel 撤销，`invokeOnCancellation` 关对话框防泄漏。
  - 已修：`MainDesign` 模式文案加 `TunnelState.Mode.Script -> script_mode`（不再 else 落到 rule_mode）；`OverrideSettingsDesign` 模式覆盖列表同样补 `Script` + `script_mode`。
- [x] **B-135 `Components.configure` 无条件覆写两个组件引用，传单个 null 会清掉另一个 (g6)**
  - `common/.../constants/Components.kt`（`configure(mainActivity, propertiesActivity)`）、`sdk/.../ClashRuntime.kt` 的 `install`
  - 触发：`configure` 对两个字段都是直接赋值（含 null），语义是"整体替换"；而 `ClashRuntime.install` 只在至少一个非 null 时才调用它，语义是"部分覆盖"。第三方只想替换主界面、`propertiesActivity` 留 null 时，通知点击跳转的属性页组件被清空，点通知无响应或跳到错误的默认组件。
  - 修法：`configure` 改为逐字段 `?.let { }` 只覆盖非 null 项，并提供显式的 `reset()`；或把参数改成可区分"未提供"与"显式清空"的包装类型。

  - 已修：`MetaFeatureSettingsDesign` 密钥生成按钮改 `launch { withContext(Dispatchers.Default) { Clash.genX25519KeyPair() } }`（生成期间禁用、取消恢复）；`veritySecretKeys`/`verityPublicKeys` 校验改 300ms 防抖 + `Dispatchers.Default`，主线程不再被 JNI 阻塞。
- [ ] **B-136 `alpha`/`meta` 两个 productFlavor 被下发到所有库模块，构建变体成倍膨胀 (g6)**
  - 根 `build.gradle.kts`（`subprojects` 内的 `productFlavors { alpha; meta }`）
  - 触发：flavor 只对 `:app` 有实际语义（版本名/包名/渠道差异），却被无差别应用到 `common`/`service`/`core`/`design`/`sdk`/`hideapi`，于是每个库都要构建两套变体、模块间还得靠 `missingDimensionStrategy` 相互对齐。完整构建/lint 时每个库的编译与 KSP 都跑两遍，CI 上 `lintMetaRelease lintAlphaRelease` 串行时间直接翻倍；新增模块忘了声明 flavor 维度会报"无法解析配置"，原因不在新模块本身。
  - 修法：flavor 只在 `:app` 声明，库模块用 `buildTypes` 或 `BuildConfig` 字段承载差异；确需按渠道分化的资源放到 `:app` 的 flavor 源集里。

  - 已修：复制 age 私钥到剪贴板时打 `ClipDescription.EXTRA_IS_SENSITIVE` 标记（Android 13+ 系统不再在预览里暴露明文）；复制走 `copy(label, value, sensitive=true)`。
- [x] **B-137 GitHub Packages 仓库带着可能为空的凭据被注入所有子工程 (g6)**
  - 根 `build.gradle.kts`（`GitHubPackagesProjectLumen` 的 `maven { credentials { ... } }`）
  - 触发：凭据从环境变量/属性读取，缺失时是空串而不是跳过该仓库，且该仓库对所有子工程生效（实际只有消费 lumen-crash 的模块需要）。外部贡献者或干净机器上无 token 时，任何依赖解析失败都会先在这个仓库上得到 401，真实的"依赖不存在 / 网络不可达"原因被淹没在鉴权错误里——本机 `services.gradle.org` 已知超时的环境下更难定位。
  - 修法：仅当凭据非空时才添加该仓库，并用 `content { includeGroup("com.chloemlla.lumen") }` 收窄作用域；仓库声明整体上移到 `settings.gradle.kts` 的 `dependencyResolutionManagement`。

  - 已修：`requestCloseAll` 对话框挂 `invokeOnCancellation { dialog.dismiss() }`，协程被取消（如返回键）时窗口关闭不泄漏。
- [x] **B-138 CI 只跑单变体单测、lint 只查 alpha、release 不归档 `mapping.txt` (g6，与 B-69 同源)**
  - `.github/workflows/build-debug.yaml`、`build-pre-release.yaml`、`build-release.yaml`、`.github/scripts/run-jvm-tests.py`、`run-android-lint.py`
  - 触发：单测只有 `testAlphaDebugUnitTest`（`meta` 变体的单测从不执行），PR 上 lint 只跑 `lintAlphaDebug`——只在 `meta` 变体生效的代码路径出错时 CI 全绿；release 产物不上传 `mapping.txt`，而本仓库已接入 lumen-crash 上报，线上收到混淆后的崩溃栈却无法还原。
  - 修法：单测扩到两个 flavor；PR 上至少跑一次 release 变体的 lint；release job 把 `mapping.txt`（及 aab 对应的符号文件）作为 artifact 按版本号留存。`:sdk` 的 assemble + `apiCheck` 见 B-69。
  - 清单项：② 可验证性

  - 已修：`ProxyPageAdapter` 新增 `updateMutex`，序列化 `updateAdapter`/`applyDataset`、`patchDelays`、`setKeyword` 三条「快照→后台计算→主线程回写」路径，并发 delay 轮询不再用陈旧快照覆盖新数据集。
  - 已修：ProxyDesign 增 KDoc 明确「design scope 跑在 Main.immediate、视图更新必在主线程」；adapter 只把重计算移到后台、结果回主线程提交。
- [x] **B-139 CI 中第三方 Action 混用 SHA 固定与 tag 固定 (g6)**
  - `.github/workflows/*.yaml`（`actions/checkout@v6`、`actions/cache@v5`、`gradle/actions/setup-gradle@v5` 为 tag；`setup-go`、`action-gh-release` 已按 SHA 固定）
  - 触发：同一套工作流里两种固定策略并存，而 tag 可被上游重新指向。这些 job 能接触源码与 Gradle 缓存，release 路径还能接触签名密钥——上游仓库被入侵或维护者误移动 tag 时，下一次构建即在**有签名凭据的环境里**执行被替换的代码，正是"把签名拆成独立 job"想防的那类风险。
  - 修法：所有第三方 action 统一按 commit SHA 固定并注释对应版本，交给 Renovate 升级（确认启用 `helpers:pinGitHubActionDigests`）；顺带补 `gradle/actions/wrapper-validation`。
  - 清单项：④ 供应链安全

  - 已修：`ProxyView` 全 Canvas 绘制无文本节点——新增 `buildAccessibilityText`（标题/副标题/延迟/选中态）写入 `info.text`，文本变化发 `TYPE_WINDOW_CONTENT_CHANGED`，TalkBack 不再报裸「button」。
- [x] **B-140 `allowLan` 的安全提示在值已写入之后才弹出，且只有一个确认按钮 (g6)**
  - `design/src/main/java/com/github/kr328/clash/design/OverrideSettingsDesign.kt`（`allowLan` 偏好项及其提示对话框）
  - 触发：绑定的 `KMutableProperty` 在控件回调里先把 `allowLan = true` 写进 `ConfigurationOverride`，随后才展示提示；对话框只有"确定"，没有取消/回滚路径。用户在公共 Wi-Fi 下误触后想反悔只能手动再关一次；若此刻应用被切走并回收，配置就以"允许局域网连接"留存，代理端口对同网段任意设备开放。
  - 修法：改为"先弹确认、确认后才写值"（`suspendCancellableCoroutine` 等对话框结果，取消时不写并复原控件状态），对话框提供取消按钮。
  - 关联：C-05（就地修改领域模型这一流派的直接后果）。

  - 已修：`SceneNetworkOption`/`SceneScheduleOption`/`SceneModeOption`/`FailoverSortOption` 全部带 `@StringRes labelRes`，`AutomationSettingsDesign` 用 `values().map { context.getString(it.labelRes) }`，删掉四处手写硬编码 `arrayOf(R.string…)`。
- [x] **B-141 模式选择遗漏 `TunnelState.Mode.Script`，主界面还会把它显示成"规则模式" (g6)**
  - `design/.../OverrideSettingsDesign.kt`（`mode` 的 selectableList）、`design/.../MainDesign.kt`（`modeLabel` 的 `else ->`）
  - 触发：`TunnelState.Mode` 确实有 `Script` 成员，但 override 设置的可选列表只列了 Direct/Global/Rule，`modeLabel` 的 `else` 又统一返回 `rule_mode`。配置文件本身设为 `mode: script` 时，主界面显示"规则模式"（信息错误），进 override 设置页则因当前值不在列表中而无法正确回显，一旦用户在该页确认，脚本模式被静默改成列表里的某一项。
  - 修法：`selectableList` 补上 `Script`；`modeLabel` 用穷尽 `when` 覆盖全部成员、去掉 `else`，让以后新增模式在编译期暴露。

  - 已修：sdk manifest 注释修正——`RECEIVE_SELF_BROADCASTS` 由 `:common` 声明并经 `:sdk` 依赖自动合并，host 无需重复声明。
  - 已修：`Metadata.kt` 整体删除（`GEOIP_FILE_NAME` 全仓零引用，属死代码）。
- [x] **B-142 主线程调用 `genX25519KeyPair`/`veritySecretKeys`/`toPublicKeys` 等原生密钥运算 (g6)**
  - `design/src/main/java/com/github/kr328/clash/design/MetaFeatureSettingsDesign.kt`（点击与文本变更监听里的三处 `Clash.*`）
  - 触发：这些是走 JNI 进入 Go 运行时的密钥生成/校验调用，却直接写在点击回调与 `TextWatcher` 里，运行在主线程。点"生成 X25519 密钥对"，或在 age 私钥输入框里逐字符输入（每次回调都触发一次 `veritySecretKeys`），主线程阻塞，低端设备上肉眼可见卡顿，输入较长内容时可触发 ANR。
  - 修法：这些调用移入 `withContext(Dispatchers.Default)`，期间禁用按钮并显示进度；文本校验改为防抖（输入停止若干毫秒后校验一次）。

  - 已修：`ProfilesDesign.requestUpdateAll` 的 `trySend(...).isFailure` 兜底删除——`requests` 是无界 Channel 且从不关闭，旧分支是不可达死代码，补注释说明。
- [x] **B-143 age 私钥复制到剪贴板时未标记敏感内容 (g6)**
  - `design/.../MetaFeatureSettingsDesign.kt`（`ClipData.newPlainText("age_secret_key", value)`）
  - 触发：把 age 私钥这类长期凭据放进剪贴板却没设 `ClipDescription.extras` 里的 `android.content.extra.IS_SENSITIVE`。Android 13+ 复制后系统会弹带内容预览的剪贴板提示，私钥明文直接显示在屏幕上（可被截屏/录屏/旁人看到），也不享受系统对敏感内容的特殊处理。
  - 修法：构造 `ClipData` 后设置 `description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }`；订阅地址中的 token 同类处理。

  - 已修：`AutomationSettingsDesign` 场景列表改惰性——每个场景折叠成一行摘要，首次展开才 `addSceneDetails` 构建控件；SSID 字段展开时套用当前全局开关状态。
- [x] **B-144 `ConnectionsDesign.renderConnections` 在主线程做聚合与过滤 (g6)**
  - `design/src/main/java/com/github/kr328/clash/design/ConnectionsDesign.kt`（`aggregateConnections` / `filterConnections`）
  - 触发：连接列表的分组聚合与关键字过滤是 O(n) 甚至 O(n·m) 的纯计算，却和视图更新一起跑在主线程，并且按定时刷新反复执行。BT/PT 或多设备 TUN 场景下活动连接上千，每次刷新都在主线程做完整聚合 + 过滤，帧率明显下降；带上搜索关键字后更重，输入时逐字符卡顿。
  - 修法：聚合与过滤移到 `Dispatchers.Default`，只把结果切回主线程提交给 adapter；配合 `DiffUtil` 在后台算 diff；上一轮未完成则跳过本轮（conflate）。

  - 已修：`ProxyDesign.adapter` getter 去掉 `?: error("...")`，改可空 + `getOrNull` 安全访问，ViewPager 尚未挂 adapter 的空窗不再抛异常。
- [x] **B-145 `requestCloseAll` 等对话框未在协程取消时 dismiss (g6)**
  - `design/.../ConnectionsDesign.kt`（`requestCloseAll`，同模块其他地方普遍缺这一步）
  - 触发：用 `suspendCancellableCoroutine` 等对话框结果，但没有 `invokeOnCancellation { dialog.dismiss() }`。确认框弹出时用户按返回退出 Activity（`BaseActivity` 的 `design?.cancel()` 取消协程），对话框窗口没有被 dismiss，`WindowManager` 泄漏该窗口并在 logcat 留下 `WindowLeaked`；极端时序下继续持有已销毁 Activity 的引用。
  - 修法：所有 `suspendCancellableCoroutine` 包装的对话框统一在 `invokeOnCancellation` 里 dismiss，并把这段封装成 `design` 内共用的 `suspend fun showDialog(...)`。

  - 已修：删除 `searchFilterJob: Job?` 字段，改单协程 + `CONFLATED` channel 消费者——快速击键收敛到最新值，过滤「至多轻微过期、永不重复」。
- [x] **B-146 `ProxyPageAdapter` 的 `applyDataset` 与 `patchDelays` 无互斥地并发改同一份 `states` (g6)**
  - `design/src/main/java/com/github/kr328/clash/design/adapter/ProxyPageAdapter.kt`
  - 触发：两个方法都是"主线程快照 `states` → 切 `Dispatchers.Default` 计算 → 回主线程整体写回"，彼此之间没有任何串行化，后回来的一方会用自己那份过期快照覆盖对方的结果。用户切换代理组（触发 `applyDataset`）的同时上一轮延迟测速返回（触发 `patchDelays`），界面出现"延迟数字属于上一个组"或"刚选中的节点选中态被抹掉"，再次刷新才恢复。
  - 修法：两条路径收敛到单一串行入口（`Mutex`，或往一个 `Channel` 投递意图由单协程顺序消费）；数据更新统一走 `AsyncListDiffer`/`ListAdapter` 保证提交顺序。

  - 已修：页面滚动位置从裸 `RecyclerView.tag` 改 `setTag(R.id.proxy_page_position, value)`（resource-keyed tag），不再与无 key 的 tag 碰撞。
  - 已修：`ProxyView.onMeasure` 删掉 `getTextBounds("Stub!")` 量高，改 `paint.fontMetrics`（descent-ascent）推导行高——CJK/emoji/大系统字体不再被裁。
- [x] **B-147 `ProxyDesign` 内部分视图更新未确认在主线程，同文件另一半却显式切了 (g6)**
  - `design/src/main/java/com/github/kr328/clash/design/ProxyDesign.kt`（`updateGroup` 直接改视图；`notifySelectionChanged` 走了主线程确认）
  - 触发：同一个类里两种风格并存。当前不崩是因为 `BaseActivity` 用 `Dispatchers.Main.immediate`，而 `Design` 自己的 scope 是 `Dispatchers.Unconfined`——正确性来自调用点碰巧合适而非契约。任何从 Design 自身 scope 或 IO 上下文调用 `updateGroup` 的新代码（例如 `withContext(Dispatchers.IO)` 拉完数据顺手刷 UI）立刻 `CalledFromWrongThreadException`，而这类写法完全符合现有代码风格，评审时看不出问题。
  - 修法：把 `Design` 的 scope 改成 `Dispatchers.Main.immediate`（与 A-24 一起做），并统一约定所有对外 suspend 方法"内部保证主线程"，去掉零散的 `withContext(Main)`。

  - 已修：删除 `setShadowLayer` + `cardOffset`/`shadow`（硬件加速下 `setShadowLayer` 对 `drawPath` 不生效，属无效代码），卡片保持平绘；真要阴影须 elevation/outlineProvider。
- [x] **B-148 `ProxyView` 全部文本用 Canvas 绘制，无障碍节点不提供任何文本 (g6)**
  - `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt:1-208`（`onDraw` 与 `onInitializeAccessibilityNodeInfo`）
  - 触发：节点名、类型、延迟全部 `canvas.drawText` 绘制，而 `onInitializeAccessibilityNodeInfo` 只设了 className / isClickable / isSelected，没有 `info.text` 或 `contentDescription`。开启 TalkBack 的用户在代理列表里逐项聚焦时读屏只播报"按钮"，节点名称、当前延迟、是否选中完全获取不到——整个选节点功能对视障用户不可用。
  - 修法：在 `onInitializeAccessibilityNodeInfo` 里按当前 state 拼出"名称 + 类型 + 延迟 + 选中态"设进 `info.text`，选中态/延迟变化时发 `AccessibilityEvent` 通知刷新。
  - 清单项：品味（可访问性）

  - 已修：根 `lint.xml` 集中全部 issue suppression（每条带理由），`build.gradle.kts` 的 `lintOptions` 只留 on/off 开关。
- [x] **B-149 `AutomationSettingsDesign` 在 4 处维护"枚举 `values()` + 手写文本数组"的平行数组 (g6)**
  - `design/src/main/java/com/github/kr328/clash/design/AutomationSettingsDesign.kt`（4 处 `values()` 与 `valuesText = arrayOf(...)` 成对出现）
  - 触发：可选值来自枚举 `values()`（自动跟随枚举变化），显示文本却是手写固定数组，两者靠下标一一对应且这种结构重复了 4 次，没有任何编译期约束。给枚举新增一个成员时 `values()` 长了一项而 `valuesText` 没有 → 选择列表在最后一项上 `ArrayIndexOutOfBoundsException`；若只是顺序变了则不崩，直接显示错误的选项名——用户选到的不是他看到的那一项。
  - 修法：把显示文本挂到枚举自身（成员携带 `@StringRes` 字段），列表从 `values()` 一次映射生成；4 处重复结构抽成共用的构造函数。

  - 已修：`tasks.wrapper` 幂等——重跑不再追加重复 `distributionSha256Sum`（两条不同校验和会让 Gradle 用后者而误导排查）。
- [x] **B-150 `sdk` 的 manifest 注释把 `RECEIVE_SELF_BROADCASTS` 的声明位置写错（轻微） (g6)**
  - `sdk/src/main/AndroidManifest.xml`（注释）
  - 触发：注释称宿主需自行声明该权限"（已在 :service 中）"，实际声明在 `common/src/main/AndroidManifest.xml`。第三方按注释去 `:service` 找不到，或误以为不需要声明——后者会让 `EventHub` 的接收器收不到任何广播，事件流永久静默。
  - 修法：注释改为指向 `:common` 的 manifest，并写明"通过依赖 `:sdk` 会自动合并，无需宿主重复声明"。

- [x] **B-151 `Metadata.GEOIP_FILE_NAME` 已无任何引用（轻微） (g6)**
  - `common/src/main/java/com/github/kr328/clash/common/constants/Metadata.kt`
  - 触发：全仓 grep 确认零引用，geo 数据文件名的真实来源已在别处（`MetaFeatureSettingsDesign` 相关逻辑与 core 侧），常量成了误导性的"权威定义"。后续实现自定义 geo 下载时若按它命名文件，与内核实际读取的名字不一致，功能表现为"下载成功但不生效"（与 B-76 同一类症状）。
  - 修法：直接删除；确有共享需要则把内核真正使用的文件名集中到该文件并让 core 侧引用它。

- [x] **B-152 `ProfilesDesign.requestUpdateAll` 的失败分支在 `Channel.UNLIMITED` 下不可达（轻微） (g6)**
  - `design/src/main/java/com/github/kr328/clash/design/ProfilesDesign.kt`、`Design.kt:18`
  - 触发：`requests` 是 `Channel(UNLIMITED)`，`trySend` 只在通道被关闭时才失败，而 `Design` 从不 `close()`，因此 `finishUpdateAll()` 这条兜底永远不会执行。将来若把容量改小（治理 UI 事件洪峰的常规做法），这段看似已有背压兜底的代码从未被验证过，第一次真正触发时行为未知。
  - 修法：要么明确通道语义为无界并删掉这条死分支（注释写清"投递不会失败"），要么把容量改为有界并真正测试兜底路径。

- [x] **B-153 `AutomationSettingsDesign` 为每个场景预先构造全部偏好控件（轻微） (g6)**
  - `design/.../AutomationSettingsDesign.kt`（按场景遍历构造偏好项的循环）
  - 触发：进入页面时就把所有场景的所有偏好控件全部实例化并加入布局，没有懒加载或复用。用户配了较多自动化场景时首帧延迟明显且随场景数线性增长，布局层级过深还会拖慢每次测量。
  - 修法：先把每个场景折叠为一行摘要、点开再构造其内部控件；场景数上限较高则改为 RecyclerView。优先级低于 B-149。

- [x] **B-154 `ConnectionsDesign` 用 `ViewMode.values()[tab.position]` 把枚举顺序耦合到 Tab 顺序（轻微） (g6)**
  - `design/.../ConnectionsDesign.kt`（Tab 选中回调）
  - 触发：直接以 TabLayout 的位置索引取枚举成员，二者的对应关系只存在于"恰好同序"这一隐含约定里，而布局与枚举分处两个文件。调整 Tab 顺序或在枚举中间插入成员，界面切到的视图模式与用户点的标签不一致；Tab 数多于枚举成员时直接 `ArrayIndexOutOfBoundsException`。
  - 修法：创建 Tab 时把对应的 `ViewMode` 放进 `tab.tag`，回调里从 tag 取；或用显式 `when (tab.position)` 让缺失分支在评审时可见。

- [x] **B-155 `ProxyDesign` 的 adapter 访问器用 `error()` 硬崩（轻微） (g6)**
  - `design/.../ProxyDesign.kt`（`private val adapter get() = binding.pagesView.adapter as? ProxyPageAdapter ?: error(...)`）
  - 触发：每次访问都断言 adapter 已设置且类型正确。数据先于 `setAdapter` 到达（例如订阅切换广播在页面初始化竞态中先回来）时刷新逻辑直接抛 `IllegalStateException`；因 `Design` scope 缺 `SupervisorJob`（A-24），这个异常还会连带取消同屏其他协程。
  - 修法：改为可空访问器 + 早退（`val a = ... ?: return`），把"adapter 尚未就绪"当作正常时序而非编程错误；真正的类型不匹配可保留断言。

- [x] **B-156 `ProxyDesign.searchFilterJob` 赋值后从不使用（轻微） (g6)**
  - `design/.../ProxyDesign.kt`（`searchFilterJob` 字段）
  - 触发：字段被赋新启动的 Job，但没有任何地方取消它或判断其状态——名字暗示"会取消上一次搜索过滤"，实际没有实现。搜索框连续输入时每次都启动新的过滤协程而不取消前一个，多个协程竞争写同一份 adapter 状态（与 B-146 同源）；这个字段还让后续维护者误以为已有防抖/取消机制而不去补。
  - 修法：要么真正实现（赋值前 `searchFilterJob?.cancel()` 并配输入防抖），要么删除该字段。

- [x] **B-157 `ProxyPageAdapter.patchDelays` 回到主线程后又重算一遍关键字过滤（轻微） (g6)**
  - `design/.../adapter/ProxyPageAdapter.kt`（`patchDelays` 内第二次 `filterByKeyword`）
  - 触发：后台线程已经算过过滤结果，回主线程提交时又对全量列表跑了一次 `filterByKeyword`，重复计算且两次结果可能不一致。大代理组（数千节点）带关键字过滤时，每轮延迟刷新都在主线程多做一次全量字符串匹配，叠加定时测速形成持续掉帧。
  - 修法：后台阶段一并产出过滤后的列表与 diff，主线程只做提交；若要用最新关键字则把关键字读取也放进后台阶段的输入快照，保证一次计算对应一次提交。

- [x] **B-158 页面索引存在 `RecyclerView.tag` 里，靠扩展属性隐式约定（轻微） (g6)**
  - `design/.../adapter/ProxyPageAdapter.kt`（基于 `tag` 的私有扩展属性 `RecyclerView.position`）
  - 触发：用 `View.tag` 这个全局单槽字段存业务状态。将来在同一层级引入任何使用 `tag` 的库（图片加载、埋点、测试工具）或自己在别处 `setTag`，页面索引被静默覆盖，表现为翻页后刷新到错误的代理组。
  - 修法：把页面索引放进 ViewHolder 字段或 adapter 内部的 `SparseArray`；确需挂在 View 上时用 `setTag(R.id.xxx, value)` 的带 key 形式。

- [x] **B-159 `ProxyView.onMeasure` 用 `getTextBounds("Stub!", 0, 1, ...)` 估算文本高度（轻微） (g6)**
  - `design/.../component/ProxyView.kt`（`onMeasure`）
  - 触发：以字面量 `"Stub!"` 的第一个字符测量边界来推导行高，既是遗留占位命名，也不能代表实际要绘制的内容（中文、emoji、不同字重）。用户使用超大字号或系统字体被替换后，实际绘制文本高于测量值，节点名或延迟文字被裁切；纯 ASCII 场景下看不出来。
  - 修法：用 `paint.fontMetrics`（`descent - ascent`）计算行高，与字符内容无关；需要具体字符串宽度时对真实文本 `measureText`。

- [x] **B-160 `ProxyView` 的 `setShadowLayer` + `drawPath` 在硬件加速下无效（轻微） (g6)**
  - `design/.../component/ProxyView.kt`
  - 触发：硬件加速的 Canvas 只对 `drawText` 支持 shadow layer，对 `drawPath` 不生效——这段代码的视觉意图在任何真机上都从未实现，却仍在每帧参与 paint 配置并可能带来额外绘制开销；只在关闭硬件加速时才"正确"。
  - 修法：删掉该 shadow layer，需要阴影改用 `elevation`/`outlineProvider` 或预绘制到 bitmap；确认视觉需求后再决定，不要保留无效配置。

- [x] **B-161 `MetaFeatureSettingsDesign` 留有大段注释掉的 geox 代码与空的 `configure` lambda（轻微） (g6)**
  - `design/.../MetaFeatureSettingsDesign.kt:133-135`（空 lambda）、`:274-305`（注释掉的 geox URL 配置块）
  - 触发：30 余行被注释的功能代码留在文件中间，另有一个什么都不做的 `configure { }`，两者都没有说明。维护者无法判断 geox 自定义 URL 是"未完成""已废弃"还是"临时关闭"，改动相邻代码时不知是否要一并恢复。
  - 修法：直接删除注释块（历史在 git 里），确需保留则加一行"为何暂时关闭、恢复条件"；空 `configure { }` 若无语义则去掉对应偏好项。

- [x] **B-162 lint 抑制清单在 `build.gradle.kts` 与 `lint.xml` 中重复维护（轻微） (g6)**
  - 根 `build.gradle.kts`（`lintOptions { disable(...13 项...) }`）、`lint.xml`
  - 触发：同一批 13 个 issue 被两处独立声明为忽略，`lint.xml` 另有针对 `RestrictedApi`/`UniquePermission`/`ExportedContentProvider` 的路径级忽略，两份清单没有交叉引用也没写理由。想收紧检查时只改一处不生效，误以为"改了没用"而放弃；新增模块时也不清楚该在哪一处登记。（`isAbortOnError` / `isCheckReleaseBuilds` 为 true 是好的，问题只在抑制清单重复。）
  - 修法：统一收敛到 `lint.xml`（更适合表达路径级例外），`build.gradle.kts` 只留 `abortOnError`/`checkReleaseBuilds` 等开关；每条 ignore 上方写一行理由与复查条件。

- [x] **B-163 `tasks.wrapper` 的 `doLast` 追加 `distributionSha256Sum` 非幂等（轻微） (g6)**
  - 根 `build.gradle.kts`（`tasks.wrapper { doLast { ... appendText(...) } }`）
  - 触发：用 `appendText` 往 `gradle-wrapper.properties` 追加校验和，没有先检查该 key 是否已存在。连续执行两次 `wrapper` 任务（升级 Gradle 版本时很常见）就会出现两行 `distributionSha256Sum`；若两行值不同，Gradle 以后者为准而前者成为误导，校验失败时排查方向完全错。
  - 修法：读入全部行、过滤掉已存在的 `distributionSha256Sum=` 再写回，或直接用 `Properties` 加载—设值—保存。

> 不建议修（g6 已核对源码）：`raw.githubusercontent.com` 的 MetaCubeX Maven 镜像——确实是供应链面，但代码里已有 `STOP-G` 注释记录该风险且用 `includeGroup` 收窄了作用域，上游未提供正式仓库前没有更好替代。
> 不建议修：`-dontobfuscate` 本身（`app/proguard-rules.pro:23`）——对自签名 VPN 客户端而言可读崩溃栈的价值高于混淆收益；真正该修的是它掩盖的 keep 规则缺失（B-70）。
> 不建议修：把 `Store` 迁到 DataStore——`MigrationBundle` 依赖 SharedPreferences 的原始文件布局做导入导出，迁移会破坏已有备份文件的兼容性。
> 不建议修：给 `Design` 的 ViewBinding 引用加判空——`BaseActivity` 中生命周期严格绑定 `setContentView` 与 `design?.cancel()`，binding 不会在 Design 存活期内失效，加判空只会掩盖真正的时序错误。
> 不建议修：重写 `Parcelable.kt` 的分片 Binder 方案——这是绕开 1MB transaction 上限的正确做法，只需修其中的具体 bug（B-128 ~ B-130、B-112），不要替换方案。
> 不建议修：把设置类页面整体改为 RecyclerView——这些页面打开频率低、共用一套偏好 DSL，改造会把改动扩散到所有设置屏；只有 `AutomationSettingsDesign` 的场景列表值得单独处理（B-153）。
> 不建议修：`PartnerApps` 使用 `signingCertificateHistory`——这是系统校验过的证书轮换链，支持密钥轮换是必要能力；要改的只是摘要算法（B-132），不是这个 API 的选择。

#### B-6 service · 核心与双进程边界 (g3)

- [x] **B-164 `lastModified() < 0` 的守卫恒不成立，配置文件缺失时变成开机更新风暴 (g3)**
  - `service/src/main/java/com/github/kr328/clash/service/ProfileReceiver.kt:86-99`
  - 触发：注释写的是 `// file not existed`，但 `File.lastModified()` 在文件不存在时返回 **0**、从不返回负数，所以 `if (last < 0) return` 永远不命中。缺失时 `last = 0`，`current - last` 是纪元以来的毫秒数，`interval` 被 `coerceAtLeast(0)` 压成 0 → 闹钟排在"立刻"。`importedDir/<uuid>/config.yaml` 因迁移中断或原子替换半途失败而缺失时，`rescheduleAll` 会把所有这类订阅一次性排成立刻触发，开机瞬间并发拉起 N 个更新；配合 A-14 的"失败不重排"，这批更新一旦失败就彻底停摆。
  - 修法：改成 `if (!file.isFile) return`（或 `last <= 0L`），文件确实缺失时走"标记为需要一次修复性更新"的显式路径；`rescheduleAll` 对同时到期的多个订阅加抖动。

- [x] **B-165 日志缓冲满一次即永久退订 native 日志，中继协程随后永久阻塞 (g3)**
  - `service/src/main/java/com/github/kr328/clash/service/ClashManager.kt:149-180`（native 侧 `core/.../Clash.kt:281-289`）
  - 触发：`subscribeLogcat()` 是 `Channel(32)`，native 回调按 `trySend(...).isSuccess` 决定是否继续订阅——返回 false 时 native **立即且永久**停止推送。打开日志页面后配置里有大量规则命中/内核报错，日志瞬时爆发几百条，UI 消费稍慢缓冲即满 → 退订发生 → 中继协程排空 32 条后在 `receive()` 上永久挂起，仍然持有 UI 侧的 `ILogObserver` binder。用户只能退出再进入才恢复，而问题现场已经错过。
  - 修法：native 回调侧改成"缓冲满则丢弃最旧一条并继续订阅"（不要用 false 表示背压）；中继侧按时间窗批量发送 `List<LogMessage>`；中继协程检测到 channel 关闭时主动清理 `logReceiver`。

- [x] **B-166 `patchSelector` 在 Binder 线程上同步写 Room (g3，g5 从 DAO 侧独立同报)**
  - `service/.../ClashManager.kt:80-90`、`service/.../data/SelectionDao.kt:9-13`
  - 触发：`patchSelector` 是非 suspend 的 Binder 方法，内部直接调非 suspend 的 `setSelected`/`removeSelected`——即在 `:background` 的 Binder 线程池（最多 16 个）上做 SQLite 写事务；而同文件的 `querySelections`/`removeSelections` 都是 suspend，这两个是数据层唯一的例外。用户在代理组列表里连续快速切换节点时每次点击占用一个 Binder 线程做磁盘写，同时 UI 还在轮询 `queryProxyGroup`/`queryDashboardSummary`，线程池被写事务占满时所有跨进程查询排队，界面表现为"点一下卡半秒"。
  - 修法：两个 DAO 方法改 `suspend`，`patchSelector` 也改 suspend 并在 `withContext(Dispatchers.IO)` 落库（选中态允许最终一致）；配 per-uuid `Mutex` 或 CONFLATED channel 实现"最后一次生效"。
  - 落地：`patchSelector` 先返回内核结果、Room 写入交给 `selectionWriteLock` 串行的协程；`SelectionDao` 未改为 suspend——`NodeFailoverController.kt:80` 从非挂起上下文调用它，改签名会连带重构故障转移路径，留待 C 批。

- [x] **B-167 `updateAdblock` 把规则集下载进"读取那一刻的"活动配置目录 (g3)**
  - `service/.../ClashManager.kt:128-141`
  - 触发：`store.activeProfile` 只在下载开始前读一次，而下载可能持续数十秒，结束后仍用同一个旧 uuid 去 `sendProfileChanged`。用户点"更新广告规则"后在下载中切换配置 → MRS 文件落到上一个 profile 的目录，`ConfigurationModule` 重载的却是当前活动配置 → 新配置仍然没有规则集，`isAdblockRulesReady()` 对它返回 false，用户重复点击也一样。
  - 修法：下载完成后重新读 `store.activeProfile` 并与开始时的值比较，不一致就丢弃结果并返回明确错误（或对新活动配置重下）；`sendProfileChanged` 用校验后的 uuid。

- [x] **B-168 `Clash.setAgeSecretKey` 是进程级全局态，两条路径互相踩 (g3)**
  - `service/.../ProfileProcessor.kt:37,116,146`、`service/.../clash/module/ConfigurationModule.kt:61-63`
  - 触发：age 私钥写进 native 全局变量且用完不复位。`ProfileProcessor` 为"正在下载的那个 profile"设置它，`ConfigurationModule` 在加载前为"当前活动 profile"设置它，两者在同一进程的不同协程里并发，谁最后写谁生效。活动配置 A 用 keyA、后台自动更新配置 B 用 keyB：A 因 `PROFILE_CHANGED` 触发重载，设 keyA 后 `Clash.load` 之间若插入 B 的 `setAgeSecretKey(keyB)`，A 的加密 provider 解密失败 → `LoadException` → 隧道停止，用户看到"配置加载失败"却与自己的操作无关。
  - 修法：把密钥变成 native 调用的**参数**（`load(dir, key)` / `fetchAndValid(dir, url, force, key)`），删掉全局 setter；过渡期至少用一把与 `Clash.load` 共享的互斥锁把"设密钥 + 使用"合成原子段。

- [x] **B-169 `IProfileManager` 的写操作在 suspend 函数里做递归文件 IO，未固定到 IO 调度器 (g3)**
  - `service/.../ProfileManager.kt:39-66,68-96,98-135,266-276`
  - 触发：`create`（`deleteRecursively`/`mkdirs`/`createNewFile`）与 `cloneImportedFiles`（`deleteRecursively` + 整目录 `copyRecursively`）都在 suspend 函数体里直接做阻塞 IO，没有 `withContext(Dispatchers.IO)`，而同文件的 `queryAll` 规规矩矩包了。实现 `CoroutineScope` 并不会给 suspend 函数体指定调度器——实际线程由 kaidl 生成的 Binder 桥接决定，而生成源码不在工作树内（`kaidl-compiler-patch` 以 maven 构件提供），无法审计。clone 一个带几十 MB provider 文件的订阅时，`copyRecursively` 在未知线程上阻塞数秒：落在 Binder 线程就占用线程池，落在主线程就是 ANR。`:60` 的 `@Suppress("BlockingMethodInNonBlockingContext")` 说明作者看见了告警并选择压制。
  - 修法：所有文件 IO 统一 `withContext(Dispatchers.IO)`（与 `queryAll` 一致）并删掉 `@Suppress`；在 `RemoteService` 侧固定明确的调度策略，让"Binder 方法跑在哪"成为可读的事实。

- [x] **B-170 `patch` 无条件把流量/到期字段清零，重命名本地配置会永久丢掉配额显示 (g3)**
  - `service/.../ProfileManager.kt:98-135`（`:114-117`、`:126-129`）
  - 触发：`patch` 两个分支都把 `upload/download/total/expire` 写成 0。对 Url 型 profile 无妨——`commit` → `apply` 会用 `subscription-userinfo` 填回；但对 `Type.File`，`apply` 拿不到 `subscriptionInfo`，0 就被直接写进 `Imported`。用户把订阅 clone 成本地配置（`clone` 特意保留了 `total`/`expire`），随后在属性页只改了个名字保存 → 清零 → 无 userinfo 可回填 → 配额进度条永久消失且无法恢复。
  - 修法：`patch` 只更新它的入参（name/source/interval/ageSecretKey），流量与到期字段保持原值；确需"重置用量"走已有的 `resetLocalTraffic` 显式入口。

- [x] **B-171 `FilesProvider` 全部方法在 Binder 线程上 `runBlocking`，且忽略 `CancellationSignal` (g3)**
  - `service/.../FilesProvider.kt:46-64,66-83,85-113,115-139,141-158`
  - 触发：五个 `DocumentsProvider` 回调无一例外把工作塞进 `runBlocking { }`，在调用方的 Binder 线程上同步等待；`openDocument` 收到的 `signal: CancellationSignal` 从头到尾没被使用。而 `picker.pick(path, writable = true)` 会触发 `Picker.cloneToPending`——一次 DB 插入加一次整目录 `copyRecursively`。系统文件管理器浏览到一个带大量 provider 文件的 profile 并以写模式打开其中一个文件时，复制在 Binder 线程上跑几秒，DocumentsUI 只能干等（用户取消也没用），多个 SAF 操作并发就会吃掉 Binder 线程池，连累 `IClashManager`/`StatusProvider` 一起变慢。
  - 修法：耗时工作放到自有调度器执行并用 `CancellationSignal.setOnCancelListener` 取消（`runBlocking` 换成"提交任务 + 可中断等待"）；重操作不应发生在 `openDocument` 的同步路径里（见 B-172）。

- [x] **B-172 打开文档（默认按写模式）会顺带创建一份 pending 编辑，并把流量字段清零 (g3)**
  - `service/.../FilesProvider.kt:46-64`（`:56`、`:211-214`）、`service/.../document/Picker.kt:51-53,123-147`
  - 触发：`picker.pick(path, mode?.requestWrite ?: true)`——`mode` 为 null 时**默认按可写处理**，而 `requestWrite` 只是 `contains("w")` 的粗判。可写会进入 `cloneToPending`：插一条 `Pending` 并把 `upload/download/total/expire` 全填 0，再整目录拷贝。任何持有 `MANAGE_DOCUMENTS` 的文档界面以 `"rw"`（或不传 mode）打开某个 provider 文件，该 profile 就凭空多出一条待提交的 pending 编辑，配置列表出现"未提交更改"，用户提交后配额显示就丢了。
  - 修法：`mode` 缺省视为只读（`?: false`），用 `ParcelFileDescriptor.parseMode` 的结果判断写意图；`cloneToPending` 从 `pick` 里移出改成显式的"开始编辑"操作；克隆时保留原有流量/到期字段。
  - 落地：`mode` 缺省已按只读处理并改用 `ParcelFileDescriptor.parseMode` 判写意图；`Picker.cloneToPending` 本就完整搬运 `upload/download/total/expire`（无需改动）。`cloneToPending` 仍留在 `pick` 内——SAF 没有独立的“开始编辑”回调，写模式打开时克隆是唯一可行语义。

- [x] **B-173 `renameDocument` 丢弃 `renameTo` 的返回值，失败也返回新的 documentId (g3)**
  - `service/.../FilesProvider.kt:85-113`（`:109-111`）
  - 触发：`File.renameTo` 在目标已存在、跨设备、无权限时返回 false 而不抛异常，而这里忽略返回值紧接着无条件返回改名后的 documentId。在文件管理器里把 `providers/a.yaml` 改名成已存在的 `b.yaml`：实际没改，但 SAF 客户端收到"成功"和一个指向 `b.yaml` 的 id，客户端随后基于这个 id 的操作作用在**另一个文件**上（覆盖 b 的内容），或刷新后发现文件仍叫 a.yaml，界面与磁盘不一致。
  - 修法：`if (!renameTo(target)) throw IllegalStateException(...)`，并在改名前检查目标是否已存在——`DocumentsProvider` 约定失败要抛异常而非静默返回。

- [x] **B-174 `StatusProvider` 无权限导出：任意应用可让 `:background` 做重活并触发弹窗与高优通知 (g3)**
  - `service/.../StatusProvider.kt:14-51,108-125`、`service/.../PartnerAccessResolver.kt:42-54,56-85`、`service/src/main/AndroidManifest.xml:64-69`
  - 触发：`StatusProvider` 是 `exported="true"` 且**没有任何 permission**（只加了 `tools:ignore`）。任何应用都能 `call("partnerStatus")` 进入 `PartnerAccessResolver.resolve`：对调用 UID 的每个包做 `getApplicationInfo` + `getPackageInfo(GET_SIGNING_CERTIFICATES)` + SHA-1/SHA-256 摘要，无缓存、无频率限制，全部同步跑在 Binder 线程上。① 第三方应用在循环里调它，就能持续让 `:background` 做 PackageManager 查询与证书摘要（耗电、拖慢自身 IPC）；② 把自己的 applicationId 设成尚未安装的已知伙伴包名，首次调用即让 CMFA 弹出配对 Activity + IMPORTANCE_HIGH 通知，用户视角是"莫名弹窗"。
  - 修法：给 `StatusProvider` 加签名级 permission（伙伴共用同一发布密钥，天然满足）；`resolve` 结果按 (uid, packageName, cert) 缓存并在包变更广播时失效；配对提示改为速率限制 + 仅在本应用处于前台时才 `startActivity`。
  - 清单项：④ 鉴权与越权 + ③ 规模
  - 落地：只做运行期收敛（`PartnerAccessResolver` 10s 决策缓存 + 每包 60s 提示冷却 + 前台才起 Activity），**未**给 `StatusProvider` 加 manifest 级签名权限：权限名由 `${applicationId}` 派生，加上后所有伙伴应用都得同步声明 `uses-permission` 才能继续读状态。理由已写进 `service/src/main/AndroidManifest.xml` 注释。

- [x] **B-175 仅凭包名就授予 Basic 层，与"只有钉住的证书才算伙伴"的规则自相矛盾 (g3)**
  - `service/.../PartnerAccessResolver.kt:86-105`（`:91-97`）
  - 触发：`PartnerTrust.HardcodedUnverified`（包名在 `hardcodePackages` 里但签名不匹配）在未获用户答复时被授予 `Basic` 层，而 `PartnerApps` 的 KDoc 明确写着"没有钉住的签名者就不是伙伴，无论它声称什么 applicationId"。Basic 层含 `running`/`vpnRunning`/`vpnState` 等。真实伙伴应用未安装时，攻击者以 `com.chloemlla.piliplus` 之类的 applicationId 安装自己的应用（包名先到先得，无需任何签名），即可持续读取"VPN 是否开启"这一隐私信号，用来判断用户何时在翻墙。
  - 修法：`HardcodedUnverified` 应与 `DeclaredUnverified` 同等对待（Denied + `REASON_SIGNER_UNVERIFIED`），"声称是伙伴"只用于伙伴列表 UI 展示、不参与授权；确要给未验证包一个降级层，也必须先经用户在配对页明确同意。
  - 清单项：④ 鉴权与越权 + ① 一致性

- [x] **B-176 配对通知用固定 id，第二个待授权伙伴会顶掉第一个且永不再提示 (g3)**
  - `service/.../PartnerPairingNotifier.kt:29-46,54-85`（`:84`）
  - 触发：`notifyIfAllowed(R.id.nf_partner_pairing, ...)` 对所有伙伴共用一个通知 id，后来的直接替换先前的；而 `requestPairing(packageName, sha256)` 是"每个 (包, 证书) 只提示一次"的去重，被顶掉的那个不会再次提示。设备上装了两个尚未授权的伙伴应用（各自查一次状态）→ 只剩后者的配对通知，前者的提示永久消失，用户再也没有入口授权它，该应用长期停留在 `pending_user_approval`，"跟随代理"一直不可用且无任何原因说明。
  - 修法：通知 id 按包名派生（或用带 tag 的 `notify`），并把待授权项聚合成一条摘要通知 + 列表页；`requestPairing` 的去重要以"通知仍在"为前提，被清除后允许再次提示。

- [x] **B-177 配对提示只用应用自称的 label 标识调用方，可被冒名 (g3)**
  - `service/.../PartnerPairingNotifier.kt:62-66,87-90`
  - 触发：通知正文由 `labelOf(context, packageName)`（应用自己的 `android:label`，完全由调用方控制）+ 证书 sha256 的前 8 个十六进制字符组成。用户要做的是"是否允许该应用读取 Clash 状态"这一安全决策，而界面上最显眼的信息恰是攻击者可任意设定的字符串，8 位摘要前缀普通用户无法核对。恶意应用把 label 设成"PiliPlus"甚至"Clash Meta 官方组件"，用户看到熟悉名字直接点允许 → 该应用获得 Full 层，可持续读取活动配置名、当前节点、流量总量、内核错误。
  - 修法：提示中必须显示**包名**（不可伪造）并与 label 并列，明确标出"签名未验证"；对未通过证书校验的调用方，配对页默认焦点应为拒绝，并说明"官方伙伴应用不会出现此提示"。
  - 清单项：④ 安全

- [x] **B-178 `notifyIfAllowed` 无条件检查 `POST_NOTIFICATIONS`，Android 8–12 上通知全部静默丢失 (g3)**
  - `service/.../util/Notification.kt:10-19`（调用点 `ProfileWorker.kt:166,202,218`、`PartnerPairingNotifier.kt:84`）
  - 触发：`notifyIfAllowed` 无条件把 `checkSelfPermission(POST_NOTIFICATIONS)` 当作发通知的前置条件，没有 `SDK_INT >= 33` 分支。该权限是 API 33 才引入的，而 minSdk 是 26——API 26–32 的平台不认识这个权限名，`checkSelfPermission` 返回 `PERMISSION_DENIED`，`notify` 永不被调用（而这些系统上发通知本来无需授权）。于是 Android 8–12 设备上，订阅更新的成功/失败通知与伙伴配对提示（唯一的授权入口）全部不显示。
  - 修法：`if (SDK_INT < 33 || checkSelfPermission(...) == GRANTED) notify(...)`，或改用各版本语义一致的 `NotificationManagerCompat.areNotificationsEnabled()`。
  - 关联：B-32（同一函数在 API 33+ 的另一面：前台服务通知被它挡住而不刷新）。

- [x] **B-179 `reason` 是普通字段，跨线程读写导致停止原因经常丢失 (g3)**
  - `service/.../ClashService.kt:21,52,68,103`、`service/.../TunService.kt:29,63,83,120`
  - 触发：`private var reason: String? = null` 没有 `@Volatile`；写入发生在 runtime 协程（`Dispatchers.IO`/`Default` 上的 select 分支与 catch 块），读取发生在主线程的 `onDestroy`，两者之间没有任何 happens-before 关系。配置加载失败（`LoadException`）→ runtime 协程写 `reason` 后 `stopSelf()` → 主线程 `onDestroy` 可能读到旧值 null → `sendClashStopped(null)`、`lastError = null`。用户看到"已正常停止"、日志也是 `destroyed: successfully`，真正的失败原因（伙伴应用还会通过 `partnerStatus` 读 `lastError`）就这么丢了。
  - 修法：字段加 `@Volatile`（或 `AtomicReference`）；更彻底的做法是让 runtime 通过显式结果通道把终止原因交给服务，而不是共享可变字段。

- [x] **B-180 排空循环一旦见到空队列就 `stopSelf`，之后到达的更新请求会被取消 (g3)**
  - `service/.../ProfileWorker.kt:46-62,70-99`
  - 触发：`onCreate` 的排空循环是 `while (true) { nextJob()?.join() ?: break }`，队列一空立刻 break 并 `stopSelf()`；而 `onStartCommand` 仍会把新任务 `launch` 后 `addJob`，此时已经没有消费者去 join 它，服务也正在停止，`BaseService.onDestroy` → `cancelAndJoinBlocking()` 直接把这个刚起步的下载协程掐掉。两个订阅的更新闹钟相隔十几秒（按各自 mtime 排程很容易错开）时，第二个更新在毫秒级内被取消，用户既看不到成功通知也看不到失败通知（`run` 只 catch `Exception`，`CancellationException` 不走 `failed`），因此也不会重排下一次闹钟。
  - 修法：用带缓冲的 `Channel<UUID>` 作任务队列，消费协程 `for (uuid in channel)` 持续消费，只在"通道空 + 无 in-flight + 达到空闲超时"时才 `stopSelf(startId)`（带 startId，避免停掉刚到达的启动请求）；停止前不要取消未完成的下载。

- [x] **B-181 服务作用域无 `SupervisorJob` 与异常处理器，一个失败会连坐并崩进程 (g3)**
  - `service/.../BaseService.kt:8`、`service/.../ProfileWorker.kt:83-95`
  - 触发：`BaseService` 用 `CoroutineScope(Dispatchers.Default)`——普通 `Job`、无 handler。`ProfileWorker` 的 `ACTION_PROFILE_SCHEDULE_UPDATES` 分支里 `ProfileReceiver.rescheduleAll(service)` 完全没有保护（同一块里的 `SubscriptionExpiryNotifier.checkAll` 反而包了 `runCatching`）。开机时 `rescheduleAll` 读库失败或 `AlarmManager.set` 抛（Android 12+ 每应用闹钟上限）→ 未捕获异常崩掉 `:background`；即使不崩，普通 `Job` 的失败会取消整个服务 scope，`jobs` 里排队的其它订阅更新一并静默消失。
  - 修法：`CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { ... })`；`ProfileWorker` 每个任务各自 `runCatching` 并把失败反映到通知与重排逻辑上。

- [x] **B-182 建立隧道的关键路径上做了三遍全量应用枚举（含签名证书） (g3)**
  - `service/.../TunService.kt:160-279`（`:198-202`、`:219`、`:145-158`）、`common/.../constants/PartnerApps.kt:174-179,194-208,238-250`
  - 触发：`open()` 里 `installedPartnerPackages(self)` 会 `getInstalledPackages(GET_SIGNING_CERTIFICATES)` 并对每个包的每张证书算 SHA-1；紧接着 `tunneledPartners(...)` 调 `installedCandidatePackages(self)`，内部**又**调一次 `installedPartnerPackages` 外加一次 `getInstalledApplications(GET_META_DATA)`。装了两三百个应用的设备上开 VPN，这三次枚举各自返回 MB 级数据并做数百次摘要计算，把 `Builder` 配置与 `establish()` 之间的耗时推到秒级——用户点开关后前台通知长时间停在"加载中"，每次重连/重建隧道都要重付。
  - 修法：`open()` 开头算一次 `Map<pkg, certSha1>` 快照，`partnerPackages`、`partnerDenyExclude`、`tunneledPartners` 全部复用它（作为参数传入）；结果按"包变更广播"失效缓存，供 `AppListCacheModule` 与本路径共用。
  - 关联：B-133（`PartnerApps` 自身的无缓存全量枚举）。

- [x] **B-183 `onTrimMemory` 在主线程触发 native GC (g3)**
  - `service/.../clash/ClashRuntime.kt:61-63`、`service/.../ClashService.kt:113-117`、`service/.../TunService.kt:134-138`
  - 触发：`onTrimMemory` 由系统在主线程回调，两个服务都直接 `runtime.requestGc()` → `Clash.forceGc()` → `Bridge.nativeForceGc()`，这是一次同步 JNI 调用，Go 侧的 GC + 归还内存是 stop-the-world 级操作。系统内存紧张时会连续下发多档 `TRIM_MEMORY_*`，每一档都在 `:background` 主线程上同步跑一次 native GC；主线程被占住期间该进程的前台通知更新、`onDestroy`、`onStartCommand` 全部排队——而这恰好是最容易被系统判定为无响应而杀掉的时刻。
  - 修法：`requestGc()` 内部 `launch(Dispatchers.Default)` 异步执行，并对连续调用做合并（最小间隔 5s、只在 `level >= TRIM_MEMORY_RUNNING_LOW` 时才真的执行）。

- [x] **B-184 `RemoteService` 销毁时取消了 manager 的作用域，却让 binder 和字段继续可用 (g3)**
  - `service/.../RemoteService.kt:12-47`
  - 触发：`onDestroy` 只对 `clash`/`profile` 调 `cancelAndJoinBlocking()`（实为单纯 cancel），既不置空字段也不让 binder 失效，`clash()`/`profile()` 仍用 `!!` 返回同一批对象。取消后 `ClashManager` 里三个观察者中继的 `launch` 立即变成空操作，而 `ProfileManager` 的 suspend 方法不依赖该 scope，照旧执行并写库。UI 进程在解绑与真正断开之间发出的 IPC 于是分裂：`setLogObserver` 静默失效（注册成功但永不推送），`create`/`commit` 却照常落库并广播——同一个 binder 上一部分能力活着一部分死了，调用方无从判断，日志里也没有痕迹。
  - 修法：`onDestroy` 把 `clashBinder`/`profileBinder` 置空并让后续 IPC 明确失败（`DeadObjectException` 语义或失败结果），`clash()`/`profile()` 不再用 `!!`；或让 manager 取消后进入显式的"已关闭"状态，所有入口统一先检查。

- [ ] **B-185 `IClashManager` 是把五个领域焊在一起的上帝接口 (g3)**
  - `service/.../remote/IClashManager.kt:8-51`、`service/.../ClashManager.kt:21-261`
  - 触发：一个 `@BinderInterface` 里挤了隧道状态、流量、代理组、配置与 override、providers、dashboard 摘要、广告拦截、连接列表以及三个观察者注册共 25 个方法；实现类 261 行，同时承担 native 转发、Room 落库、文件路径拼接、观察者生命周期管理。这是演进阻塞而非运行期故障：新增广告拦截功能被迫改动所有 UI 共用的这一个接口；本次审查里 A-16（无界 channel）与 B-165（日志退订）是同一段复制三遍的中继模板（`:159-176`、`:193-209`、`:232-248`），任何修法都要改三处——已经在事实上收着复制的账单。
  - 修法：按领域拆成 `IClashState`/`IProxyControl`/`IObserverRegistry`/`IAdblock` 等窄接口（`IRemoteService` 已经是"返回子接口"的形状，扩展成本很低）；三段中继模板抽成一个带缓冲策略与生命周期的泛型 helper；Room 落库从 `ClashManager` 移到用例层。
  - 清单项：品味（上帝类 / 不一致的丑）

- [x] **B-186 下载进度逐事件跨进程回调，且一次异常就永久停止上报 (g3)**
  - `service/.../ProfileProcessor.kt:195-220`（`:213`）
  - 触发：`Clash.fetchAndValid` 的每一个 `FetchStatus` 事件都直接 `cb?.updateStatus(it)` 做一次跨进程调用，没有节流也没有合并；而 catch 分支把 `cb = null`——只要 UI 侧抛过一次异常（界面正在销毁、binder 短暂不可用），此后整个下载过程都不再上报进度。导入大订阅时进度事件密集，用户旋转屏幕或短暂切后台导致一次回调异常 → 进度条从此静止在中途，用户以为卡死并强行退出，而下载其实还在 `NonCancellable` 里跑（见 A-38）。
  - 修法：服务端按时间窗（约 200ms）或进度增量（每 1%）节流后再跨进程；回调异常只跳过本次（可加连续失败计数），不要一次性永久置空；UI 重新绑定后能重新拿到进度。

- [x] **B-187 manifest 里的 intent-filter 少了 `$`，且 action 名与常量不一致，是一段死配置（轻微） (g3，g6 同报)**
  - `service/src/main/AndroidManifest.xml:98-101`（对照 `common/.../constants/Intents.kt:22` 的 `"$packageName.intent.action.REQUEST_UPDATE"`）
  - 触发：`{applicationId}.intent.action.PROFILE_REQUEST_UPDATE` 缺少 `$`，占位符不会被 manifest merger 替换；即便补上，后缀也与常量不一致。目前没有功能影响——唯一的发送方 `ProfileReceiver.pendingIntentOf` 用 `setComponent` 显式指定了组件。危害是误导：读 manifest 的人会以为这条隐式广播路径可用，一旦有人为了让外部工具触发订阅更新而依赖它，就会遇到"广播发出去但没人收"且 manifest 看起来完全正常。
  - 修法：改成 `${applicationId}.intent.action.REQUEST_UPDATE`，或干脆删掉这条 filter（连同 `<data android:scheme="uuid" />`），让"只接受显式 Intent"成为明确事实。

- [x] **B-188 `ClashRuntime` 里的 `modules` 列表既无人读取又存在并发写（轻微） (g3)**
  - `service/.../clash/ClashRuntime.kt:29,36-40`
  - 触发：`val modules = mutableListOf<Module<*>>()` 只在 `install` 的子协程里被 `add`，全文没有任何读取点；而这些子协程可以并发执行，对普通 `ArrayList` 并发 `add` 本身是未定义行为。`install` 被连续调用十余次，多个子协程在 `Dispatchers.IO` 的不同线程上同时 `add`，可能触发 `ArrayIndexOutOfBoundsException` 或元素丢失——由于列表从不被读，异常之外没有可观察后果，但它掩盖了"这里本来想做模块生命周期管理"的意图。
  - 修法：直接删掉 `modules`；如确实需要在关闭时逐个 close 模块，改成在 `install` 的调用协程里 add 而不是在子协程里。

- [x] **B-189 `PartnerAccessResolver` 里有一条不可达的授权分支（轻微） (g3)**
  - `service/.../PartnerAccessResolver.kt:62-72`
  - 触发：`signerDigestsOf` 返回 null 时，若 `trust == Verified` 就给 `Full` 并附 `REASON_NO_SIGNATURE`。但 `Verified` 本身来自 `hasPinnedSigner`，它与 `signerDigestsOf` 读的是同一份 `signingCertificatesOf`——有匹配证书必然能取到摘要，这个组合不可能出现。风险不在运行期，而在于它读起来像"没有签名也能拿 Full"的后门，会让后续维护者误判信任模型。
  - 修法：`digests == null` 一律 `Denied + REASON_NO_SIGNATURE`，删掉 `Verified` 特例。

- [x] **B-190 `isChildDocument` 用 `startsWith` 判断父子关系，前缀相同即误判（轻微） (g3)**
  - `service/.../FilesProvider.kt:180-185`（`queryRoots` 在 `:165` 声明了 `FLAG_SUPPORTS_IS_CHILD`，系统会真的依赖这个判断）
  - 触发：`documentId.startsWith(parentDocumentId)` 没有边界检查，`"/uuid/providers/abc"` 与 `"/uuid/providers/ab"` 会被判为父子。两个 provider 文件名互为前缀（如 `rule.yaml` 与 `rule.yaml.bak`）时，SAF 的树形操作（`ACTION_OPEN_DOCUMENT_TREE` 授权范围、文件管理器的复制/移动）会把不属于该子树的文档算进来。
  - 修法：`documentId == parentDocumentId || documentId.startsWith(parentDocumentId.removeSuffix("/") + "/")`。

  - 文档注记已补在 `build.gradle.kts` compileSdk 处：compileSdk 37 由 targetSdk 驱动的平台行为（edge-to-edge/预测性返回/FGS specialUse/INTERACT_ACROSS_USERS）决定，AGP 8.13 测试上限 36.1，gradle.properties 带 `android.suppressUnsupportedCompileSdk=37` 承认工具 gap；勿盲目降级。
- [x] **B-191 `serviceRunning` 的 setter 在主线程上做文件读写（轻微） (g3)**
  - `service/.../StatusProvider.kt:207-229`（调用点 `ClashService.onCreate:82`/`onDestroy:102`、`TunService.onCreate:99`/`onDestroy:118`）
  - 触发：`serviceRunning` 的 setter 连带写 `shouldStartClashOnBoot`，而后者是对 `filesDir/service_running.lock` 做 `createNewFile()`/`delete()`/`exists()`，而这个 setter 的调用点全在主线程。启动与停止的关键路径上各有一次主线程磁盘写，低端设备或 IO 繁忙时（恰好是刚拷完大配置的时刻）会拖长停止耗时，也是标准的 StrictMode `DiskWriteViolation`。
  - 修法：把"开机自启标记"从属性 setter 的副作用里拆出来，改成显式调用并投递到 IO 调度器；内存态与持久态分开，不要用一个赋值同时做两件事。

- [x] **B-192 用类型判断代替进程判断来选 `SharedPreferences` 实现（轻微） (g3)**
  - `service/.../PreferenceProvider.kt:17-30`
  - 触发：`when (context) { is BaseService, is TunService -> 直接 getSharedPreferences; else -> MultiProcessPreference }`——用"是不是这两个类"来推断"是不是在 `:background` 进程"，而这两件事只是当前恰好等价。演进期即失效：新增一个跑在 `:background` 但不继承 `BaseService` 的 Service（例如另一个 `VpnService` 变体）会走 `MultiProcessPreference`；反之若某个 `BaseService` 子类被放到 UI 进程，它会绕过跨进程 Provider 直接读写自己进程的偏好，两个进程的偏好从此静默分叉且没有任何报错。
  - 修法：按真实进程名判断（`Application.getProcessName()` 与 `PreferenceProvider` 所在进程比较），或干脆让所有调用方统一走 `MultiProcessPreference`（同进程访问会短路，开销可接受）。

- [x] **B-193 `apply` 在 pending 变化时静默 no-op，且遗留 `processingDir`（轻微） (g3)**
  - `service/.../ProfileProcessor.kt:44-101`
  - 触发：`if (PendingDao().queryByUUID(snapshot.uuid) == snapshot)` 为假时整段落库被跳过——没有日志、没有异常，也不清理已经下载好内容的 `processingDir`（`update` 与 `validate` 都会清，只有 `apply` 这条路径不清）。新建配置时点保存（开始下载）→ 返回键触发 `release(uuid)` 删掉 pending → 下载完成后 `apply` 静默丢弃。行为本身是对的（用户已取消），但 `commit()` 对调用方返回"成功"，调用方无法区分"已导入"与"被并发编辑丢弃"，同时一份完整配置内容留在 `processingDir` 直到下次操作才被覆盖。
  - 修法：分支不成立时打日志并给出可区分的结果（或让 `commit` 返回布尔/密封类），同时 `deleteRecursively()` 清理 `processingDir`。

- [x] **B-194 自动更新路径不做字段校验，与手动导入不一致（轻微） (g3)**
  - `service/.../ProfileProcessor.kt:130-148` 对比 `:275-306`
  - 触发：`apply`/`validate` 经由 `snapshotPending` 调用 `enforceFieldValid()`（scheme 白名单 http/https/content、interval ≥ 15 分钟），而自动更新的 `update` 直接从 `ImportedDao` 取行后就 `fetchProfile`，完全不校验。而 `Imported` 行并不只经由校验路径产生——`LegacyMigration` 会把旧库里的任意 `uri` 迁进来，`MigrationBundle` 也会从外部 bundle 反序列化，这类来源的 source 未经白名单就进入定时下载路径。
  - 修法：把校验提取成一个对 `source`/`interval` 生效的纯函数，`update` 在下载前同样调用；校验失败给出可见的失败通知而不是静默按原样请求。
  - 清单项：④ 输入校验 + ① 一致性

- [x] **B-195 `TunService` 未覆写 `onRevoke`，授权被撤销与用户主动停止无法区分（轻微） (g3)**
  - `service/.../TunService.kt:25-132`
  - 触发：全类没有 `onRevoke()`，父类默认实现会 `stopSelf()`，所以清理路径能走通，但 `reason` 保持为 null。用户在系统设置里断开 VPN，或另一个 VPN 应用抢占授权 → `onDestroy` 走 `sendClashStopped(null)`、`lastError = null`、日志 `destroyed: successfully`。用户在 CMFA 里看到"已正常停止"，无法得知是被系统撤销、也不知道要去重新授权；伙伴应用读到的 `lastError` 同样为空。
  - 修法：覆写 `onRevoke()`，先设置一个明确的 `reason`（如"VPN 授权已被撤销"）再 `super.onRevoke()`，让 UI 与伙伴侧能给出可操作的提示。

- [x] **B-196 隧道构建里硬编码了第三方厂商域名的代理排除表（轻微） (g3)**
  - `service/.../TunService.kt:252-262,308-315`
  - 触发：`HTTP_PROXY_BLACK_LIST = listOf("*jd.com", "100ime-iat-api.xfyun.cn", "*360buyimg.com")` 写死在伴生对象里，用户不可见、不可配置；上面的注释还在解释"历史黑名单把这些域名强制直连造成过问题"，读起来与仍然保留这份名单自相矛盾。使用系统代理时京东与讯飞语音接口被排除在 HTTP 代理之外，用户为这些域名写的分流规则在走系统代理的应用上不生效，且既没有 UI 也没有日志可查证原因。
  - 修法：把排除表移到可配置项（覆盖设置或 override 配置里），默认为空；确需保留内置项时在设置页显式列出并允许关闭，注释说明每一条的具体原因与何时可以删除。

> 不建议修（g3 已核对源码）：`FilesProvider` 的路径穿越——`document/Paths.kt` 会过滤掉 `.` 与 `..` 段，`PatternFileName` 禁止 `/`，`renameDocument` 的目标始终由 `parentFile.resolve(name)` 构造，逃不出 profile 目录；再加 canonicalPath 校验属重复防御。（g5 对此持相反意见，见 B-103。**定夺结果**：g3 对"不可利用"的判断成立，故不加 canonicalPath 校验；但"静默改写调用方输入"这一点按 g5 改成了显式拒绝，见 B-103。）
> 不建议修：`FilesProvider` 的导出——它带 `android:permission="android.permission.MANAGE_DOCUMENTS"`（signature|privileged），实际只有系统文档界面能触达。
> 不建议修：`ProfileReceiver` 的 PendingIntent requestCode 全是 0——`setUUID` 把 uuid 写进了 Intent 的 **data**（`Uri.fromParts("uuid", ...)`），`PendingIntent` 的等价性判断包含 data，因此每个 profile 的 PendingIntent 彼此独立。
> 不建议修：`PartnerAccessResolver.resolve` 取同 UID 所有包中的最高层级——共享 UID 需要相同签名，攻击者无法把自己塞进伙伴的 UID。
> 不建议修：`ProfileReceiver` 挂签名权限却要接收 `BOOT_COMPLETED`——`android:permission` 校验的是发送方，系统进程在权限检查中被视为已授予；应用自己也 `uses-permission` 了该权限，闹钟 PendingIntent 以应用身份发送同样通过。这条链是完整的。
> 不建议修：`TUN_MTU = 9000`——与上游 CMFA 一致，是 mihomo TUN 栈的既定取值。
> 不建议修：`ClashService`/`TunService` 在 `onCreate` 就 `startForeground`——这是正确做法；被互斥守卫挡掉的分支虽然没调它，但服务随即被销毁，AMS 会撤销超时计时。真正要修的是守卫本身（A-12）。
> 不建议修：观察者没有 `RemoteCallbackList` / DeathRecipient——UI 进程在界面不可见时会解绑 `RemoteService`，中继协程随之结束，观察者不会长期泄漏；真正的问题是取消后 binder 仍可用（B-184）。
> 不建议修：`ProfileProcessor` 的 `withContext(NonCancellable)` 本身——落库 + 目录原子替换确实不能被中途取消；要收缩的是它覆盖到下载阶段（A-38）。
> 不建议修：`StatusProvider` 的 `query`/`insert`/`update`/`delete` 抛 `IllegalArgumentException("Stub!")`——`ContentProvider` 契约要求这些方法存在，明确抛异常比返回 null 更清晰。



### C. 架构与品味

<!-- SECTION-C -->

- [ ] **C-02 `:sdk` 没有边界：`api(project(":service"))` 把内部类型整个泄给第三方 (g6)**
  - 根因 `sdk/build.gradle.kts:7-9`（`api(project(":service"))` + `api(":core")` + `api(":common")`），症状 `sdk/.../ClashRuntime.kt:279-321`
  - 缺陷：SDK 的**公开** API 直接收发 `:service` / `:core` 的内部类型——`IClashManager`、Room 实体、`ConfigurationOverride` 全部成了对外契约。没有可冻结的 API 面，也没有版本协商。
  - 触发：给 `Profile` 加一个字段、或调整 `ConfigurationOverride` 的字段顺序，嵌入方就是 `NoSuchMethodError` 或 Parcel 读错位——而这两种改动在本仓库属于日常。
  - 修法：`:sdk` 定义自己的模型，`api` 降为 `implementation`，开 `explicitApi` + binary-compatibility-validator 并在 CI 跑 `apiCheck`。
  - 清单项：第 9 章"抽象泄漏" + 移动端"API 长期向后兼容"

- [x] **C-03 `compileSdk = targetSdk = 37` 靠 `suppressUnsupportedCompileSdk` 压警告 (g6)**
  - `build.gradle.kts` / `app/build.gradle.kts`
  - 缺陷：编译目标压在一个当前 AGP 尚未正式支持的 SDK 上，靠抑制开关通过。
  - 后果：AGP 对新平台的行为变更（尤其前台服务、广播、存储权限这些本项目最吃紧的地方）没有官方保证；工具链升级时容易一次性爆出一批问题。
  - 修法：明确"为什么必须是 37"，或退回受支持的组合并把升级作为独立改动。
  - 清单项：Choose Boring Technology（创新代币应该花在内核上，不是构建工具链上）

- [ ] **C-04 根 `subprojects {}` 阻断了 configuration cache 与 isolated projects (g6)**
  - 根 `build.gradle.kts`
  - 缺陷：跨项目配置注入让 Gradle 无法缓存配置阶段。
  - 后果：每次构建都重跑配置，CI 时长与本地反馈速度都被拖慢——而本仓库**禁止本地构建**，CI 是唯一验证者，构建时长直接等于迭代速度。
  - 修法：改用 convention plugin（`buildSrc` / `build-logic`）替代 `subprojects {}`。

- [ ] **C-05 `design` 模块里两套数据流并存 (g6)**
  - `design/` 各 Design 实现
  - 缺陷：一套是 `Channel<Request>` 的事件流（UI → Activity → 服务），另一套是通过 `KMutableProperty` **就地修改领域模型**。同一个模块里两种相反的方向：一种把变更当消息传，一种直接改对象。
  - 后果：读代码时无法从类型推断"改了这个属性会不会落库"；B-05（`onStop` 偷偷提交编辑）正是就地修改流派的直接后果。
  - 修法：选定一种。既然跨进程本就必须走消息，就让 UI 层也只发消息，领域模型对 UI 只读。
  - 清单项：第 9 章"不一致的丑"

- [ ] **C-06 "有下发路径，没有撤销路径"是一个反复出现的模式**
  - 实例：`SceneModule` 应用场景后无法回滚（A-19）、`AccessControlActivity` 停隧道后无人负责重启（B-02）、`SecureStorage` 有 init 没有读写（B-34）、抓包有 start 没有可达的 stop（A-21）。
  - 判断：这不是四个独立 bug，是同一种思维习惯——**只写乐观路径**。第 24 章"韧性"一整组问题在本仓库的具体形态就是这个。
  - 修法：把"每个 apply 必须配一个 revoke，且 revoke 必须能在发起方消失后独立完成"作为服务侧的编码约定写进 `AGENTS.md`。

- [ ] **C-07 服务侧的自我防护程度按文件随机分布**
  - 实例：十几个 Module 里只有 3 个自建异常边界（A-18）；`Clash.kt` 8 个回调里 3 个有 `runCatching`、5 个没有（A-27）；`AppCrashedActivity` 在崩溃门后 `finish()`、`BaseActivity` 不 finish（A-08）；`Pending` 用 REPLACE、`Imported` 用 ABORT（B-62）。
  - 判断：每一处单看都像"作者当时判断过"，合起来看是**没有统一约定**。这类不一致的代价不是崩溃，是每次读代码都要重新判断一遍"这里到底防没防"。
  - 修法：把边界放到基类/框架层（`Module.execute()`、`Clash` 的回调分发、`BaseActivity`），而不是靠每个调用点自觉。

- [ ] **C-08 半成品密度偏高，功能"看起来有"但从未工作**
  - 实例：抓包自交付起恒为 0 字节（A-20）、`TrafficHistoryStore.buffer` 只写不读所以流量历史没有数据源（B-53）、`SecureStorage` 零调用方而 KDoc 宣称已加密（B-34）、节点失效转移没有后台驱动者（B-28）、`SuspendModule` 的健康检查在不可达分支（B-56）。
  - 判断：这五条的共同点是**没有任何自动化验证覆盖到"这个功能真的产生了输出"**。加上 B-57（单测只测 happy path）与 B-69（CI 不构建 `:sdk`），根因是验证面太窄。
  - 修法：给每个"会产生持久化输出"的功能加一条最小验证（文件非空、样本数 > 0），在 CI 里跑。

- [ ] **C-09 跨进程边界被当成本地方法调用**
  - 实例：A-04（丢弃 `bindService` 返回值）、A-05（`get()` 无超时）、B-08 / B-09（主线程同步 Binder）、B-12（重试包不住取代理这一步）、B-14（N 次 IPC 读 N 个属性）。
  - 判断：这是本项目**头号硬约束**（内核必须活在独立进程）的直接账单，而代码里几乎所有跨进程调用都按"本地调用一定成功、而且很快"来写。
  - 修法：把跨进程调用收敛到一层（`withClash` / `withProfile` 已经是雏形），在那一层强制超时、批量与失败语义，其余代码不许直接碰 Binder 代理。

- [ ] **C-10 降级手段是"销毁数据"**
  - 实例：`queryOverride` 解码失败返回空对象随后被写回磁盘（A-28）、`SceneStore.decode` 同形状、`Flag` 把"不可读"压成"不存在"于是上层用空配置覆盖真实数据（B-66）、场景枚举解析失败静默重置全部场景（A-29）。
  - 判断：对一个"配置写坏 = 用户永久失联"的应用，这是最贵的一类缺陷。四处独立出现说明这是默认写法，不是疏忽。
  - 修法：约定"解析失败必须让调用方知道"，禁止返回看起来正常的空值；写路径在覆盖用户数据前必须确认读路径成功。
  - 清单项：① 数据一致性（本项目的最高优先级质量属性）


- [ ] **C-01 `:sdk` 把 `:app` 的远程层整段复制了一遍**
  - `sdk/.../internal/Resource.kt` ↔ `app/.../remote/Resource.kt`、`sdk/.../internal/RemoteSession.kt` ↔ `app/.../remote/Service.kt`
  - 缺陷：两两近乎逐字相同（连 `TOGGLE_CRASHED_INTERVAL = 10s` 都一样），只差 `internal` 修饰和一个回调名。
  - 后果：本次审查里 A-04、A-05 两条缺陷都必须**改两遍**才算修完——这就是复制的账单。
  - 建议修法：抽到 `common` 或 `service` 模块一份实现，`:app` 与 `:sdk` 共用。
  - 清单项：第 9 章“不一致的丑”


### D. 本轮顺手修（无编号，修复过程中撞见）

这三项不是子代理报告里的条目，是修上面条目时在同一批文件里看到的同类问题，一并改掉，记在此处以便复核。

- [x] **D-01 `SceneStore` 的 JSON 解码没开 `coerceInputValues`**
  - `service/.../scene/SceneStore.kt`
  - 缺陷：`Json` 未配置 `coerceInputValues`，磁盘上出现 `null` 的非空字段会直接抛出，而调用方对解析失败的处理是"压成空"（与 B-66 同源）。
  - 已修：`JSON` 加 `coerceInputValues = true`，让"单个字段值不合法"退化为默认值，而不是把整份场景配置判成不可读。

- [x] **D-02 `cancelAndJoinBlocking()` 名字里的 join 是假的**
  - `service/.../util/Coroutine.kt`；调用方 `TunService.kt:128`、`RemoteService.kt:32-33`、`BaseService.kt:12`
  - 缺陷：函数名承诺"取消并等待结束"，实现只发了取消信号就返回，于是 `onDestroy` 里的后续清理与仍在跑的子协程并发——这正是 A-15（先停后启永久卡在加载中）的成因。
  - 已修：改为 `runBlocking { withTimeoutOrNull(JOIN_TIMEOUT_MS /* 2_000 */) { job.cancelAndJoin() } }`，超时打 warn 日志。2 秒是刻意的上限：`onDestroy` 跑在主线程，无界等待只会把一个 bug 换成 ANR。三处调用方的注释已同步写明"时间受限"。

- [x] **D-03 `Remote.verifyApp()` 在 IO 线程上调 `finish()` / `startActivity()`**
  - `app/.../remote/Remote.kt`
  - 缺陷：`verifyApp()` 由 `Global.launch(Dispatchers.IO)` 启动，签名校验失败分支直接在 IO 线程遍历 `createdActivities` 调 `finish()` 并 `startActivity`，两者都只在主线程安全；且集合被就地遍历，而 `finish()` 的回调会改这个集合。
  - 已修：失败分支包进 `withContext(Dispatchers.Main)`，遍历前先 `.toList()` 快照；`verifyApk()` 本体仍留在 IO 线程（它要走完整个 APK 签名链）。顺带把嵌套判断改成提前 return。


---

## 三、验证

本仓库禁止本地 Gradle 构建（`AGENTS.md`）。正确性由 GitHub Actions 判定，见提交后的 workflow 运行结果。

---

## 四、交付缺口（本文档尚未覆盖的部分）

六份分组报告均已落盘于 `.audit-reports/`（临时目录，不入库），勾选表已按报告文件内容补齐，不再存在"报告未送达"这类缺口。以下是**剩下的真实缺口**，记录在此以免后续读者误以为覆盖已完整：

- **未覆盖的范围**：`core/` 的 Go 侧（JNI 之下的 mihomo 内核本体）、`hideapi`、`kaidl-compiler-patch`、资源与本地化、AppWidget 布局，以及全部 UI 视觉与无障碍（`ProxyView` 的读屏缺口 B-148 是顺手撞见的，不代表这一面已被系统性审过）。
- **未做的验证**：本仓库禁止本地构建，因此所有"会导致运行时崩溃"的判断均为**静态阅读结论**，没有真机复现；修复后的正确性同样只由 CI 编译通过 + 后续实机使用验证。
- **未逐条复核**：除 g4 外的条目**未经协调者逐条回读源码复核**（行号对应各子代理审查时的工作树），采信前应按位置字段自行核实。

### 后续补齐方向

- Go 侧（`core/src/foss/golang`）单独派一次审查：本表里 A-27、A-34、B-107、B-108、B-111、B-29 的最终定级都依赖 JNI 胶水层与内核侧的行为（异常越过 JNI 的真实后果、fd 的 close 责任、`adblock_hits.jsonl` 的轮转）。
- UI 视觉与无障碍单独派一次：`design/` 的 Canvas 自绘组件、字号/深色模式适配、TalkBack 可用性。
- ~~两处组间冲突需要定夺~~ **已定夺**：B-103 —— g3 的"不可利用"成立（穿越段过滤后仍落在 providers 目录内），但按 g5 改成显式拒绝，理由是一致性而非漏洞；B-68 —— g5 对，g4 的"误差相消"只适用于取差值的计费路径，单值直读没有可相消的对象，已改成先解码成字节再格式化。两条的完整推演都写在各自条目里。
- **需要产品定夺（非技术问题，协调者不代拍）**：应用锁只守 `MainActivity`，`InternalControlActivity` 与 `ExternalControlActivity` 是绕过口，详见 A-37。
