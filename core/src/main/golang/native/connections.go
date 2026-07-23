package main

//#include "bridge.h"
import "C"

import (
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"cfa/native/app"

	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// connectionSnapshot is the JSON-serializable snapshot of a single tracked connection.
type connectionSnapshot struct {
	ID          string `json:"id"`
	Network     string `json:"network"`
	Type        string `json:"type"`
	Host        string `json:"host"`
	DstIP       string `json:"dstIp"`
	DstPort     string `json:"dstPort"`
	SrcIP       string `json:"srcIp"`
	SrcPort     string `json:"srcPort"`
	InboundName string `json:"inbound"`
	Rule        string `json:"rule"`
	RulePayload string `json:"rulePayload"`
	Chains      string `json:"chains"`
	Upload      int64  `json:"upload"`
	Download    int64  `json:"download"`
	StartTime   int64  `json:"start"` // unix millis
	Process     string `json:"process"`
	Package     string `json:"package"`
	UID         int32  `json:"uid"`
}

// connectionsPayload is the top-level JSON object pushed to Kotlin on every tick.
type connectionsPayload struct {
	Connections []connectionSnapshot `json:"connections"`
	Time        int64                `json:"time"` // unix millis of snapshot
}

type connectionsSubscription struct {
	remote unsafe.Pointer
	cancel chan struct{}
}

var (
	connSubMu     sync.Mutex
	connSubNextID int64
	connSubMap    = map[int64]*connectionsSubscription{}
)

// snapshotConnections reads the statistic manager and returns the current list.
func snapshotConnections() []connectionSnapshot {
	out := make([]connectionSnapshot, 0, 32)

	statistic.DefaultManager.Range(func(t statistic.Tracker) bool {
		info := t.Info()
		if info == nil || info.Metadata == nil {
			return true
		}
		m := info.Metadata

		host := m.SniffHost
		if host == "" {
			host = m.Host
		}

		processName := m.Process
		pkg := ""
		if m.Uid != 0 {
			pkg = app.QueryAppByUid(int(m.Uid))
		}
		if processName == "" {
			processName = pkg
		}
		if processName == "" && m.ProcessPath != "" {
			processName = m.ProcessPath
		}

		dstIP := ""
		if m.DstIP.IsValid() {
			dstIP = m.DstIP.String()
		}
		srcIP := ""
		if m.SrcIP.IsValid() {
			srcIP = m.SrcIP.String()
		}

		snap := connectionSnapshot{
			ID:          t.ID(),
			Network:     m.NetWork.String(),
			Type:        m.Type.String(),
			Host:        host,
			DstIP:       dstIP,
			DstPort:     fmt.Sprintf("%d", m.DstPort),
			SrcIP:       srcIP,
			SrcPort:     fmt.Sprintf("%d", m.SrcPort),
			InboundName: m.InName,
			Rule:        info.Rule,
			RulePayload: info.RulePayload,
			Chains:      strings.Join(info.Chain, " → "),
			Upload:      info.UploadTotal.Load(),
			Download:    info.DownloadTotal.Load(),
			StartTime:   info.Start.UnixMilli(),
			Process:     processName,
			Package:     pkg,
			UID:         int32(m.Uid),
		}
		out = append(out, snap)
		return true
	})

	return out
}

//export subscribeConnections
func subscribeConnections(remote unsafe.Pointer, intervalMs C.int64_t) int64 {
	id := atomic.AddInt64(&connSubNextID, 1)
	cancel := make(chan struct{})

	connSubMu.Lock()
	connSubMap[id] = &connectionsSubscription{remote: remote, cancel: cancel}
	connSubMu.Unlock()

	interval := time.Duration(intervalMs) * time.Millisecond
	if interval < 500*time.Millisecond {
		interval = 1 * time.Second
	}

	go func(id int64, remote unsafe.Pointer, cancel <-chan struct{}) {
		released := false
		release := func() {
			if released {
				return
			}
			released = true
			C.release_object(remote)
		}
		defer release()

		defer func() {
			connSubMu.Lock()
			delete(connSubMap, id)
			connSubMu.Unlock()
		}()

		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-cancel:
				log.Debugln("[APP] connections subscriber cancelled")
				return
			case <-ticker.C:
				conns := snapshotConnections()
				payload := &connectionsPayload{
					Connections: conns,
					Time:        time.Now().UnixMilli(),
				}

				// Non-zero return means the Java side is gone.
				if C.connections_received(remote, marshalJson(payload)) != 0 {
					log.Debugln("[APP] connections subscriber closed")
					return
				}
			}
		}
	}(id, remote, cancel)

	return id
}

//export unsubscribeConnections
func unsubscribeConnections(id int64) {
	connSubMu.Lock()
	sub, ok := connSubMap[id]
	if ok {
		delete(connSubMap, id)
	}
	connSubMu.Unlock()

	if ok {
		close(sub.cancel)
	}
}