package com.github.kr328.clash.design.model

class ProfilePageState {
    var allUpdating = false
        private set

    fun beginUpdateAll(): Boolean {
        if (allUpdating) return false

        allUpdating = true
        return true
    }

    fun finishUpdateAll() {
        allUpdating = false
    }
}
