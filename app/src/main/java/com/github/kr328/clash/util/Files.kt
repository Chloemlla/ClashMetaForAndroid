package com.github.kr328.clash.util

import android.content.Context
import java.io.File

val Context.logsDir: File
    // Logs must live in filesDir, not cacheDir: the system clears cacheDir without notice,
    // which would silently delete logs that LogcatWriter still counts against its own quota.
    // The two layers must agree on what survives.
    get() = filesDir.resolve("logs")

val Context.clashDir: File
    get() = filesDir.resolve("clash")