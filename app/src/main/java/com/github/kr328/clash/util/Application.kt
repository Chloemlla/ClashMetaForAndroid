package com.github.kr328.clash.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

object ApplicationObserver {
    // Written from the main-thread lifecycle callbacks but read from arbitrary threads
    // (foreground checks, config-change fan-out), so the sets must publish safely.
    private val _createdActivities: MutableSet<Activity> = ConcurrentHashMap.newKeySet()
    private val _visibleActivities: MutableSet<Activity> = ConcurrentHashMap.newKeySet()

    private var visibleChanged: (Boolean) -> Unit = {}

    /** Epoch millis when the app last went fully to the background (all activities stopped). */
    @Volatile
    private var lastBackgroundedAt: Long = 0L

    /**
     * One-shot: the length of the last background trip (ms), captured when the app returns to the
     * foreground. Used by the app-lock resume gate (B-72) so a fresh authentication is required
     * only when the app actually came back after the timeout, never while the app stays foreground.
     */
    @Volatile
    var backgroundReturnMs: Long = 0L
        private set

    private var appVisible = false
        private set(value) {
            if (field != value) {
                field = value

                visibleChanged(value)
            }
        }

    val createdActivities: Set<Activity>
        get() = _createdActivities

    private val activityObserver = object : Application.ActivityLifecycleCallbacks {
        @Synchronized
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            _createdActivities.add(activity)
        }

        @Synchronized
        override fun onActivityDestroyed(activity: Activity) {
            _createdActivities.remove(activity)
            _visibleActivities.remove(activity)
            if (_visibleActivities.isEmpty()) {
                appVisible = false
                markBackgrounded()
            }
        }

        @Synchronized
        override fun onActivityStarted(activity: Activity) {
            val wasVisible = appVisible
            _visibleActivities.add(activity)
            appVisible = true
            if (!wasVisible && lastBackgroundedAt > 0L) {
                // Returning from a real background trip: capture how long it lasted, then reset
                // the background clock so foreground navigation never re-arms the gate.
                backgroundReturnMs = System.currentTimeMillis() - lastBackgroundedAt
                lastBackgroundedAt = 0L
            }
        }

        @Synchronized
        override fun onActivityStopped(activity: Activity) {
            _visibleActivities.remove(activity)
            if (_visibleActivities.isEmpty()) {
                appVisible = false
                markBackgrounded()
            }
        }

        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    private fun markBackgrounded() {
        // Only the first stop of a background trip stamps the clock. A later onActivityDestroyed /
        // onActivityStopped while still fully backgrounded (e.g. system reclaims a stopped
        // activity) must not shrink the measured background duration.
        if (lastBackgroundedAt == 0L) {
            lastBackgroundedAt = System.currentTimeMillis()
            // An in-process "unlocked" flag must not survive a real background trip: the next
            // return from background is exactly when the app lock should re-check.
            AppLockController.onAppBackgrounded()
        }
    }

    fun onVisibleChanged(visibleChanged: (Boolean) -> Unit) {
        this.visibleChanged = visibleChanged
    }

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(activityObserver)
    }
}

fun Context.verifyApk(): Boolean {
    return try {
        val info = applicationInfo
        val sources = info.splitSourceDirs ?: arrayOf(info.sourceDir)

        val regexNativeLibrary = Regex("lib/(\\S+)/libclash.so")
        val availableAbi = Build.SUPPORTED_ABIS.toSet()
        val apkAbi = sources
            .asSequence()
            .filter { File(it).exists() }
            .flatMap { path ->
                // Materialize entries inside use{} so the ZipFile is closed before the sequence continues.
                ZipFile(path).use { zip ->
                    zip.entries()
                        .asSequence()
                        .mapNotNull { regexNativeLibrary.matchEntire(it.name) }
                        .mapNotNull { it.groups[1]?.value }
                        .toList()
                }
            }
            .toSet()

        availableAbi.intersect(apkAbi).isNotEmpty()
    } catch (e: Exception) {
        false
    }
}