package com.voicemusic.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts playback if the app process was killed and a hardware media button is pressed. */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val svcIntent = Intent(context, PlaybackService::class.java).setAction(Intent.ACTION_MEDIA_BUTTON)
        svcIntent.putExtra(Intent.EXTRA_KEY_EVENT, intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_KEY_EVENT))
        try { ContextCompat.startForegroundService(context, svcIntent) } catch (e: Exception) { CrashLog.note("MediaButtonReceiver start failed", e) }
    }
}
