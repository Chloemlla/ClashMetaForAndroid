package main

//#include "bridge.h"
import "C"

import (
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/metacubex/mihomo/log"
)

type message struct {
	Level   string `json:"level"`
	Message string `json:"message"`
	Time    int64  `json:"time"`
}

type logcatSubscription struct {
	remote unsafe.Pointer
	cancel chan struct{}
}

var (
	logcatMu      sync.Mutex
	logcatNextID  int64
	logcatCancels = map[int64]*logcatSubscription{}
)

func init() {
	go func() {
		defer safeRecover("logForwarder")
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			cPayload := C.CString(msg.Payload)

			switch msg.LogLevel {
			case log.INFO:
				C.log_info(cPayload)
			case log.ERROR:
				C.log_error(cPayload)
			case log.WARNING:
				C.log_warn(cPayload)
			case log.DEBUG:
				C.log_debug(cPayload)
			case log.SILENT:
				C.log_verbose(cPayload)
			}
		}
	}()
}

//export subscribeLogcat
func subscribeLogcat(remote unsafe.Pointer) int64 {
	id := atomic.AddInt64(&logcatNextID, 1)
	cancel := make(chan struct{})

	logcatMu.Lock()
	logcatCancels[id] = &logcatSubscription{remote: remote, cancel: cancel}
	logcatMu.Unlock()

	go func(id int64, remote unsafe.Pointer, cancel <-chan struct{}) {
		defer safeRecover("subscribeLogcat")
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
			logcatMu.Lock()
			delete(logcatCancels, id)
			logcatMu.Unlock()
		}()

		for {
			select {
			case <-cancel:
				log.Debugln("Logcat subscriber cancelled")
				return
			case msg, ok := <-sub:
				if !ok {
					return
				}

				if msg.LogLevel < log.Level() && !strings.HasPrefix(msg.Payload, "[APP]") {
					continue
				}

				rMsg := &message{
					Level:   msg.LogLevel.String(),
					Message: msg.Payload,
					Time:    time.Now().UnixNano() / 1000 / 1000,
				}

				// Non-zero means the Java side wants to stop (closed channel).
				if C.logcat_received(remote, marshalJson(rMsg)) != 0 {
					log.Debugln("Logcat subscriber closed")
					return
				}
			}
		}
	}(id, remote, cancel)

	log.Infoln("[APP] Logcat level: %s", log.Level().String())
	return id
}

//export unsubscribeLogcat
func unsubscribeLogcat(id int64) {
	logcatMu.Lock()
	sub, ok := logcatCancels[id]
	if ok {
		delete(logcatCancels, id)
	}
	logcatMu.Unlock()

	if ok {
		close(sub.cancel)
	}
}
