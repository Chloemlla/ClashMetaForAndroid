package tunnel

import (
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/tunnel"
)

// QueryProxyGroupNow returns only the currently selected proxy name for a group.
// Unlike QueryProxyGroup it does not materialize, sort, or regex-annotate the full member list.
func QueryProxyGroupNow(name string) string {
	g := resolveProxyGroup(name)
	if g == nil {
		return ""
	}
	return g.Now()
}

// QueryProxyDelays returns name→last-delay for members of a group without title/subtitle/sort work.
// Used for intermediate URL-test UI refreshes.
func QueryProxyDelays(name string) map[string]int {
	g := resolveProxyGroup(name)
	if g == nil {
		return map[string]int{}
	}

	proxies := g.Proxies()
	result := make(map[string]int, len(proxies))
	for _, p := range proxies {
		testURL := "https://www.gstatic.com/generate_204"
		for k := range p.ExtraDelayHistories() {
			if len(k) > 0 {
				testURL = k
				break
			}
		}
		result[p.Name()] = int(p.LastDelayForTestUrl(testURL))
	}
	return result
}

// HasProviders reports whether any non-compatible rule/proxy provider is loaded.
func HasProviders() bool {
	for _, rule := range tunnel.RuleProviders() {
		if rule.VehicleType() != provider.Compatible {
			return true
		}
	}
	for _, proxy := range tunnel.Providers() {
		if proxy.VehicleType() != provider.Compatible {
			return true
		}
	}
	return false
}

// DashboardSummary is a compact main-screen snapshot.
type DashboardSummary struct {
	Mode         string `json:"mode"`
	HasProviders bool   `json:"hasProviders"`
	SelectedNow  string `json:"selectedNow"`
}

// QueryDashboardSummary returns mode, provider presence, and the selected node for one preferred group.
// preferred may be empty; excludeNotSelectable matches QueryProxyGroupNames.
func QueryDashboardSummary(preferred string, excludeNotSelectable bool) *DashboardSummary {
	summary := &DashboardSummary{
		Mode:         QueryMode(),
		HasProviders: HasProviders(),
	}

	names := QueryProxyGroupNames(excludeNotSelectable)
	if len(names) == 0 {
		return summary
	}

	groupName := names[0]
	if preferred != "" {
		for _, n := range names {
			if n == preferred {
				groupName = preferred
				break
			}
		}
	}
	summary.SelectedNow = QueryProxyGroupNow(groupName)
	return summary
}

func resolveProxyGroup(name string) outboundgroup.ProxyGroup {
	p := tunnel.Proxies()[name]
	if p == nil {
		return nil
	}
	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return nil
	}
	return g
}
