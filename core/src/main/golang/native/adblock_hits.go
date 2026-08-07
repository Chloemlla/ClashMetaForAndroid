package main

//#include "bridge.h"
import "C"

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"regexp"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"cfa/native/config"

	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

const (
	adblockHitFileName      = "adblock_hits.jsonl"
	adblockTopDomainLimit   = 10
	adblockSubscriberBuffer = 256
)

// adblockHit is the JSON-serializable per-hit record pushed to Kotlin and
// appended to the adblock_hits.jsonl log.
type adblockHit struct {
	Time     int64  `json:"time"`
	Network  string `json:"network"`
	Domain   string `json:"domain"`
	RuleType string `json:"ruleType"`
	Payload  string `json:"payload"`
	Source   string `json:"source"`
}

type adblockTopDomain struct {
	Domain string `json:"domain"`
	Count  int64  `json:"count"`
}

// adblockStats is the session aggregate snapshot served by queryAdblockStats.
type adblockStats struct {
	Total      int64              `json:"total"`
	Blocked    int64              `json:"blocked"`
	TopDomains []adblockTopDomain `json:"topDomains"`
}

// "[TCP] src(proc, uid=0) --> host:port match Domain(afd.baidu.com) using [REJECT]"
var adblockHitRe = regexp.MustCompile(`^\[(TCP|UDP)\] .+? --> (\S+) match (\w+)(?:\((.+)\))? using \[([^\]]+)\]$`)

// adblock recorder state. The recorder goroutine is the sole consumer of the
// observable log stream for hit parsing, so it must never block the tunnel:
// persistence is a tiny O_APPEND write and JNI broadcast is non-blocking.
var (
	adbHitMu       sync.Mutex
	adbTotalConns  int64
	adbBlocked     int64
	adbDomainCount = map[string]int64{}
	adbHitFile     *os.File
	adbHitWriter   *bufio.Writer
)

func init() {
	go func() {
		defer safeRecover("adblockRecorder")
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			processAdblockLog(msg.Payload)
		}
	}()
}

// isConnectionLog reports whether a log line is a per-connection metadata line
// (mihomo logMetadata), as opposed to dial-error or non-connection lines.
func isConnectionLog(payload string) bool {
	if !strings.HasPrefix(payload, "[TCP] ") && !strings.HasPrefix(payload, "[UDP] ") {
		return false
	}

	i := strings.Index(payload, " --> ")
	if i < 0 {
		return false
	}

	// "[TCP] dial DIRECT (match ...) ... --> ... error:" are failed dials.
	return !strings.HasPrefix(payload[6:i], "dial ")
}

// parseAdblockHit attributes a connection log line to an adblock rule (the
// remote cfm-adblock rule-set or one of the hardcoded Baidu rules) that was
// REJECTed. Returns nil for any other line.
func parseAdblockHit(payload string) *adblockHit {
	m := adblockHitRe.FindStringSubmatch(payload)
	if m == nil {
		return nil
	}

	if !strings.Contains(m[5], "REJECT") {
		return nil
	}

	ruleType := m[3]
	rulePayload := m[4]
	var source string
	switch {
	case ruleType == "RuleSet" && rulePayload == config.AdblockProviderName:
		source = config.AdblockProviderName
	case config.IsBaiduAdblockHit(ruleType, rulePayload):
		source = "baidu"
	default:
		return nil
	}

	return &adblockHit{
		Time:     time.Now().UnixMilli(),
		Network:  m[1],
		Domain:   hostOfRemoteAddress(m[2]),
		RuleType: ruleType,
		Payload:  rulePayload,
		Source:   source,
	}
}

func hostOfRemoteAddress(remote string) string {
	if host, _, err := net.SplitHostPort(remote); err == nil {
		return host
	}
	return remote
}

func processAdblockLog(payload string) {
	if !isConnectionLog(payload) {
		return
	}
	atomic.AddInt64(&adbTotalConns, 1)

	hit := parseAdblockHit(payload)
	if hit == nil {
		return
	}

	adbHitMu.Lock()
	adbBlocked++
	adbDomainCount[hit.Domain]++
	persistAdblockHitLocked(hit)
	adbHitMu.Unlock()

	broadcastAdblockHit(hit)
}

