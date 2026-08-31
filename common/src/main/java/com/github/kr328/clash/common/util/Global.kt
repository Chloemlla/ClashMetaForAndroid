package com.github.kr328.clash.common.util

import com.github.kr328.clash.common.Global

// Lazy on purpose: eager top-level initialization would fail whichever process touches this
// file before Global.init(), turning the real cause into NoClassDefFoundError forever after.
val packageName: String
    get() = Global.application.packageName