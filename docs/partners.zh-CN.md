# Partner 注册表与 `partnerStatus`

> [English](partners.md)

> 模块：`:common`（`PartnerApps`）、`:service`（`StatusProvider`、`TunService`）
> 安全边界：**只读状态 + VPN 访问控制自动包含**。Partner 永远不能通过此接口启动/停止/切换 VPN（参见 `SECURITY.md`，审计合约 **F-12**）。

## 1. 什么是 Partner

`PartnerApps`（`common/src/main/java/com/github/kr328/clash/common/constants/PartnerApps.kt`）
通过以下规则识别一个包是否为 partner：

```
isPartner = 硬编码 ∪ (元数据标记存在 ∧ 签名与任意已安装的硬编码 partner 或自身匹配)
```

- **硬编码白名单**（`PartnerApps.hardcodePackages`）：PiliPlus / NexAI / Project-Lumen / Zhihu++ / Aura / CDict 的应用 ID 及其常见的 `.debug` / `.dev` / `.lite` 后缀。这是静态信任根，用于排除列表（deny-list），无论是否启用发现机制，始终有效。当某个硬编码应用 ID **实际已安装**时，运行时门禁还要求它与 CMFA 或另一个已安装的硬编码 partner 共享签名证书，从而防止在已知 partner 名下安装的伪造应用读取 `partnerStatus`。
- **元数据发现**：其他已安装的应用可以在其 `AndroidManifest.xml` 中（放在 `<application>` 内，而非 `<activity>`）声明以下内容以加入发现：

  ```xml
  <meta-data android:name="com.github.kr328.clash.partner" android:value="true" />
  ```

  该标记本身**绝不值得信任**。一个包只有在同时满足以下条件时才会被接受为已发现的 partner：
  - 与已安装在设备上的硬编码白名单中的某个包**共享签名证书**
  - 或与本应用（CMFA）自身共享签名证书（同签名者/同套件构建）

  这保证了发现机制是**严格累加的**：它只能扩大*谁*可以访问现有只读接口的范围，而不会扩大*什么*操作可以被执行。

`PartnerApps.installedPartnerPackages(context)` 返回合并后的集合（已安装的硬编码包 ∪ 已验证的已发现包），`TunService` 使用它进行 VPN 访问控制自动包含（`ServiceStore.partnerAppAutoAdapt`）。`PartnerApps.isPartnerPackage(context, packageName)` 是 `StatusProvider` 用于限制调用者的等效单包检查。

## 2. `partnerStatus`（StatusProvider）

Authority：`${applicationId}.status`，方法：`partnerStatus`。可由 CMFA 自身或已识别的 partner 调用（参见 §1）；其他调用者返回 `null`。

| 键 | 类型 | 说明 |
|---|---|---|
| `apiVersion` | int | 当前为 `2`；添加/移除字段时递增。 |
| `running` | boolean | Clash 核心是否在运行。 |
| `vpnRunning` | boolean | VPN 隧道是否活跃（保留以兼容 v1）。 |
| `vpnState` | int | v2：`0`=已断开，`1`=连接中，`2`=已连接。 |
| `partnerAppAutoAdapt` | boolean | Partner 自动包含是否启用（`piliPlusAutoAdapt` 保留为旧别名）。 |
| `name` | string? | 当前配置文件名称。 |
| `package` | string | CMFA 自身的 applicationId。 |
| `mode` | string? | 仅在 `WidgetState` 快照存在时出现；当前隧道模式。 |
| `selectedNode` | string? | 仅在 `WidgetState` 快照存在时出现；当前选中的代理/策略组。 |
| `upTotal` / `downTotal` | long | 仅在 `WidgetState` 快照存在时出现；累计字节数。 |
| `proxyDelay` | long | v2：当前选中代理的平均延迟（毫秒），未知时为 0。 |
| `aliveProxies` | int | v2：当前选中策略组中存活代理（延迟 > 0）的数量，未知时为 0。 |
| `memoryUsage` | long | v2：Clash 核心 Go 运行时已分配内存（字节）。 |
| `lastError` | string? | v2：上次停止/崩溃原因，正常运行时为 null。 |

`partnerStatus` **绝不**包含订阅 URL、`ageSecretKey`、完整配置或任何控制方法。该 Provider 上没有供 partner 调用的 `start`/`stop`/`toggle` 方法；`InternalControlActivity`（执行启动/停止操作）保持 `exported=false`（F-12）。

## 3. 仅限自身的 `widgetState`

`widgetState` 仍然仅限于 CMFA 自身（`isSelfCaller()`）调用，与 partner 识别无关，因为它支撑的是应用内/桌面小部件，而非跨应用状态查询。

## 4. 添加新的 Partner

- 当 CMFA 端到端控制发布流程时，优先将 applicationId（及其 `.debug`/`.dev` 后缀）添加到 `PartnerApps.kt` 中的相应集合。
- 当 partner 应用独立构建和签名，但与 CMFA（或硬编码 partner）共享签名证书时，改用元数据发现机制，这样每次 partner 发布时无需修改代码。
- 永远不要添加新的 exported 方法、deep link 或 provider 列，以免让 partner（或伪造元数据标记的包）请求 VPN 启动/停止、读取订阅 URL 或读取 `ageSecretKey`。