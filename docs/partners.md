# Partner Registry and `partnerStatus`

> [中文版](partners.zh-CN.md)

> Module: `:common` (`PartnerApps`), `:service` (`StatusProvider`, `TunService`)
> Security boundary: **read-only status + VPN access-control auto-include only**. Partners
> can never start/stop/toggle the VPN through this surface (see `SECURITY.md`, audit
> contract **F-12**).

## 1. Who is a partner

`PartnerApps` (`common/src/main/java/com/github/kr328/clash/common/constants/PartnerApps.kt`)
recognizes a package as a partner using:

```
isPartner = signedWith(trustedSignerSha1)
```

- **One pinned certificate** (`PartnerApps.trustedSignerSha1`): the SHA-1 fingerprint (lowercase
  hex, no separators) of the shared release signing key used by CMFA and every partner app. This
  is the entire registry. Every installed app presenting that certificate is a partner, including
  apps this build has never heard of, and no app without it is a partner — whatever applicationId
  it claims and whatever meta-data it declares. Read the fingerprint with
  `keytool -list -v -keystore <keystore>` (`SHA1:`, colons removed) or
  `apksigner verify --print-certs <apk>` (`Signer #1 certificate SHA-1 digest`). When the shared
  key is rotated, **replace** the value; a second entry would reintroduce the multi-key trust this
  gate exists to remove.
- **Hardcode allowlist** (`PartnerApps.hardcodePackages`) and the **meta-data flag** below grant
  nothing. They only mark an app as *claiming* partner status, so the partner list can show a
  wrongly signed app and explain it instead of leaving it invisible:

  ```xml
  <meta-data android:name="com.github.kr328.clash.partner" android:value="true" />
  ```

  (inside `<application>`, not `<activity>`.)

`PartnerApps.installedPartnerPackages(context)` returns the certificate-verified set and is what
`TunService` uses for VPN access-control auto-include (`ServiceStore.partnerAppAutoAdapt`), for
both the allow list and deny-list exclusion. `PartnerApps.trustOf(context, packageName)` is the
equivalent single-package classification used by `PartnerAccessResolver` to gate `StatusProvider`
callers.

A device-owner approval recorded in `PartnerGrantStore` is the second trust source: it reaches the
read-only status surface **and** puts the app into the tunnel, which is how the owner adopts an app
whose key was never pinned. It is bound to the certificate the app presented, so re-signing
invalidates it.

## 2. `partnerStatus` (StatusProvider)

Authority: `${applicationId}.status`, method `partnerStatus`. Callable by CMFA itself or
by a recognized partner (see §1); all other callers get `null`.

| Key | Type | Notes |
|---|---|---|
| `apiVersion` | int | Currently `2`; bump when fields are added/removed. |
| `running` | boolean | Clash core running. |
| `vpnRunning` | boolean | VPN tunnel active (kept for v1 backward compatibility). |
| `vpnState` | int | v2: `0`=disconnected, `1`=connecting, `2`=connected. |
| `partnerAppAutoAdapt` | boolean | Whether partner auto-include is enabled (`piliPlusAutoAdapt` kept as a legacy alias). |
| `name` | string? | Current profile name. |
| `package` | string | CMFA's own applicationId. |
| `mode` | string? | Present only once a `WidgetState` snapshot exists; current tunnel mode. |
| `selectedNode` | string? | Present only once a `WidgetState` snapshot exists; the selected proxy/group. |
| `upTotal` / `downTotal` | long | Present only once a `WidgetState` snapshot exists; cumulative byte totals. |
| `proxyDelay` | long | v2: Average delay (ms) of the currently selected proxy, or 0. |
| `aliveProxies` | int | v2: Count of alive proxies (delay > 0) in the selected proxy group, or 0. |
| `memoryUsage` | long | v2: Clash core Go runtime allocated memory (bytes). |
| `lastError` | string? | v2: Last stop/crash reason, null when healthy. |

`partnerStatus` **never** includes subscription URLs, `ageSecretKey`, full configuration,
or any control method. There is no `start`/`stop`/`toggle` method on this provider for
partners; `InternalControlActivity` (which does start/stop) stays `exported=false` (F-12).

## 3. Self-only `widgetState`

`widgetState` remains restricted to CMFA itself (`isSelfCaller()`), independent of
partner recognition, since it backs in-app/home-screen widgets rather than
cross-app status.

## 4. Adding a new partner

- Sign the partner app with the shared release key. That is the only requirement the runtime gate
  enforces: no code change, no applicationId registration, no meta-data flag.
- An app signed with a different key can still be adopted by the device owner through the pairing
  prompt, which covers both status reads and tunnel coverage for that exact certificate.
- Never add a new exported method, deep link, or provider column that would let a
  partner (or a spoofed meta-data-only package) request VPN start/stop, read the
  subscription URL, or read `ageSecretKey`.
