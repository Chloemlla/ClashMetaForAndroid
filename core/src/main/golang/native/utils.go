package main

import "C"

import (
	"encoding/json"
	"reflect"
	"runtime/debug"

	"github.com/metacubex/mihomo/log"
)

// safeRecover must be deferred at the top of any goroutine launched from a
// //export-ed JNI function. An unrecovered panic in such a goroutine tears down
// the whole :background process, dropping an active VPN tunnel with no stack
// context propagated back to Kotlin. Log the panic + stack instead and keep
// the process alive.
func safeRecover(name string) {
	if r := recover(); r != nil {
		log.Errorln("[APP] panic in %s: %v\n%s", name, r, debug.Stack())
	}
}

func marshalJson(obj any) *C.char {
	res, err := json.Marshal(obj)
	if err != nil {
		// A marshal failure must not crash the whole process across the JNI
		// boundary. Log it and return an empty JSON object so the caller can
		// degrade gracefully instead of taking down the tunnel.
		log.Errorln("[APP] marshalJson failed for %s: %v", reflect.TypeOf(obj), err)
		return C.CString("{}")
	}

	return C.CString(string(res))
}

func marshalString(obj any) *C.char {
	if obj == nil {
		return nil
	}

	switch o := obj.(type) {
	case error:
		return C.CString(o.Error())
	case string:
		return C.CString(o)
	}

	// Unknown types are a programming error, but panicking here crashes the
	// process from a JNI-exported call. Log and return null instead.
	log.Errorln("[APP] marshalString: invalid marshal type %s", reflect.TypeOf(obj).Name())
	return nil
}
