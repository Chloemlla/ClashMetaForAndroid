# Partner 注册表与 `partnerStatus`

> [English](partners.md)

> 模块：`:common`（`PartnerApps`）、`:service`（`StatusProvider`、`TunService`）
> 安全边界：**只读状态 + VPN 访问控制自动包含**。Partner 永远不能通过此接口启动/停止/切换 VPN（参见 `SECURITY.md`，审计合约 **F-12**）。

## 1. 什么是 Partner

`PartnerApps`（`common/src/main/java/com/github/kr328/clash/common/constants/PartnerApps.kt`）
通过以下规则识别一个包是否为 partner：

```
isPartner = 使用 trustedSignerSha1 证书签名
```

- **唯一固定证书**（`PartnerApps.trustedSignerSha1`）：CMFA 与全部伙伴应用共用的那把发布签名密钥的 SHA-1 指纹（小写十六进制、无分隔符）。这就是整个白名单：凡是出示这张证书的已安装应用都是 partner，**包括本版本从未听说过的应用**；没有出示的一律不是 partner，不论它占用哪个应用 ID、声明了什么元数据。指纹用 `keytool -list -v -keystore <keystore>`（`SHA1:` 一行，去掉冒号）或 `apksigner verify --print-certs <apk>`（`Signer #1 certificate SHA-1 digest`）读取。轮换共享密钥时请**替换**该值，不要追加：多出一条就等于把这道门禁要消除的"多密钥信任"重新引回来。
- **硬编码白名单**（`PartnerApps.hardcodePackages`）与下面的**元数据标记**都不授予任何权限，只用于标记一个应用"自称是 partner"，以便伙伴列表把签名不对的应用显示出来并解释原因，而不是让它凭空消失：

  ```xml
  <meta-data android:name="com.github.kr328.clash.partner" android:value="true" />
  ```

  （放在 `<application>` 内，而非 `<activity>`。）

`PartnerApps.installedPartnerPackages(context)` 返回通过证书校验的集合，`TunService` 用它做 VPN 访问控制自动包含（`ServiceStore.partnerAppAutoAdapt`）——允许列表与排除列表（deny-list）都用同一个集合。`PartnerApps.trustOf(context, packageName)` 是 `PartnerAccessResolver` 用于限制 `StatusProvider` 调用者的等效单包判定。

设备拥有者在配对弹窗里的授权是第二个信任来源：它既开放只读状态接口，**也**把该应用送进隧道，用于收养密钥没被固定的应用。该授权绑定当时出示的证书，重新签名即失效。

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

- 用共享发布密钥给该 partner 应用签名。这是运行时门禁**唯一**强制的条件：不用改代码、不用登记 applicationId、也不用声明元数据标记。
- 用别的密钥签名的应用仍可由设备拥有者通过配对弹窗收养，该授权对那张证书同时开放状态读取与隧道接管。
- 永远不要添加新的 exported 方法、deep link 或 provider 列，以免让 partner（或伪造元数据标记的包）请求 VPN 启动/停止、读取订阅 URL 或读取 `ageSecretKey`。