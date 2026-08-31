package com.github.kr328.clash

import android.app.ActivityManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import com.github.kr328.clash.util.AppLockController
import com.github.kr328.clash.util.AppLockGate
import com.github.kr328.clash.util.isLumenCrashReportPending
import com.github.kr328.clash.util.presentPendingLumenCrashReportIfNeeded
import com.github.kr328.clash.common.compat.isAllowForceDarkCompat
import com.github.kr328.clash.common.compat.isLightNavigationBarCompat
import com.github.kr328.clash.common.compat.isLightStatusBarsCompat
import com.github.kr328.clash.common.compat.isSystemBarsTranslucentCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.bridge.ClashException
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.DayNight
import com.github.kr328.clash.design.util.resolveThemedBoolean
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.ActivityResultLifecycle
import com.github.kr328.clash.util.ApplicationObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import com.github.kr328.clash.design.R

abstract class BaseActivity<D : Design<*>> : AppCompatActivity(),
    CoroutineScope,
    Broadcasts.Observer {

    // SupervisorJob + handler: one failed main()/fetch must not cancel siblings or kill UI.
    private val activityJob = SupervisorJob()
    private val activityExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        runCatching {
            com.github.kr328.clash.common.log.Log.w(
                "Uncaught exception in ${javaClass.simpleName}: $throwable",
                throwable,
            )
        }
        runCatching {
            if (com.chloemlla.lumen.crash.LumenCrash.isInstalled()) {
                com.chloemlla.lumen.crash.LumenCrash.record(throwable)
            }
        }
    }
    override val coroutineContext =
        Dispatchers.Main.immediate + activityJob + activityExceptionHandler

    protected val uiStore by lazy { UiStore(this) }
    protected val events = Channel<Event>(Channel.UNLIMITED)
    protected var activityStarted: Boolean = false
    private var crashGateDeferred: Boolean = false
    protected val clashRunning: Boolean
        get() = Remote.broadcasts.clashRunning
    protected var design: D? = null
        set(value) {
            field = value
            if (value != null) {
                setContentView(value.root)
            } else {
                setContentView(View(this))
            }
        }

    private var defer: suspend () -> Unit = {}
    private var deferRunning = false
    private val nextRequestKey = AtomicInteger(0)
    private var dayNight: DayNight = DayNight.Day

    // Set true after the first onStart() following onCreate(); the cold-start gate already
    // ran in onCreate's launch{} before main(), so onStart must not immediately re-prompt.
    private var initialStartHandled = false

    // Guards against onCreate's cold-start gate and onStart's resume gate both firing a
    // BiometricPrompt concurrently (onStart runs synchronously right after onCreate returns,
    // while the cold-start authenticate() call may still be suspended awaiting a result).
    private var unlockGateInProgress = false

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!onBackPressedCompat()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    protected abstract suspend fun main()

    /**
     * Handle the system/back navigation affordance.
     * Return true if the event was fully handled; false to fall back to the default finish behavior.
     */
    protected open fun onBackPressedCompat(): Boolean = false

    fun defer(operation: suspend () -> Unit) {
        this.defer = operation
    }

    suspend fun <I, O> startActivityForResult(
        contracts: ActivityResultContract<I, O>,
        input: I,
    ): O = withContext(Dispatchers.Main) {
        val requestKey = nextRequestKey.getAndIncrement().toString()

        // Cancellable: the caller can be torn down while the child Activity is on top. A
        // non-cancellable suspension would never let ActivityResultLifecycle.use reach its
        // DESTROYED cleanup, stranding this coroutine and the registered launcher for good.
        ActivityResultLifecycle().use { lifecycle, start ->
            suspendCancellableCoroutine { c ->
                activityResultRegistry.register(requestKey, lifecycle, contracts) {
                    c.resume(it)
                }.apply { start() }.launch(input)
            }
        }
    }

    suspend fun setContentDesign(design: D) {
        suspendCancellableCoroutine<Unit> {
            window.decorView.post {
                this.design = design
                it.resume(Unit)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pending Lumen crash UI owns the process; gate before dayNight / main design work.
        // Fail-soft: a broken gate must never abort Activity creation.
        val gated = runCatching { presentPendingLumenCrashReportIfNeeded() }.getOrDefault(false)
        if (gated) {
            // presentPendingLumenCrashReportIfNeeded deliberately does not finish() us, so this
            // window now exists with no content view and no main(). Without this flag it would
            // stay blank forever after the crash surface is dismissed.
            crashGateDeferred = true
            return
        }

        applyDayNight()
        applySecureScreen()

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        // Apply excludeFromRecents setting to all app tasks.
        checkNotNull(getSystemService<ActivityManager>()).appTasks.forEach { task ->
            task.setExcludeFromRecents(uiStore.hideFromRecents)
        }

        launch {
            // Cold-start gate: when app lock is on, this must resolve before any Design
            // (Profiles/Logs/Properties/etc.) is attached to the window. On failure/cancel
            // we finish() rather than falling through to main() so content never leaks.
            if (!ensureUnlocked()) {
                finish()
                return@launch
            }

            try {
                main()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.github.kr328.clash.common.log.Log.w("main() failed: $e", e)

                // A screen that already drew stays usable and just reports the error; one that
                // never attached a Design is an unresponsive blank window, so close it instead.
                val attached = design
                if (attached == null) {
                    finish()
                } else {
                    attached.showExceptionToast(e)
                }
            }
        }
    }


    override fun onStart() {
        super.onStart()

        if (crashGateDeferred) {
            // Stay dormant while the crash surface still owns the screen; rebuilding now would
            // re-trigger the gate and loop. Once the report is consumed, rebuild for real.
            if (isLumenCrashReportPending()) {
                return
            }

            crashGateDeferred = false

            recreate()

            return
        }

        activityStarted = true
        Remote.broadcasts.addObserver(this)
        events.trySend(Event.ActivityStart)
        maybeGateOnResume()
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        Remote.broadcasts.removeObserver(this)
        events.trySend(Event.ActivityStop)
    }

    override fun onDestroy() {
        design?.cancel()
        activityJob.cancel()
        super.onDestroy()
    }

    override fun finish() {
        if (deferRunning) return
        deferRunning = true

        launch {
            try {
                defer()
            } finally {
                withContext(NonCancellable) {
                    super.finish()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (queryDayNight(newConfig) != dayNight) {
            ApplicationObserver.createdActivities.forEach {
                it.recreate()
            }
        }
    }

    open fun shouldDisplayHomeAsUpEnabled(): Boolean {
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onProfileChanged() {
        events.trySend(Event.ProfileChanged)
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        events.trySend(Event.ProfileUpdateCompleted)
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        events.trySend(Event.ProfileUpdateFailed)
    }

    override fun onProfileLoaded() {
        events.trySend(Event.ProfileLoaded)
    }

    override fun onServiceRecreated() {
        events.trySend(Event.ServiceRecreated)
    }

    override fun onStarted() {
        events.trySend(Event.ClashStart)
    }

    override fun onStopped(cause: String?) {
        events.trySend(Event.ClashStop)

        if (cause != null && activityStarted) {
            launch {
                design?.showExceptionToast(ClashException(cause))
            }
        }
    }

    private fun queryDayNight(config: Configuration = resources.configuration): DayNight {
        return when (uiStore.darkMode) {
            DarkMode.Auto -> if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) DayNight.Night else DayNight.Day
            DarkMode.ForceLight -> DayNight.Day
            DarkMode.ForceDark -> DayNight.Night
        }
    }

    private fun applyDayNight(config: Configuration = resources.configuration) {
        val dayNight = queryDayNight(config)
        when (dayNight) {
            DayNight.Night -> theme.applyStyle(R.style.AppThemeDark, true)
            DayNight.Day -> theme.applyStyle(R.style.AppThemeLight, true)
        }

        window.isAllowForceDarkCompat = false
        // Android 15+ edge-to-edge; Android 16 (targetSdk 36) removes the opt-out.
        // Draw under system bars and let designs pad via WindowInsets (Surface.insets).
        window.isSystemBarsTranslucentCompat = true
        WindowCompat.setDecorFitsSystemWindows(window, false)

        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.isNavigationBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= 23) {
            window.isLightStatusBarsCompat = resolveThemedBoolean(android.R.attr.windowLightStatusBar)
        }

        if (Build.VERSION.SDK_INT >= 27) {
            window.isLightNavigationBarCompat = resolveThemedBoolean(android.R.attr.windowLightNavigationBar)
        }

        this.dayNight = dayNight
    }

    private fun applySecureScreen() {
        if (uiStore.secureScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Cold-start gate: called from onCreate's launch{} before main(). Returns true when the
     * activity may proceed (lock disabled, already-fresh unlock, or successful authentication).
     */
    private suspend fun ensureUnlocked(): Boolean {
        initialStartHandled = true

        if (!AppLockController.isUnlockRequired(uiStore)) {
            return true
        }

        if (unlockGateInProgress) return false
        unlockGateInProgress = true
        try {
            return AppLockController.authenticate(this, uiStore)
        } finally {
            unlockGateInProgress = false
        }
    }

    /**
     * Return-from-background gate: after the initial cold start, re-check the timeout every
     * time the activity becomes visible again. On failure/cancel, finish() rather than
     * leaving stale Design content on screen (Profiles/Logs must not leak).
     */
    private fun maybeGateOnResume() {
        if (!initialStartHandled) return
        if (unlockGateInProgress) return
        if (!AppLockController.isUnlockRequired(uiStore)) return

        unlockGateInProgress = true
        launch {
            try {
                val unlocked = AppLockController.authenticate(this@BaseActivity, uiStore)
                if (!unlocked) {
                    finish()
                }
            } finally {
                unlockGateInProgress = false
            }
        }
    }

    enum class Event {
        ServiceRecreated,
        ActivityStart,
        ActivityStop,
        ClashStop,
        ClashStart,
        ProfileLoaded,
        ProfileChanged,
        ProfileUpdateCompleted,
        ProfileUpdateFailed,
    }
}
