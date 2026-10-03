package com.p25.apx1000.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Fallback receiver for hardware PTT keys delivered as media-button broadcasts
 * when no active [androidx.media.session.MediaSessionCompat] consumes them.
 */
class PttButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (!PttService.isPttKey(event.keyCode)) return

        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> PttService.ACTION_PTT_DOWN
            KeyEvent.ACTION_UP -> PttService.ACTION_PTT_UP
            else -> return
        }

        try {
            val svc = Intent(context, PttService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, svc)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not forward PTT key to service", t)
        }
    }

    companion object {
        private const val TAG = "PttButtonReceiver"
    }
}
