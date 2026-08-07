package config

import (
	"encoding/json"
	"strings"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

const (
	adblockProviderName   = "cfm-adblock"
	adblockProviderURL    = "https://raw.githubusercontent.com/217heidai/adblockfilters/main/rules/adblockmihomo.yaml"
	adblockUpdateInterval = 28800 // seconds = 8 hours
)

// patchAdblock injects the built-in adblock rule-provider and a leading
// RULE-SET when the "clash-for-android.adblock" override flag is enabled
// (absent implies enabled). It runs before patchProviders so the provider
// path is rewritten to profileDir/providers/, and never overwrites an
// existing user-defined provider of the same name.
func patchAdblock(cfg *config.RawConfig, _ string) error {
	if !adblockEnabled() {
		return nil
	}

	if _, exists := cfg.RuleProvider[adblockProviderName]; exists {
		return nil // user already defines cfm-adblock — do not overwrite
	}

	if cfg.RuleProvider == nil {
		cfg.RuleProvider = make(map[string]map[string]any)
	}
	cfg.RuleProvider[adblockProviderName] = map[string]any{
		"type":     "http",
		"behavior": "domain",
		"format":   "yaml",
		"url":      adblockProviderURL,
		"interval": adblockUpdateInterval,
	}

	cfg.Rule = append([]string{"RULE-SET," + adblockProviderName + ",REJECT"}, cfg.Rule...)

	return nil
}

// adblockEnabled reports whether the adblock switch is on. It reads the
// "clash-for-android.adblock" key from the persist override; a missing key
// or a malformed document defaults to enabled.
func adblockEnabled() bool {
	var raw map[string]any
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotPersist))).Decode(&raw); err != nil {
		log.Warnln("Apply adblock enabled flag: %s", err.Error())
		return true // default to enabled on parse failure
	}

	app, ok := raw["clash-for-android"].(map[string]any)
	if !ok {
		return true
	}

	enabled, ok := app["adblock"].(bool)
	if !ok {
		return true // key absent or not a bool — default enabled
	}

	return enabled
}