func persistAdblockHitLocked(hit *adblockHit) {
	if adbHitWriter == nil {
		f, err := os.OpenFile(
			constant.Path.Resolve(adblockHitFileName),
			os.O_CREATE|os.O_WRONLY|os.O_APPEND,
			0644,
		)
		if err != nil {
			return
		}
		adbHitFile = f
		adbHitWriter = bufio.NewWriter(f)
	}

	data, err := json.Marshal(hit)
	if err != nil {
		return
	}
	_, _ = adbHitWriter.Write(append(data, '\n'))
	_ = adbHitWriter.Flush()
}

//export queryAdblockStats
func queryAdblockStats() *C.char {
	adbHitMu.Lock()
	blocked := adbBlocked
	domains := make([]adblockTopDomain, 0, len(adbDomainCount))
	for domain, count := range adbDomainCount {
		domains = append(domains, adblockTopDomain{Domain: domain, Count: count})
	}
	adbHitMu.Unlock()

	sort.Slice(domains, func(i, j int) bool {
		if domains[i].Count == domains[j].Count {
			return domains[i].Domain < domains[j].Domain
		}
		return domains[i].Count > domains[j].Count
	})
	if len(domains) > adblockTopDomainLimit {
		domains = domains[:adblockTopDomainLimit]
	}

	return marshalJson(&adblockStats{
		Total:      atomic.LoadInt64(&adbTotalConns),
		Blocked:    blocked,
		TopDomains: domains,
	})
}

//export clearAdblockHits
func clearAdblockHits() {
	adbHitMu.Lock()
	defer adbHitMu.Unlock()

	adbBlocked = 0
	adbDomainCount = map[string]int64{}
	atomic.StoreInt64(&adbTotalConns, 0)

	if adbHitWriter != nil {
		_ = adbHitWriter.Flush()
	}
	if adbHitFile != nil {
		_ = adbHitFile.Close()
		adbHitFile = nil
		adbHitWriter = nil
	}
	_ = os.Remove(constant.Path.Resolve(adblockHitFileName))
}

// JNI push subscribers. Unlike subscribeDns (which subscribes the observable
// directly per subscriber), the always-on recorder owns the log stream and
// fans parsed hits out to subscribers over small buffered channels with
// non-blocking sends, so a slow UI consumer can never stall the tunnel.
type adblockSubscription struct {
	remote unsafe.Pointer
	cancel chan struct{}
	ch     chan *adblockHit
}

var (
	adbSubMu     sync.Mutex
	adbSubNextID int64
	adbSubs      = map[int64]*adblockSubscription{}
)

func broadcastAdblockHit(hit *adblockHit) {
	adbSubMu.Lock()
	subs := make([]*adblockSubscription, 0, len(adbSubs))
	for _, s := range adbSubs {
		subs = append(subs, s)
	}
	adbSubMu.Unlock()

	for _, s := range subs {
		select {
		case s.ch <- hit:
		default:
			// drop rather than backpressure the tunnel
		}
	}
}

//export subscribeAdblock
func subscribeAdblock(remote unsafe.Pointer) int64 {
	id := atomic.AddInt64(&adbSubNextID, 1)
	sub := &adblockSubscription{
		remote: remote,
		cancel: make(chan struct{}),
		ch:     make(chan *adblockHit, adblockSubscriberBuffer),
	}

	adbSubMu.Lock()
	adbSubs[id] = sub
	adbSubMu.Unlock()

	go func(id int64, sub *adblockSubscription) {
		defer safeRecover("subscribeAdblock")

		defer func() {
			adbSubMu.Lock()
			delete(adbSubs, id)
			adbSubMu.Unlock()
		}()

		released := false
		release := func() {
			if released {
				return
			}
			released = true
			C.release_object(sub.remote)
		}
		defer release()

		for {
			select {
			case <-sub.cancel:
				return
			case hit, ok := <-sub.ch:
				if !ok {
					return
				}
				if C.adblock_received(sub.remote, marshalJson(hit)) != 0 {
					return
				}
			}
		}
	}(id, sub)

	return id
}

//export unsubscribeAdblock
func unsubscribeAdblock(id int64) {
	adbSubMu.Lock()
	sub, ok := adbSubs[id]
	if ok {
		delete(adbSubs, id)
	}
	adbSubMu.Unlock()

	if ok {
		close(sub.cancel)
	}
}
