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

// httpEvent is the JSON-serializable HTTP capture event pushed to Java.
// Captures plain HTTP request/response details (not HTTPS).
//
// NOTE: body/headers/connection are reserved for a future dedicated capture
// channel. The current log-based implementation can only carry method, path,
// host, status code and URL — headers and request bodies are not emitted in
// mihomo log messages, so those fields remain empty.
type httpEvent struct {
	Type       string `json:"type"`
	Method     string `json:"method,omitempty"`
	URL        string `json:"url,omitempty"`
	Host       string `json:"host,omitempty"`
	Path       string `json:"path,omitempty"`
	StatusCode int    `json:"statusCode,omitempty"`
	Body       string `json:"body,omitempty"`       // reserved; not populated by log capture
	Headers    string `json:"headers,omitempty"`    // reserved; not populated by log capture
	Connection string `json:"connection,omitempty"` // reserved; not populated by log capture
	Timestamp  int64  `json:"timestamp"`
}

// HTTP access log regex pattern emitted by the mihomo HTTP inbound
// (listener/http/proxy.go). Format:
// "[HTTP] <status> <method> <host> <path> from <src> in <duration>"
// Example: "[HTTP] 200 GET example.com /search?q=clash from 1.2.3.4:5678 in 12ms"
var httpAccessRe = regexp.MustCompile(`^\[HTTP\]\s+(\d{3})\s+(\S+)\s+(\S+)\s+(\S+)\s+from\s+\S+\s+in\s+\S+$`)

type httpCaptureSubscription struct {
	remote unsafe.Pointer
	cancel chan struct{}
}

var (
	httpCapMu     sync.Mutex
	httpCapNextID int64
	httpCapMap    = map[int64]*httpCaptureSubscription{}
)

// parseHttpLogMsg parses a [HTTP]-prefixed log message into a structured event.
// Returns nil if the message is not a recognized HTTP log pattern.
func parseHttpLogMsg(payload string) *httpEvent {
	now := time.Now().UnixMilli()

	// Access log: "[HTTP] 200 GET example.com /search?q=clash from 1.2.3.4:5678 in 12ms"
	if m := httpAccessRe.FindStringSubmatch(payload); m != nil {
		host := m[3]
		path := m[4]
		event := &httpEvent{
			Type:       "access",
			StatusCode: parseIntOrZero(m[1]),
			Method:     m[2],
			Host:       host,
			Path:       path,
			URL:        host + path,
			Timestamp:  now,
		}
		return event
	}

	return nil
}

// parseIntOrZero converts a string to int, returning 0 on failure.
func parseIntOrZero(s string) int {
	n := 0
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0
		}
		n = n*10 + int(c-'0')
	}
	return n
}

//export subscribeHttp
func subscribeHttp(remote unsafe.Pointer) int64 {
	id := atomic.AddInt64(&httpCapNextID, 1)
	cancel := make(chan struct{})

	httpCapMu.Lock()
	httpCapMap[id] = &httpCaptureSubscription{remote: remote, cancel: cancel}
	httpCapMu.Unlock()

	go func(id int64, remote unsafe.Pointer, cancel <-chan struct{}) {
		defer safeRecover("subscribeHttp")
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
			httpCapMu.Lock()
			delete(httpCapMap, id)
			httpCapMu.Unlock()
		}()

		for {
			select {
			case <-cancel:
				log.Debugln("[APP] HTTP subscriber cancelled")
				return
			case msg, ok := <-sub:
				if !ok {
					return
				}

				// Parse HTTP log messages.
				event := parseHttpLogMsg(msg.Payload)
				if event == nil {
					continue
				}

				if C.http_received(remote, marshalJson(event)) != 0 {
					log.Debugln("[APP] HTTP subscriber closed")
					return
				}
			}
		}
	}(id, remote, cancel)

	return id
}

//export unsubscribeHttp
func unsubscribeHttp(id int64) {
	httpCapMu.Lock()
	sub, ok := httpCapMap[id]
	if ok {
		delete(httpCapMap, id)
	}
	httpCapMu.Unlock()

	if ok {
		close(sub.cancel)
	}
}
