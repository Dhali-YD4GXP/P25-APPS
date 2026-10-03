package com.p25.apx1000.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.Collections

/**
 * Half-duplex push-to-talk engine.
 *
 * TX path: microphone -> Codec 2 encode -> [onFrameEncoded] (transport hook,
 * e.g. the WebSocket uplink) and a low-volume local "sidetone" decode so the
 * operator hears their own digital voice.
 *
 * RX path: [replayRx] / [decodeAndPlay] decodes vocoder frames and plays them
 * back, giving received audio the same P25/DMR-like character.
 *
 * Signalling:
 *  - TPT is played before the microphone is streamed.
 *  - If the channel is busy, transmission is blocked and the 314 Hz inhibit
 *    tone is played instead.
 */
class PttEngine(context: Context, private val listener: Listener) {

    enum class Light { IDLE, RX, TX, INHIBIT }

    interface Listener {
        fun onLight(light: Light)
        fun onSpeaker(unitId: String?)
        fun onBusy(busy: Boolean)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val tonePlayer = TonePlayer(appContext)

    private var encoder: Codec2Codec? = null
    private var rxDecoder: Codec2Codec? = null
    private var sidetoneDecoder: Codec2Codec? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val playLock = Any()
    private val lastTxFrames: MutableList<ByteArray> =
        Collections.synchronizedList(ArrayList())

    @Volatile private var capturing = false
    @Volatile private var rxPlaying = false
    @Volatile private var channelBusy = false
    @Volatile private var visible = false

    /** Transport hook: called for every encoded TX frame. */
    @Volatile var onFrameEncoded: ((ByteArray) -> Unit)? = null

    val codecModeName: String
        get() = when (codecMode) {
            Codec2.MODE_700C -> "700C"
            Codec2.MODE_1600 -> "1600"
            Codec2.MODE_3200 -> "3200"
            Codec2.MODE_1300 -> "1300"
            else -> codecMode.toString()
        }

    @Volatile private var codecMode = Codec2.MODE_1600

    fun start(mode: Int = codecMode) {
        try {
            releaseCodecs()
            codecMode = mode
            encoder = Codec2Codec(mode)
            rxDecoder = Codec2Codec(mode)
            sidetoneDecoder = Codec2Codec(mode)
            if (encoder?.isReady != true) {
                listener.onError("Codec 2 init failed (mode=$mode)")
                return
            }
            openAudio()
            setLight(Light.IDLE)
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            listener.onError(t.message ?: "audio init failed")
        }
    }

    private fun openAudio() {
        val frameBytes = (encoder?.samplesPerFrame ?: 160) * 2
        if (audioRecord == null) {
            val minRec = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recBuf = maxOf(minRec, frameBytes * 4)
            @Suppress("DEPRECATION")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recBuf
            )
        }
        if (audioTrack == null) {
            val minTrk = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val trkBuf = maxOf(minTrk, frameBytes * 4)
            @Suppress("DEPRECATION")
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                trkBuf,
                AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
        }
    }

    /** Begin a transmission. Safe to call from any thread. */
    fun pttDown() {
        if (capturing) return
        if (channelBusy) {
            setLight(Light.INHIBIT)
            listener.onBusy(true)
            tonePlayer.playInhibit()
            Thread {
                Thread.sleep(INHIBIT_RESET_MS)
                if (!capturing && (!channelBusy || visible)) {
                    listener.onBusy(false)
                    setLight(Light.IDLE)
                }
            }.start()
            return
        }
        capturing = true
        setLight(Light.TX)
        Thread(Runnable { captureLoop() }, "ptt-tx").start()
    }

    /** End a transmission. Safe to call from any thread. */
    fun pttUp() {
        capturing = false
        setLight(Light.IDLE)
        listener.onSpeaker(null)
    }

