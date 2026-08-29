# Partner 注册表与 `partnerStatus`

> [English](partners.md)

> 模块：`:common`（`PartnerApps`）、`:service`（`StatusProvider`、`TunService`）
> 安全边界：**只读状态 + VPN 访问控制自动包含**。Partner 永远不能通过此接口启动/停止/切换 VPN（参见 `SECURITY.md`，审计合约 **F-12**）。

## 1. 什么是 Partner

`PartnerApps`（`common/src/main/java/com/github/kr328/clash/common/constants/PartnerApps.kt`）
通过以下规则识别一个包是否为 partner：

```
isPartner = (硬编码 ∪ 元数据标记存在) ∧ 使用 trustedSignerSha1 证书签名
```

- **唯一固定证书**（`PartnerApps.trustedSignerSha1`）：CMFA 与全部伙伴应用共用的那把发布签名密钥的 SHA-1 指纹（小写十六进制、无分隔符）。这是**全部**信任根。没有出示这张证书的应用就不是 partner——不论它占用哪个应用 ID、声明了什么元数据、设备拥有者在伙伴列表里回答过什么。指纹用 `keytool -list -v -keystore <keystore>`（`SHA1:` 一行，去掉冒号）或 `apksigner verify --print-certs <apk>`（`Signer #1 certificate SHA-1 digest`）读取。轮换共享密钥时请**替换**该值，不要追加：多出一条就等于把这道门禁要消除的"多密钥信任"重新引回来。
- **硬编码白名单**（`PartnerApps.hardcodePackages`）：PiliPlus / NexAI / Project-Lumen / Zhihu++ / Aura / CDict 的应用 ID 及其常见的 `.debug` / `.dev` / `.lite` 后缀。它只负责把这些包**提名**去做证书校验，本身不授予任何权限——因此在已知 partner 名下安装的伪造应用既读不到 `partnerStatus`，也拿不到隧道接管。
- **元数据发现**：其他已安装的应用可以在其 `AndroidManifest.xml` 中（放在 `<application>` 内，而非 `<activity>`）声明以下内容以加入发现：

  ```xml
  <meta-data android:name="com.github.kr328.clash.partner" android:value="true" />
  ```

  该标记本身**绝不值得信任**，同样只是把这个包提名去做同一道证书校验。这保证了发现机制是**严格累加的**：它只能扩大*谁*可以访问现有只读接口的范围，而不会扩大*什么*操作可以被执行。

`PartnerApps.installedPartnerPackages(context)` 返回通过证书校验的集合，`TunService` 用它做 VPN 访问控制自动包含（`ServiceStore.partnerAppAutoAdapt`）——允许列表与排除列表（deny-list）都用同一个集合。`PartnerApps.isPartnerPackage(context, packageName)` 是 `StatusProvider` 用于限制调用者的等效单包检查。

设备拥有者在 `PartnerGrantStore` 里记录的授权只作用于只读状态接口，**不会**把应用放进隧道：流量接管完全由证书决定。

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

- 用共享发布密钥给该 partner 应用签名。这是运行时门禁**唯一**强制的条件，而且每次 partner 发版都不需要改代码。
- 把 applicationId（及其 `.debug`/`.dev` 后缀）加入 `PartnerApps.kt` 中的相应集合，或让该应用声明元数据标记。两条路都只是把它提名去做证书校验。
- 永远不要添加新的 exported 方法、deep link 或 provider 列，以免让 partner（或伪造元数据标记的包）请求 VPN 启动/停止、读取订阅 URL 或读取 `ageSecretKey`。