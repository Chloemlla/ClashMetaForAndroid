package app

import (
	"time"

	"github.com/dlclark/regexp2"

	"github.com/metacubex/mihomo/log"
)

// Cap catastrophic backtracking on user-supplied ui-subtitle-pattern.
// Applied per match; after timeout Find*Match returns an error and callers keep the raw name.
const subtitleMatchTimeout = 50 * time.Millisecond

var uiSubtitlePattern *regexp2.Regexp

func ApplySubtitlePattern(pattern string) {
	if pattern == "" {
		uiSubtitlePattern = nil

		return
	}

	if o := uiSubtitlePattern; o != nil && o.String() == pattern {
		return
	}

	reg, err := regexp2.Compile(pattern, regexp2.IgnoreCase|regexp2.Compiled)
	if err == nil {
		reg.MatchTimeout = subtitleMatchTimeout
		uiSubtitlePattern = reg
	} else {
		uiSubtitlePattern = nil

		log.Warnln("Compile ui-subtitle-pattern: %s", err.Error())
	}
}

func SubtitlePattern() *regexp2.Regexp {
	return uiSubtitlePattern
}