    private fun captureLoop() {
        val enc = encoder ?: return
        val sidetone = sidetoneDecoder ?: return
        val rec = audioRecord ?: return
        val gain = SIDETONE_GAIN

        // Talk Permit Tone: wait for it to finish before opening the mic.
        val tptMs = tonePlayer.playTpt()
        var waited = 0L
        while (waited < tptMs && capturing) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            }
            waited += 20
        }
        if (!capturing) return

        val frame = enc.samplesPerFrame
        if (frame <= 0) return
        val buf = ShortArray(frame)
        synchronized(lastTxFrames) { lastTxFrames.clear() }

        try {
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            listener.onError(t.message ?: "microphone error")
            capturing = false
            setLight(Light.IDLE)
            return
        }

        while (capturing) {
            val n = rec.read(buf, 0, frame)
            if (n <= 0) continue
            val bits = enc.encode(buf)
            synchronized(lastTxFrames) {
                if (lastTxFrames.size >= MAX_STORED_FRAMES) lastTxFrames.removeAt(0)
                lastTxFrames.add(bits.copyOf())
            }
            onFrameEncoded?.invoke(bits)
            // Local sidetone so the operator hears the digitised voice.
            val pcm = sidetone.decode(bits)
            writePlayback(applyGain(pcm, gain))
        }

        try {
            rec.stop()
        } catch (_: Throwable) {
        }
    }

    /**
     * Simulate a received call: decode the most recent transmission and play
     * it back with the speaker identity shown on the APX1000 display.
     */
    fun replayRx(unitId: String?) {
        rxPlaying = true
        Thread(Runnable {
            val frames = synchronized(lastTxFrames) { lastTxFrames.toList() }
            if (frames.isEmpty()) {
                rxPlaying = false
                return@Runnable
            }
            setLight(Light.RX)
            listener.onSpeaker(unitId)
            val dec = rxDecoder ?: return@Runnable
            for (f in frames) {
                if (!rxPlaying) break
                try {
                    writePlayback(dec.decode(f))
                } catch (_: Throwable) {
                }
            }
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
            }
            listener.onSpeaker(null)
            setLight(Light.IDLE)
            rxPlaying = false
        }, "ptt-rx").start()
    }

    /** Decode and play a single remote vocoder frame (called by transport). */
    fun decodeAndPlay(bits: ByteArray) {
        val dec = rxDecoder ?: return
        try {
            if (!rxPlaying) {
                setLight(Light.RX)
            }
            writePlayback(dec.decode(bits))
        } catch (t: Throwable) {
            Log.w(TAG, "decodeAndPlay failed", t)
        }
    }

    fun stopRxPlayback() {
        rxPlaying = false
        setLight(Light.IDLE)
        listener.onSpeaker(null)
    }

    /** Called when floor control reports the channel as occupied. */
    fun setChannelBusy(busy: Boolean) {
        channelBusy = busy
        listener.onBusy(busy)
        if (busy && !capturing) setLight(Light.INHIBIT) else if (!capturing) setLight(Light.IDLE)
    }

    fun setVisible(v: Boolean) {
        visible = v
    }

    private fun setLight(light: Light) = listener.onLight(light)

    private fun writePlayback(pcm: ShortArray) {
        synchronized(playLock) {
            audioTrack?.write(pcm, 0, pcm.size)
        }
    }

    private fun applyGain(pcm: ShortArray, gain: Float): ShortArray {
        if (gain == 1.0f) return pcm
        for (i in pcm.indices) {
            pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    private fun releaseCodecs() {
        encoder?.close(); encoder = null
        rxDecoder?.close(); rxDecoder = null
        sidetoneDecoder?.close(); sidetoneDecoder = null
    }

    fun release() {
        capturing = false
        rxPlaying = false
        synchronized(lastTxFrames) { lastTxFrames.clear() }
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        audioRecord?.release(); audioRecord = null
        try {
            audioTrack?.stop()
        } catch (_: Throwable) {
        }
        audioTrack?.release(); audioTrack = null
        releaseCodecs()
        tonePlayer.release()
    }

    companion object {
        private const val TAG = "PttEngine"
        const val SAMPLE_RATE = 8000
        private const val SIDETONE_GAIN = 0.35f
        private const val MAX_STORED_FRAMES = 250
        private const val INHIBIT_RESET_MS = 400L
    }
}
