package com.jo.facelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            if (LockState.isEnabled(context)) {
                ContextCompat.startForegroundService(
                    context, Intent(context, MonitorService::class.java)
                )
            }
        }
    }
}
