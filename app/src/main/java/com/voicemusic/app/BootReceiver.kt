package com.voicemusic.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Optional "start when the car turns on" behavior for a head-unit install (Settings -> Start on boot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON") return
        val app = context.applicationContext as? App ?: return
        if (!app.config.startOnBoot) return
        try {
            val svc = Intent(context, PlaybackService::class.java)
            ContextCompat.startForegroundService(context, svc)
        } catch (e: Exception) { CrashLog.note("BootReceiver start failed", e) }
    }
}
