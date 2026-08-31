package com.github.kr328.clash.common.constants

/**
 * Mirror of `core/src/main/golang/native/config/adblock.go`. The Go side is what actually injects
 * the rule-provider, so it owns these values — a change there has to be copied here, and a
 * mismatch is silent (the rules simply never match).
 */
object Adblock {
    const val PROVIDER_NAME = "cfm-adblock"
    const val PROVIDER_URL =
        "https://raw.githubusercontent.com/217heidai/adblockfilters/main/rules/adblockmihomo.mrs"

    /** Written by the Go core inside the profile's clash directory. */
    const val HITS_FILE_NAME = "adblock_hits.jsonl"
}
