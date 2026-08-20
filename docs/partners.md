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
isPartner = hardcode ∪ (meta-data present ∧ trustedSigner)
trustedSigner = pinned certificate digest ∨ sharesSignatureWith(any installed hardcode partner OR self)
```

- **Hardcode allowlist** (`PartnerApps.hardcodePackages`): the PiliPlus / NexAI /
  Project-Lumen / Zhihu++ / Aura / CDict applicationIds and their common `.debug` / `.dev` /
  `.lite` suffixes. This is the static trust root for deny-list exclusion and works with or
  without discovery. When a hardcoded applicationId is actually **installed**, the
  runtime gate additionally requires a trusted signer, so a spoofed install under a known
  partner name cannot read `partnerStatus`.
- **Pinned signer digests** (`PartnerApps.trustedSignerSha256`): SHA-256 digests (lowercase
  hex) of the partner release signing certificates. Each suite app ships its own signing key,
  so "same signer as CMFA or another installed hardcode partner" matches no real install —
  without pinning, the anti-spoofing gate rejects the genuine partner apps. Read a digest with
  `apksigner verify --print-certs <apk>` (`Signer #1 certificate SHA-256 digest`) and add it
  when a partner key is introduced or rotated. Zhihu++ has no published release yet, so no
  digest is pinned for it.
- **Meta-data discovery**: any other installed app may declare the following in its
  `AndroidManifest.xml` (inside `<application>`, not `<activity>`) to opt into
  discovery:

  ```xml
  <meta-data android:name="com.github.kr328.clash.partner" android:value="true" />
  ```

  The flag alone is **never trusted**. A package is only accepted as a discovered
  partner when it also presents a trusted signer: a pinned certificate digest, the same
  certificate as an installed hardcode partner, or the same certificate as this app (CMFA)
  itself. This keeps discovery strictly additive: it can only widen *who* reaches the
  existing read-only surface, never *what* a partner can do.

`PartnerApps.installedPartnerPackages(context)` returns the merged set (installed
hardcode ∪ verified discovered) and is what `TunService` uses for VPN access-control
auto-include (`ServiceStore.partnerAppAutoAdapt`). `PartnerApps.isPartnerPackage(context,
packageName)` is the equivalent single-package check used by `StatusProvider` to gate
callers.

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

- Prefer adding the applicationId (and its `.debug`/`.dev` suffixes) to the relevant
  set in `PartnerApps.kt` when CMFA controls the release process end-to-end, and pin the
  release certificate digest in `PartnerApps.trustedSignerSha256` so the runtime gate
  recognizes it.
- Use meta-data discovery instead when the partner app is built and signed
  independently but shares CMFA's signing certificate (or a hardcode partner's), so no
  code change is required on every partner release.
- Never add a new exported method, deep link, or provider column that would let a
  partner (or a spoofed meta-data-only package) request VPN start/stop, read the
  subscription URL, or read `ageSecretKey`.
