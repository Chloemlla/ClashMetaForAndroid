package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DialerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SECRET_CODE) {
            return
        }

        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }

    companion object {
        private const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"
    }
}
