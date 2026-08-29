package config

import (
	"encoding/json"
	"net/url"
	"os"
	"path"
	"strings"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

const (
	// AdblockProviderName is the built-in remote adblock rule-provider name.
	// Exported so the hit recorder in package main can attribute matches.
	AdblockProviderName   = "cfm-adblock"
	adblockProviderURL    = "https://raw.githubusercontent.com/217heidai/adblockfilters/main/rules/adblockmihomo.mrs"
	adblockUpdateInterval = 28800 // seconds = 8 hours

	// adblockKey and baidupanAdblockKey are the `clash-for-android`
	// override keys controlling the built-in adblock rule-sets.
	adblockKey         = "adblock"
	baidupanAdblockKey = "baidupan-adblock"
)

// patchAdblock injects the built-in adblock rule-provider and a leading
// RULE-SET when the "clash-for-android.adblock" override flag is enabled
// (absent implies enabled). It runs before patchProviders so the provider
// path is rewritten to profileDir/providers/, and never overwrites an
// existing user-defined provider of the same name.
//
// The remote rules are NOT fetched while the config is being loaded. When the
// local MRS file is still absent, an empty inline provider is injected instead,
// so the config loads without an auto-download and the RULE-SET stays resolved
// (matching nothing). After the app downloads the file with the user's consent,
// the provider becomes an HTTP one on the next load.
//
// Performance: the provider uses the precompiled MRS rule-set format
// (adblockmihomo.mrs, ~1.65 MiB vs ~5.0 MiB for the 185k-line YAML), giving
// smaller transfer and faster load. Matching is behavior:domain (trie), not
// classical linear scanning (which would be O(n) per packet and is
// deliberately avoided). The injected rule carries no-resolve, which only
// clears helper.ResolveIP for a domain-behavior rule-set, preventing any
// unnecessary IP resolution.
func patchAdblock(cfg *config.RawConfig, profileDir string) error {
	if !adblockEnabled() {
		return nil
	}

	if _, exists := cfg.RuleProvider[AdblockProviderName]; exists {
		return nil // user already defines cfm-adblock — do not overwrite
	}

	if cfg.RuleProvider == nil {
		cfg.RuleProvider = make(map[string]map[string]any)
	}
	if AdblockProviderReady(profileDir) {
		cfg.RuleProvider[AdblockProviderName] = map[string]any{
			"type":     "http",
			"behavior": "domain",
			"format":   "mrs",
			"url":      adblockProviderURL,
			"interval": adblockUpdateInterval,
		}
	} else {
		// Placeholder until the app downloads the rules after startup; keeps the
		// RULE-SET resolvable without triggering a network fetch at load time.
		cfg.RuleProvider[AdblockProviderName] = map[string]any{
			"type":     "inline",
			"behavior": "domain",
			"payload":  []any{},
		}
	}

	cfg.Rule = append([]string{"RULE-SET," + AdblockProviderName + ",REJECT,no-resolve"}, cfg.Rule...)

	return nil
}

// patchBaiduAdblock prepends the hardcoded Baidu Netdisk ad-block list to the
// rules when the "clash-for-android.baidupan-adblock" override flag is enabled
// (absent implies disabled). Unlike patchAdblock it needs no remote provider,
// so the curated list works even without network access.
func patchBaiduAdblock(cfg *config.RawConfig, _ string) error {
	if !overrideAppFlag(baidupanAdblockKey, false) {
		return nil
	}

	rules := make([]string, 0, len(baiduAdblockRules)+len(cfg.Rule))
	rules = append(rules, baiduAdblockRules...)
	rules = append(rules, cfg.Rule...)
	cfg.Rule = rules

	return nil
}

// baiduAdblockRules is the curated Baidu Netdisk manual ad-block list. Rules
// are hardcoded (not fetched) and injected as leading rules so they are
// evaluated before any catch-all in the user profile.
var baiduAdblockRules = []string{
	"DOMAIN,afd.baidu.com,REJECT",
	"DOMAIN,afdconf.baidu.com,REJECT",
	"DOMAIN,tcbox.baidu.com,REJECT",
	"DOMAIN,datasink.dxmpay.com,REJECT",
	"DOMAIN,www.dxmpay.com,REJECT",
	"DOMAIN,app.duxiaoman.com,REJECT",
	"DOMAIN,app.duxiaomanfintech.com,REJECT",
	"DOMAIN,lf-cdn-tos.bytescm.com,REJECT",
	"DOMAIN,staticsns.cdn.bcebos.com,REJECT",
	"DOMAIN,mssdk.volces.com,REJECT",
	"DOMAIN,sdktmp.hubcloud.com.cn,REJECT",
	"DOMAIN-SUFFIX,hubcloud.com.cn,REJECT",
	"DOMAIN-SUFFIX,volces.com,REJECT",
	"DOMAIN,cpro.baidustatic.com,REJECT",
	"DOMAIN,nsclick.baidu.com,REJECT",
	"DOMAIN,feed-image.baidu.com,REJECT",
	"DOMAIN,sdk.e.qq.com,REJECT",
	"DOMAIN,als.baidu.com,REJECT",
	"DOMAIN-SUFFIX,advlion.com,REJECT",
	"DOMAIN-SUFFIX,beizi.biz,REJECT",
	"DOMAIN-SUFFIX,pangolin-sdk-toutiao.com,REJECT",
	"DOMAIN-SUFFIX,pangolin-sdk-toutiao1.com,REJECT",
	"DOMAIN-SUFFIX,pangolin-sdk-toutiao-b.com,REJECT",
	"DOMAIN-SUFFIX,pglstatp-toutiao.com,REJECT",
	"DOMAIN-SUFFIX,ubixioe.com,REJECT",
	"DOMAIN-SUFFIX,mentamob.com,REJECT",
	"DOMAIN-SUFFIX,ctobsnssdk.com,REJECT",
	"DOMAIN-SUFFIX,zhangyuyidong.cn,REJECT",
	"DOMAIN-SUFFIX,1rtb.net,REJECT",
	"DOMAIN-SUFFIX,1rtb.com,REJECT",
	"DOMAIN,mobads.baidu.com,REJECT",
	"DOMAIN-SUFFIX,adkwai.com,REJECT",
	"DOMAIN-SUFFIX,cusky.cn,REJECT",
	"DOMAIN-SUFFIX,youjingnetwork.com,REJECT",
	"IP-CIDR,112.34.111.108/32,REJECT,no-resolve",
	"DOMAIN-SUFFIX,vlion.cn,REJECT",
	"IP-CIDR,112.34.111.107/32,REJECT,no-resolve",
	"DOMAIN,mbd.baidu.com,REJECT",
	"DOMAIN,sofire.baidu.com,REJECT",
	"DOMAIN,sofire-dr.baidu.com,REJECT",
	"DOMAIN,dss0.bdstatic.com,REJECT",
	"DOMAIN,pic.rmb.bdstatic.com,REJECT",
	"DOMAIN,ecma.bdimg.com,REJECT",
}

// overrideAppFlag reads a `clash-for-android.<key>` boolean from the persist
// override, falling back to defaultValue when the key is absent or the
// document is malformed.
func overrideAppFlag(key string, defaultValue bool) bool {
	var raw map[string]any
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotPersist))).Decode(&raw); err != nil {
		log.Warnln("Apply override flag %s: %s", key, err.Error())
		return defaultValue
	}

	app, ok := raw["clash-for-android"].(map[string]any)
	if !ok {
		return defaultValue
	}

	enabled, ok := app[key].(bool)
	if !ok {
		return defaultValue
	}

	return enabled
}

