package tunnel

import (
	"runtime/debug"
	"sync"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// recoverPanic keeps the :background process alive when a health-check
// goroutine panics (e.g. inside a mihomo provider). An unrecovered panic in
// any goroutine tears down the whole process and drops an active VPN tunnel.
func recoverPanic(name string) {
	if r := recover(); r != nil {
		log.Errorln("[APP] panic in %s: %v\n%s", name, r, debug.Stack())
	}
}

func HealthCheck(name string) {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return
	}

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(provider provider.ProxyProvider) {
			defer wg.Done()
			defer recoverPanic("healthCheckProvider")

			provider.HealthCheck()
		}(pr)
	}

	wg.Wait()
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		go func(group string) {
			defer recoverPanic("healthCheckAll")

			HealthCheck(group)
		}(g)
	}
}
