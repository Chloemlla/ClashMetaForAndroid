package tunnel

import (
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

func CloseAllConnections() {
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()
		return true
	})
}

func CloseConnection(id string) {
	if id == "" {
		return
	}
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		if c.ID() == id {
			_ = c.Close()
			return false
		}
		return true
	})
}

func closeMatch(filter func(conn C.Connection) bool) {
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		if filter(c) {
			_ = c.Close()
		}
		return true
	})
}

func closeConnByGroup(name string) {
	closeMatch(func(conn C.Connection) bool {
		for _, c := range conn.Chains() {
			if c == name {
				return true
			}
		}

		return false
	})
}