// adblockEnabled reports whether the adblock switch is on. It reads the
// "clash-for-android.adblock" key from the persist override; a missing key
// or a malformed document defaults to enabled.
func adblockEnabled() bool {
	return overrideAppFlag(adblockKey, true)
}

// adblockProviderPath is the local file mihomo maps the injected provider to
// (profileDir/providers/rules/<md5 of url>); it must match patchProviders,
// otherwise the core would re-fetch the file on the next config load.
func adblockProviderPath(profileDir string) string {
	hash := utils.MakeHash([]byte(adblockProviderURL)).String()
	return path.Join(profileDir, "providers", "rules", hash)
}

// AdblockProviderReady reports whether the built-in adblock rule-set has been
// downloaded into the profile directory (i.e. the user consented and the fetch
// succeeded), so the config loader can decide between an HTTP provider and the
// offline placeholder.
func AdblockProviderReady(profileDir string) bool {
	_, err := os.Stat(adblockProviderPath(profileDir))
	return err == nil
}

// UpdateAdblockProvider downloads the built-in adblock rule-set into the
// profile's providers directory without requiring a running tunnel, so the
// rules can be pre-warmed before the first VPN start. The target path must
// match patchProviders (profileDir/providers/rules/<md5 of url>), otherwise
// the core would re-fetch the file on the next config load.
//
// When proxy is non-empty the fetch is routed through that outbound (a proxy
// group or node of the loaded config); an empty proxy falls back to the
// default tunnel routing / direct connection.
func UpdateAdblockProvider(profileDir, proxy string) error {
	u, err := url.Parse(adblockProviderURL)
	if err != nil {
		return err
	}

	_, err = fetch(u, adblockProviderPath(profileDir), proxy)
	return err
}

// Baidu rule attribution sets, built from baiduAdblockRules. The hit recorder
// in package main uses IsBaiduAdblockHit to attribute a connection log line's
// rule match (ruleType + payload) to the hardcoded Baidu ad-block list.
var (
	baiduDomainSet map[string]struct{}
	baiduSuffixSet map[string]struct{}
	baiduIPSet     map[string]struct{}
)

func init() {
	baiduDomainSet = make(map[string]struct{}, len(baiduAdblockRules))
	baiduSuffixSet = make(map[string]struct{}, len(baiduAdblockRules))
	baiduIPSet = make(map[string]struct{}, len(baiduAdblockRules))

	for _, rule := range baiduAdblockRules {
		parts := strings.Split(rule, ",")
		if len(parts) < 3 {
			continue
		}
		switch parts[0] {
		case "DOMAIN":
			baiduDomainSet[parts[1]] = struct{}{}
		case "DOMAIN-SUFFIX":
			baiduSuffixSet[parts[1]] = struct{}{}
		case "IP-CIDR":
			// The log reports IPCIDR(payload) with payload = the rule's CIDR
			// string (ipnet.String()), so key on the full CIDR.
			baiduIPSet[parts[1]] = struct{}{}
		}
	}
}

// IsBaiduAdblockHit reports whether a connection log line's rule match belongs
// to the hardcoded Baidu ad-block list. ruleType/payload mirror the values
// mihomo logs, e.g. "Domain"/"afd.baidu.com", "DomainSuffix"/"volces.com",
// "IPCIDR"/"112.34.111.108/32".
func IsBaiduAdblockHit(ruleType, payload string) bool {
	switch ruleType {
	case "Domain":
		_, ok := baiduDomainSet[payload]
		return ok
	case "DomainSuffix":
		_, ok := baiduSuffixSet[payload]
		return ok
	case "IPCIDR":
		_, ok := baiduIPSet[payload]
		return ok
	}
	return false
}
