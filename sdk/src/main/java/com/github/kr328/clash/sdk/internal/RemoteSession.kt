package com.github.kr328.clash.sdk.internal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import java.util.concurrent.TimeUnit

internal class RemoteSession(
    private val context: Application,
    private val onCrashed: () -> Unit,
) {
    val remote = Resource<IRemoteService>()

    private val connection = object : ServiceConnection {
        private var lastCrashed: Long = -1

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            remote.set(service.unwrap(IRemoteService::class))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleDeath("RemoteService killed or crashed")
        }

        override fun onBindingDied(name: ComponentName?) {
            // Binding was alive and then died without a clean disconnect; the system will not
            // deliver another callback, so unbind now so a later bind() can re-connect.
            handleDeath("RemoteService binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            // Service returned a null Binder: a contract violation that will never recover.
            remote.set(null)
            unbind()
            onCrashed()
            Log.w("RemoteService returned a null binder")
        }

        private fun handleDeath(message: String) {
            remote.set(null)

            if (System.currentTimeMillis() - lastCrashed < TOGGLE_CRASHED_INTERVAL) {
                unbind()
                onCrashed()
            }

            lastCrashed = System.currentTimeMillis()
            Log.w(message)
        }
    }

    fun bind() {
        try {
            // bindService signals failure with a false return, not an exception.
            if (!context.bindService(
                    RemoteService::class.intent,
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            ) {
                Log.w("Bind RemoteService rejected")
                unbind()
                onCrashed()
            }
        } catch (e: Exception) {
            Log.w("Bind RemoteService: $e", e)
            unbind()
            onCrashed()
        }
    }

    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
            // already unbound
        }
        remote.set(null)
    }

    companion object {
        private val TOGGLE_CRASHED_INTERVAL = TimeUnit.SECONDS.toMillis(10)
    }
}