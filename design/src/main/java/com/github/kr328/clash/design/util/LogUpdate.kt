package com.github.kr328.clash.design.util

data class LogUpdate(
    val removed: Int,
    val insertionStart: Int,
    val appended: Int,
)

fun calculateLogUpdate(
    oldSize: Int,
    updatedSize: Int,
    removed: Int,
    appended: Int,
): LogUpdate? {
    val boundedRemoved = removed.coerceIn(0, oldSize)
    val insertionStart = oldSize - boundedRemoved
    val valid = removed == boundedRemoved &&
        appended >= 0 &&
        insertionStart + appended == updatedSize

    return if (valid) LogUpdate(boundedRemoved, insertionStart, appended) else null
}
