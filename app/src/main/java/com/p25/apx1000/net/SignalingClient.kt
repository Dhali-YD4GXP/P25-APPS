package com.p25.apx1000.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the P25 signaling + floor-control backend
 * (`wss://p25.dhali.my.id/ws`).
 *
 * Protocol (JSON text + opaque binary Codec 2 frames):
 *   -> {"type":"hello","unitId":"1001","channel":"P25-CH-8891"}
 *   -> {"type":"join","channel":"P25-CH-8891"}
 *   -> {"type":"ptt","state":"down"|"up"}
 *   -> <binary Codec 2 frame>
 *   <- {"type":"welcome"|"joined"|"member"|"floor"|"speaker"|"error"|"pong"}
 *   <- <binary Codec 2 frame>
 *
 * Reconnects automatically with backoff while [wantConnected] is true.
 */
class SignalingClient(
    private val url: String,
    private val listener: Listener
) {

    interface Listener {
        fun onConnecting()
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onJoined(channelName: String)
        fun onFloor(busy: Boolean, holder: String?, granted: Boolean)
        fun onSpeaker(unitId: String?)
        fun onRemoteFrame(frame: ByteArray)
        fun onError(message: String)
    }

    @Volatile private var webSocket: WebSocket? = null
    @Volatile var isConnected: Boolean = false
        private set

    @Volatile private var wantConnected = false
    @Volatile private var unitId: String = ""
    @Volatile private var channel: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private var retryDelayMs = 2000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for the socket
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val retry = Runnable { if (wantConnected) openSocket() }

    /** Connect (or reconnect) with the given identity and optional channel. */
    fun connect(unitId: String, channel: String) {
        val newId = unitId.trim().uppercase()
        val idChanged = newId != this.unitId
        this.unitId = newId
        this.channel = channel.trim().uppercase()
        wantConnected = true
        retryDelayMs = 2000L
        handler.removeCallbacks(retry)
        if (idChanged) {
            try {
                webSocket?.close(1000, "re-identify")
            } catch (_: Throwable) {
            }
            webSocket = null
            isConnected = false
        }
        openSocket()
    }

    /** Join (or switch) a talkgroup channel on the current connection. */
    fun join(channel: String) {
        this.channel = channel.trim().uppercase()
        send(JSONObject().put("type", "join").put("channel", this.channel))
    }

    fun sendPtt(state: String) {
        send(JSONObject().put("type", "ptt").put("state", state))
    }

    fun sendFrame(frame: ByteArray) {
        val ws = webSocket ?: return
        if (!isConnected) return
        try {
            ws.send(frame.toByteString())
        } catch (t: Throwable) {
            Log.w(TAG, "sendFrame failed", t)
        }
    }

    fun disconnect() {
        wantConnected = false
        handler.removeCallbacks(retry)
        try {
            webSocket?.close(1000, "client closing")
        } catch (_: Throwable) {
        }
        webSocket = null
        isConnected = false
    }

    private fun openSocket() {
        if (!wantConnected) return
        try {
            listener.onConnecting()
            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, socketListener)
        } catch (t: Throwable) {
            Log.w(TAG, "newWebSocket failed", t)
            listener.onDisconnected(t.javaClass.simpleName)
            scheduleRetry()
        }
    }

    private fun send(obj: JSONObject) {
        val ws = webSocket
        if (ws == null || !isConnected) return
        try {
            ws.send(obj.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${obj}", t)
        }
    }

    private fun scheduleRetry() {
        if (!wantConnected) return
        handler.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "connected: $url")
            isConnected = true
            retryDelayMs = 2000L
            listener.onConnected()
            send(JSONObject().put("type", "hello").put("unitId", unitId).put("channel", channel))
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                val obj = JSONObject(text)
                when (obj.optString("type")) {
                    "welcome" -> {}
                    "joined" -> listener.onJoined(obj.optJSONObject("channel")?.optString("name") ?: channel)
                    "floor" -> listener.onFloor(
                        busy = obj.optBoolean("busy", false),
                        holder = if (obj.isNull("holder")) null else obj.optString("holder").ifEmpty { null },
                        granted = obj.optBoolean("granted", false)
                    )
                    "speaker" -> listener.onSpeaker(obj.optString("unitId").ifEmpty { null })
                    "error" -> listener.onError(obj.optString("message", "server error"))
                    "pong" -> {}
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bad message: $text", t)
            }
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            listener.onRemoteFrame(bytes.toByteArray())
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            onDown("closed $code")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            val msg = t.javaClass.simpleName + (t.message?.let { ": ${it.take(60)}" } ?: "")
            onDown(msg)
        }

        private fun onDown(reason: String) {
            if (!isConnected) return
            isConnected = false
            Log.w(TAG, "disconnected: $reason")
            listener.onDisconnected(reason)
            scheduleRetry()
        }
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
