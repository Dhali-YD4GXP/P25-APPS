package com.p25.apx1000.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.util.Log
import com.p25.apx1000.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Handles the two signalling tones required by the APX1000 skin:
 *
 *  - Talk Permit Tone (TPT): played from res/raw/tpt_p25.wav via SoundPool.
 *    Microphone streaming must only start once the TPT has finished.
 *  - Talk Inhibit Tone (314 Hz, ~350 ms): generated as PCM and played through
 *    an [AudioTrack] when the channel is already busy.
 */
class TonePlayer(private val context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    @Volatile private var tptLoaded = false
    private var tptId: Int = 0
    @Volatile var tptDurationMs: Long = 300L
        private set

    init {
        soundPool.setOnLoadCompleteListener { _, _, status ->
            tptLoaded = status == 0
            if (status != 0) Log.w(TAG, "Failed to load tpt_p25.wav (status=$status)")
        }
        tptDurationMs = readWavDurationMs(R.raw.tpt_p25)
        tptId = soundPool.load(context, R.raw.tpt_p25, 1)
    }

    /** Read the duration of a PCM WAV raw resource so TX waits exactly for the TPT. */
    private fun readWavDurationMs(resId: Int): Long {
        return try {
            val b = context.resources.openRawResource(resId).use { it.readBytes() }
            if (b.size < 44) return 300L
            fun le32(o: Int): Int = (b[o].toInt() and 0xff) or
                ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or
                ((b[o + 3].toInt() and 0xff) shl 24)
            var i = 12
            var byteRate = 0
            var dataSize = 0
            while (i + 8 <= b.size) {
                val id = String(b, i, 4, Charsets.US_ASCII)
                val size = le32(i + 4)
                if (id == "fmt " && i + 20 <= b.size) {
                    byteRate = le32(i + 16)
                } else if (id == "data") {
                    dataSize = size
                    break
                }
                i += 8 + size + (size and 1)
            }
            if (byteRate > 0 && dataSize > 0) (dataSize.toLong() * 1000 / byteRate) else 300L
        } catch (_: Throwable) {
            300L
        }
    }

    /** Play the TPT. Returns the (estimated) duration in ms to wait before TX. */
    fun playTpt(): Long {
        if (tptLoaded) {
            soundPool.play(tptId, 1.0f, 1.0f, 1, 0, 1.0f)
        } else {
            playGeneratedTone(TPT_FREQ_HZ, TPT_DURATION_MS, 0.7f)
        }
        return tptDurationMs
    }

    /** Play the 314 Hz talk-inhibit tone through a dedicated AudioTrack. */
    fun playInhibit() {
        val samples = ShortArray((SAMPLE_RATE * INHIBIT_DURATION_MS / 1000))
        for (i in samples.indices) {
            samples[i] = (sin(2.0 * PI * INHIBIT_FREQ_HZ * i / SAMPLE_RATE) * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
        playPcm(samples)
    }

    fun release() {
        try {
            soundPool.release()
        } catch (_: Throwable) {
        }
    }

    private fun playGeneratedTone(freqHz: Double, durationMs: Int, gain: Float) {
        val samples = ShortArray(SAMPLE_RATE * durationMs / 1000)
        for (i in samples.indices) {
            samples[i] = (sin(2.0 * PI * freqHz * i / SAMPLE_RATE) * gain * Short.MAX_VALUE).toInt().toShort()
        }
        playPcm(samples)
    }

    private fun playPcm(samples: ShortArray) {
        @Suppress("DEPRECATION")
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            samples.size * 2,
            AudioTrack.MODE_STATIC
        )
        try {
            track.write(samples, 0, samples.size)
            track.play()
            Thread {
                Thread.sleep(samples.size.toLong() * 1000 / SAMPLE_RATE + 60)
                try {
                    track.stop()
                } catch (_: Throwable) {
                }
                track.release()
            }.start()
        } catch (t: Throwable) {
            Log.w(TAG, "playPcm failed", t)
            try {
                track.release()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "TonePlayer"
        const val SAMPLE_RATE = 8000
        private const val INHIBIT_FREQ_HZ = 314.0
        private const val INHIBIT_DURATION_MS = 350
        private const val TPT_FREQ_HZ = 1200.0
        private const val TPT_DURATION_MS = 220
    }
}
