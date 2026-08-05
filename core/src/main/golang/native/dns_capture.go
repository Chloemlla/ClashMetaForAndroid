package main

//#include "bridge.h"
import "C"

import (
	"regexp"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/metacubex/mihomo/log"
)

// dnsEvent is the JSON-serializable DNS capture event pushed to Java.
type dnsEvent struct {
	Type      string `json:"type"`
	Domain    string `json:"domain,omitempty"`
	QType     string `json:"qtype,omitempty"`
	Server    string `json:"server,omitempty"`
	Result    string `json:"result,omitempty"`
	ExpireAt  string `json:"expireAt,omitempty"`
	Timestamp int64  `json:"timestamp"`
}

// DNS log message regex patterns.
// "resolve <domain> <qtype> from <server>"
var dnsQueryRe = regexp.MustCompile(`^\[DNS\] resolve (\S+) (\S+) from (\S+)$`)

// "<domain> --> <result> from <server>"
var dnsResponseRe = regexp.MustCompile(`^\[DNS\] (\S+) --> (.+) from (.+)$`)

// "cache hit <domain> --> <result>, expire at <time>"
var dnsCacheHitRe = regexp.MustCompile(`^\[DNS\] cache hit (\S+) --> (.+), expire at (.+)$`)

// "Truncated reply from <host>:<port> for <query> over UDP, retrying over TCP"
var dnsTruncatedRe = regexp.MustCompile(`^\[DNS\] Truncated reply from (\S+):(\S+) for (.+) over UDP, retrying over TCP$`)

// "dns cache ignored because of acme challenge for: <domain>"
var dnsAcmeRe = regexp.MustCompile(`^\[DNS\] dns cache ignored because of acme challenge for: (.+)$`)

type dnsCaptureSubscription struct {
	remote unsafe.Pointer
	cancel chan struct{}
}

var (
	dnsCapMu     sync.Mutex
	dnsCapNextID int64
	dnsCapMap    = map[int64]*dnsCaptureSubscription{}
)

// parseDnsLogMsg parses a [DNS]-prefixed log message into a structured event.
// Returns nil if the message is not a recognized DNS log pattern.
func parseDnsLogMsg(payload string) *dnsEvent {
	now := time.Now().UnixMilli()

	if m := dnsQueryRe.FindStringSubmatch(payload); m != nil {
		return &dnsEvent{
			Type:      "query",
			Domain:    m[1],
			QType:     m[2],
			Server:    m[3],
			Timestamp: now,
		}
	}
	if m := dnsResponseRe.FindStringSubmatch(payload); m != nil {
		return &dnsEvent{
			Type:      "response",
			Domain:    m[1],
			Result:    m[2],
			Server:    m[3],
			Timestamp: now,
		}
	}
	if m := dnsCacheHitRe.FindStringSubmatch(payload); m != nil {
		return &dnsEvent{
			Type:     "cache_hit",
			Domain:   m[1],
			Result:   m[2],
			ExpireAt: m[3],
			Timestamp: now,
		}
	}
	if m := dnsTruncatedRe.FindStringSubmatch(payload); m != nil {
		return &dnsEvent{
			Type:      "truncated",
			Domain:    m[3],
			Server:    m[1] + ":" + m[2],
			Timestamp: now,
		}
	}
	if m := dnsAcmeRe.FindStringSubmatch(payload); m != nil {
		return &dnsEvent{
			Type:      "acme_skip",
			Domain:    m[1],
			Timestamp: now,
		}
	}
	return nil
}

//export subscribeDns
func subscribeDns(remote unsafe.Pointer) int64 {
	id := atomic.AddInt64(&dnsCapNextID, 1)
	cancel := make(chan struct{})

	dnsCapMu.Lock()
	dnsCapMap[id] = &dnsCaptureSubscription{remote: remote, cancel: cancel}
	dnsCapMu.Unlock()

	go func(id int64, remote unsafe.Pointer, cancel <-chan struct{}) {
		defer safeRecover("subscribeDns")
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

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
			dnsCapMu.Lock()
			delete(dnsCapMap, id)
			dnsCapMu.Unlock()
		}()

		for {
			select {
			case <-cancel:
				log.Debugln("[APP] DNS subscriber cancelled")
				return
			case msg, ok := <-sub:
				if !ok {
					return
				}

				// Capture all DNS log messages regardless of log level.
				event := parseDnsLogMsg(msg.Payload)
				if event == nil {
					continue
				}

				if C.dns_received(remote, marshalJson(event)) != 0 {
					log.Debugln("[APP] DNS subscriber closed")
					return
				}
			}
		}
	}(id, remote, cancel)

	return id
}

//export unsubscribeDns
func unsubscribeDns(id int64) {
	dnsCapMu.Lock()
	sub, ok := dnsCapMap[id]
	if ok {
		delete(dnsCapMap, id)
	}
	dnsCapMu.Unlock()

	if ok {
		close(sub.cancel)
	}
}