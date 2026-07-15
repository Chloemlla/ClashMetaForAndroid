# ClashMeta Runtime SDK（方向 B）嵌入指南

> 模块：`:sdk`（`com.github.kr328.clash.sdk`）  
> 依赖：`api` → `:service` / `:core` / `:common`  
> 安全边界：**同 App 内嵌**；不恢复第三方远程启停 VPN。

## 1. Gradle

```kotlin
// settings.gradle.kts
include(":sdk") // already in Chloemlla tree

// host app
dependencies {
    implementation(project(":sdk"))
    // or a published AAR of :sdk that pulls service/core/common
}
```

Host `minSdk` ≥ 21。需与 CMFA 相同地声明 VPN / 前台服务等权限（可由合并后的 `:service` manifest 提供一部分；以 AGP merge 结果为准）。

## 2. Application 初始化

```kotlin
class HostApp : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Global.init is also invoked by ClashRuntime.install
    }

    override fun onCreate() {
        super.onCreate()
        if (packageName != currentProcessName) return // only main process

        ClashRuntime.install(
            this,
            ClashRuntimeConfig(
                mainActivity = ComponentName(this, MainActivity::class.java),
                propertiesActivity = ComponentName(this, ProfileActivity::class.java),
                enableVpnByDefault = true,
            ),
        )
        extractGeoAssets() // see §4
    }
}
```

## 3. UI 生命周期

```kotlin
// when host UI becomes visible
ClashRuntime.bind()

// when host UI goes background (optional but recommended)
ClashRuntime.unbind()
```

观察事件：

```kotlin
lifecycleScope.launch {
    ClashRuntime.events.collect { event ->
        when (event) {
            ClashRuntimeEvent.Started -> ...
            is ClashRuntimeEvent.Stopped -> ...
            ClashRuntimeEvent.ProfileLoaded -> ...
            else -> Unit
        }
    }
}
```

## 4. Geo 资产

内核从 `context.filesDir/clash/` 读取 `geoip.metadb`、`geosite.dat`、`ASN.mmdb`、`BundleMRS.7z` 等。  
CMFA 的 `app` 模块会在首次启动从 assets 解压；**SDK 宿主必须自行打包并解压**同等文件（可从 CMFA release assets 复制）。

## 5. 配置与启动

```kotlin
// import subscription
val uuid = ClashRuntime.importUrlProfile("MySub", "https://example.com/sub.yaml")
ClashRuntime.setActive(uuid)

// start VPN (may return prepare Intent)
val prepare = ClashRuntime.start(activity)
if (prepare != null) {
    vpnLauncher.launch(prepare) // then ClashRuntime.start(activity) again
}

// proxies
val groups = ClashRuntime.queryProxyGroupNames()
val group = ClashRuntime.queryProxyGroup(groups.first())
ClashRuntime.selectProxy(group.name, group.now)
ClashRuntime.healthCheck(group.name)

// stop
ClashRuntime.stop(activity)
```

高级场景可直接：

```kotlin
ClashRuntime.withClash { queryOverride(Clash.OverrideSlot.Persist) }
ClashRuntime.withProfile { queryAll() }
```

## 6. 进程模型

| 进程 | 职责 |
|------|------|
| 主进程 | `ClashRuntime.install` / `bind` / UI / Binder 客户端 |
| `:background` | `RemoteService`、Profile DB、部分 Provider（由 service 清单定义） |

不要在 background 进程再次 `install` + UI bind。

## 7. 安全与非目标

- **不做**跨 App `START_CLASH` / exported 遥控（审计 F-12）。
- 通知点击跳转依赖 `Components.configure`；不配置则默认 CMFA Activity 类名（宿主包名下可能不存在 → 请务必配置）。
- 签名 / `applicationId` 决定 Provider authority 与自广播权限；换包名即独立数据区。

## 8. 与 stock `app` 的关系

| 能力 | stock `app` | Runtime SDK |
|------|-------------|-------------|
| UI | `design` + Activities | 宿主自研 |
| Remote bind | `Remote` / `withClash` | `ClashRuntime` |
| VPN 启停 | `startClashService`（读 `UiStore.enableVpn`） | `setVpnEnabled` + `start` |
| 组件回跳 | 写死 Main/Properties | `Components.configure` |

后续可将 `app` 内部实现改为委托 `:sdk`，避免双份 bind 逻辑（非本 PR 强制）。

## 9. 验证

本仓库约定：**不在本机跑 Gradle**；合并后由 GitHub Actions 编译 `:sdk` 与现有 app 变体。