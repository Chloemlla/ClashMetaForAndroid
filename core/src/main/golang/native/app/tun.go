package app

import (
	"net"
	"strconv"
	"sync"
	"syscall"
	"time"

	"cfa/native/platform"
)

var markSocketImpl func(fd int)
var querySocketUidImpl func(protocol int, source, target string) int

// Short-lived cache for connection-owner UID lookups on the data path.
// Keyed by protocol + endpoints; invalidated when the TUN context is replaced.
type uidCacheEntry struct {
	uid       int
	expiresAt time.Time
}

const (
	uidCacheTTL   = 2 * time.Second
	uidCacheLimit = 2048
)

var (
	uidCacheMu sync.Mutex
	uidCache   = make(map[string]uidCacheEntry, 256)
)

func MarkSocket(fd int) {
	markSocketImpl(fd)
}

func QuerySocketUid(source, target net.Addr) int {
	var protocol int

	switch source.Network() {
	case "udp", "udp4", "udp6":
		protocol = syscall.IPPROTO_UDP
	case "tcp", "tcp4", "tcp6":
		protocol = syscall.IPPROTO_TCP
	default:
		return -1
	}

	src := source.String()
	dst := target.String()
	key := uidCacheKey(protocol, src, dst)

	if uid, ok := lookupUidCache(key); ok {
		return uid
	}

	var uid int
	if PlatformVersion() < 29 {
		uid = platform.QuerySocketUidFromProcFs(source, target)
	} else {
		uid = querySocketUidImpl(protocol, src, dst)
	}

	storeUidCache(key, uid)
	return uid
}

func uidCacheKey(protocol int, source, target string) string {
	// protocol is 6/17 — prefix keeps TCP/UDP distinct for the same endpoints.
	return strconv.Itoa(protocol) + "|" + source + "|" + target
}

func lookupUidCache(key string) (int, bool) {
	now := time.Now()

	uidCacheMu.Lock()
	defer uidCacheMu.Unlock()

	entry, ok := uidCache[key]
	if !ok {
		return -1, false
	}
	if now.After(entry.expiresAt) {
		delete(uidCache, key)
		return -1, false
	}
	return entry.uid, true
}

func storeUidCache(key string, uid int) {
	uidCacheMu.Lock()
	defer uidCacheMu.Unlock()

	if len(uidCache) >= uidCacheLimit {
		// Drop expired entries; if still full, clear wholesale (cheap, rare).
		now := time.Now()
		for k, v := range uidCache {
			if now.After(v.expiresAt) {
				delete(uidCache, k)
			}
		}
		if len(uidCache) >= uidCacheLimit {
			uidCache = make(map[string]uidCacheEntry, 256)
		}
	}

	uidCache[key] = uidCacheEntry{
		uid:       uid,
		expiresAt: time.Now().Add(uidCacheTTL),
	}
}

func clearUidCache() {
	uidCacheMu.Lock()
	uidCache = make(map[string]uidCacheEntry, 256)
	uidCacheMu.Unlock()
}

func ApplyTunContext(markSocket func(fd int), querySocketUid func(int, string, string) int) {
	if markSocket == nil {
		markSocket = func(fd int) {}
	}

	if querySocketUid == nil {
		querySocketUid = func(int, string, string) int { return -1 }
	}

	markSocketImpl = markSocket
	querySocketUidImpl = querySocketUid
	clearUidCache()
}

func init() {
	ApplyTunContext(nil, nil)
}
