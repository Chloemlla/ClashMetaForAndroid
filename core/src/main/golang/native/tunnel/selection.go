package tunnel

import (
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/tunnel"
)

// QueryProxyGroupNow returns only the currently selected proxy name for a group.
// Unlike QueryProxyGroup it does not materialize, sort, or regex-annotate the full member list.
func QueryProxyGroupNow(name string) string {
	p := tunnel.Proxies()[name]
	if p == nil {
		return ""
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return ""
	}

	return g.Now()
}

// HasProviders reports whether any non-compatible rule/proxy provider is loaded.
// Used by the main dashboard which only needs a boolean, not the full provider DTO list.
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
