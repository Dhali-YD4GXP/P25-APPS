package com.p25.apx1000.audio

/**
 * Low level JNI bindings to the vendored Codec 2 vocoder.
 *
 * Codec 2 is an open-source (LGPL) low bitrate speech codec. At the bitrates
 * used here (700C/1600/3200 bps) it produces the characteristic narrow,
 * synthetic "digital radio" voice of systems such as P25 (IMBE, ~4400 bps)
 * and DMR (AMBE+2, ~2400 bps).
 */
object Codec2 {

    init {
        System.loadLibrary("p25codec2")
    }

    const val MODE_3200 = 0
    const val MODE_2400 = 1
    const val MODE_1600 = 2
    const val MODE_1400 = 3
    const val MODE_1300 = 4
    const val MODE_1200 = 5
    const val MODE_700C = 8

    external fun nativeCreate(mode: Int): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeSamplesPerFrame(handle: Long): Int
    external fun nativeBitsPerFrame(handle: Long): Int
    external fun nativeBytesPerFrame(handle: Long): Int
    external fun nativeEncode(handle: Long, speech: ShortArray, out: ByteArray): Int
    external fun nativeDecode(handle: Long, bits: ByteArray, out: ShortArray): Int
}

/**
 * Stateful convenience wrapper around a single Codec 2 instance.
 * Not thread safe: use one instance per audio direction / thread.
 */
class Codec2Codec(mode: Int) : AutoCloseable {

    private var handle: Long = Codec2.nativeCreate(mode)

    val samplesPerFrame: Int = if (handle != 0L) Codec2.nativeSamplesPerFrame(handle) else 0
    val bytesPerFrame: Int = if (handle != 0L) Codec2.nativeBytesPerFrame(handle) else 0
    val bitsPerFrame: Int = if (handle != 0L) Codec2.nativeBitsPerFrame(handle) else 0

    val isReady: Boolean get() = handle != 0L

    /** Encode one frame of 16-bit PCM (length [samplesPerFrame]). */
    fun encode(speech: ShortArray): ByteArray {
        require(handle != 0L) { "Codec2 instance is not ready" }
        require(speech.size >= samplesPerFrame) { "speech must be at least $samplesPerFrame samples" }
        val out = ByteArray(bytesPerFrame)
        Codec2.nativeEncode(handle, speech, out)
        return out
    }

    /** Decode one vocoder frame into 16-bit PCM (length [samplesPerFrame]). */
    fun decode(bits: ByteArray): ShortArray {
        require(handle != 0L) { "Codec2 instance is not ready" }
        require(bits.size >= bytesPerFrame) { "bits must be at least $bytesPerFrame bytes" }
        val out = ShortArray(samplesPerFrame)
        Codec2.nativeDecode(handle, bits, out)
        return out
    }

    override fun close() {
        if (handle != 0L) {
            Codec2.nativeDestroy(handle)
            handle = 0L
        }
    }
}
