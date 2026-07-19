// +build linux

package platform

import (
	"bufio"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"
)

var netIndexOfLocal = -1
var netIndexOfUid = -1

var nativeEndian binary.ByteOrder

// Short-lived whole-table snapshots: many new connections miss the per-tuple cache
// and would otherwise re-open and re-scan /proc/net/* for each miss.
const procSnapshotTTL = 200 * time.Millisecond

type procSnapshot struct {
	// local_address (hex ip:port, lowercased) → uid
	byLocal   map[string]int
	expiresAt time.Time
}

var (
	procSnapshotMu sync.Mutex
	procSnapshots  = map[string]*procSnapshot{}
)

func QuerySocketUidFromProcFs(source, _ net.Addr) int {
	if netIndexOfLocal < 0 || netIndexOfUid < 0 {
		return -1
	}

	network := source.Network()

	if strings.HasSuffix(network, "4") || strings.HasSuffix(network, "6") {
		network = network[:len(network)-1]
	}

	path := "/proc/net/" + network

	var sIP net.IP
	var sPort int

	switch s := source.(type) {
	case *net.TCPAddr:
		sIP = s.IP
		sPort = s.Port
	case *net.UDPAddr:
		sIP = s.IP
		sPort = s.Port
	default:
		return -1
	}

	sIP = sIP.To16()
	if sIP == nil {
		return -1
	}

	uid := doQuery(path+"6", sIP, sPort)
	if uid == -1 {
		sIP = sIP.To4()
		if sIP == nil {
			return -1
		}
		uid = doQuery(path, sIP, sPort)
	}

	return uid
}

func doQuery(path string, sIP net.IP, sPort int) int {
	var bytes [2]byte
	binary.BigEndian.PutUint16(bytes[:], uint16(sPort))
	local := strings.ToLower(fmt.Sprintf("%s:%s", hex.EncodeToString(nativeEndianIP(sIP)), hex.EncodeToString(bytes[:])))

	if uid, ok := lookupProcSnapshot(path, local); ok {
		return uid
	}

	table, err := loadProcTable(path)
	if err != nil {
		return -1
	}
	storeProcSnapshot(path, table)

	if uid, ok := table[local]; ok {
		return uid
	}
	return -1
}

func lookupProcSnapshot(path, local string) (int, bool) {
	now := time.Now()

	procSnapshotMu.Lock()
	defer procSnapshotMu.Unlock()

	snap, ok := procSnapshots[path]
	if !ok || now.After(snap.expiresAt) {
		return -1, false
	}
	uid, found := snap.byLocal[local]
	if !found {
		return -1, true // table is fresh but this local endpoint is absent
	}
	return uid, true
}

func storeProcSnapshot(path string, table map[string]int) {
	procSnapshotMu.Lock()
	procSnapshots[path] = &procSnapshot{
		byLocal:   table,
		expiresAt: time.Now().Add(procSnapshotTTL),
	}
	procSnapshotMu.Unlock()
}

func loadProcTable(path string) (map[string]int, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	reader := bufio.NewReader(file)
	table := make(map[string]int, 256)

	for {
		row, _, err := reader.ReadLine()
		if err != nil {
			break
		}

		fields := strings.Fields(string(row))
		if len(fields) <= netIndexOfLocal || len(fields) <= netIndexOfUid {
			continue
		}

		uid, err := strconv.Atoi(fields[netIndexOfUid])
		if err != nil {
			continue
		}
		table[strings.ToLower(fields[netIndexOfLocal])] = uid
	}

	return table, nil
}

func nativeEndianIP(ip net.IP) []byte {
	result := make([]byte, len(ip))

	for i := 0; i < len(ip); i += 4 {
		value := binary.BigEndian.Uint32(ip[i:])

		nativeEndian.PutUint32(result[i:], value)
	}

	return result
}

func init() {
	file, err := os.Open("/proc/net/tcp")
	if err != nil {
		return
	}

	defer file.Close()

	reader := bufio.NewReader(file)

	header, _, err := reader.ReadLine()
	if err != nil {
		return
	}

	columns := strings.Fields(string(header))

	var txQueue, rxQueue, tr, tmWhen bool

	for idx, col := range columns {
		offset := 0

		if txQueue && rxQueue {
			offset--
		}

		if tr && tmWhen {
			offset--
		}

		switch col {
		case "tx_queue":
			txQueue = true
		case "rx_queue":
			rxQueue = true
		case "tr":
			tr = true
		case "tm->when":
			tmWhen = true
		case "local_address":
			netIndexOfLocal = idx + offset
		case "uid":
			netIndexOfUid = idx + offset
		}
	}
}

func init() {
	var x uint32 = 0x01020304
	if *(*byte)(unsafe.Pointer(&x)) == 0x01 {
		nativeEndian = binary.BigEndian
	} else {
		nativeEndian = binary.LittleEndian
	}
}
