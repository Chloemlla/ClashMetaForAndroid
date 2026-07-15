package main

import "C"

import (
	"encoding/json"
	"reflect"

	"github.com/metacubex/mihomo/log"
)

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
