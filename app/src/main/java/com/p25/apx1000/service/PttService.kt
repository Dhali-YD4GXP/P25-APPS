package com.p25.apx1000.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import com.p25.apx1000.MainActivity
import com.p25.apx1000.R
import com.p25.apx1000.audio.PttEngine
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persistent foreground service that owns the [PttEngine].
 *
 * It keeps the CPU awake with a partial wake lock and keeps the side PTT key
 * responsive while the screen is off or the app is backgrounded, via a
 * [MediaSessionCompat] plus vendor key codes.
 */
class PttService : Service(), PttEngine.Listener {

    interface UiListener {
        fun onLight(light: PttEngine.Light)
        fun onSpeaker(unitId: String?)
        fun onBusy(busy: Boolean)
        fun onError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun service(): PttService = this@PttService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiListeners = CopyOnWriteArrayList<UiListener>()

    private var engine: PttEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startInForeground()
        acquireWakeLock()
        setupMediaSession()
        engine = PttEngine(this, this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_PTT_DOWN -> pttDown()
            ACTION_PTT_UP -> pttUp()
            ACTION_TOGGLE_BUSY -> setChannelBusy(!busyState)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine?.release()
        engine = null
        releaseWakeLock()
        mediaSession?.release()
        mediaSession = null
        uiListeners.clear()
        super.onDestroy()
    }

    // ---- Public API used by the Activity --------------------------------

    fun addUiListener(l: UiListener) {
        uiListeners.addIfAbsent(l)
    }

    fun removeUiListener(l: UiListener) {
        uiListeners.remove(l)
    }

    fun pttDown() = engine?.pttDown()
    fun pttUp() = engine?.pttUp()

    var busyState: Boolean = false
        private set

    fun setChannelBusy(busy: Boolean) {
        busyState = busy
        engine?.setChannelBusy(busy)
    }

    fun replayRx(unitId: String?) = engine?.replayRx(unitId)

    fun setCodecMode(mode: Int) = engine?.start(mode)

    fun codecModeName(): String = engine?.codecModeName ?: "-"

    fun setVisible(v: Boolean) = engine?.setVisible(v)

    /** Transport hook for the future WSS uplink. */
    fun setFrameSink(sink: ((ByteArray) -> Unit)?) {
        engine?.onFrameEncoded = sink
    }

    // ---- PttEngine.Listener (called on worker threads) ------------------

    override fun onLight(light: PttEngine.Light) = postToUi { it.onLight(light) }
    override fun onSpeaker(unitId: String?) = postToUi { it.onSpeaker(unitId) }
    override fun onBusy(busy: Boolean) = postToUi { it.onBusy(busy) }
    override fun onError(message: String) = postToUi { it.onError(message) }

    private fun postToUi(block: (UiListener) -> Unit) {
        mainHandler.post { uiListeners.forEach { block(it) } }
    }

    // ---- Key handling ---------------------------------------------------

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "PttService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event != null && handleKeyEvent(event)) return true
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }
    }

    /** Returns true if the event was consumed as a PTT press. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isPttKey(event.keyCode)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) pttDown()
            KeyEvent.ACTION_UP -> pttUp()
        }
        return true
    }

    private fun startInForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed (mic permission?)", t)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "PttService"
        private const val WAKE_TAG = "P25Apx1000::PttWakeLock"
        private const val CHANNEL_ID = "p25_ptt"
        private const val NOTIF_ID = 0x25

        const val ACTION_PTT_DOWN = "com.p25.apx1000.PTT_DOWN"
        const val ACTION_PTT_UP = "com.p25.apx1000.PTT_UP"
        const val ACTION_TOGGLE_BUSY = "com.p25.apx1000.TOGGLE_BUSY"

        private const val KEYCODE_PTT = 228
        private const val KEYCODE_VENDOR_1 = 288
        private const val KEYCODE_VENDOR_2 = 301
        private const val KEYCODE_MEDIA_RECORD = 130

        fun isPttKey(keyCode: Int): Boolean =
            keyCode == KEYCODE_PTT ||
                keyCode == KEYCODE_VENDOR_1 ||
                keyCode == KEYCODE_VENDOR_2 ||
                keyCode == KEYCODE_MEDIA_RECORD ||
                keyCode == KeyEvent.KEYCODE_HEADSETHOOK

        fun start(context: Context) {
            val intent = Intent(context, PttService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
