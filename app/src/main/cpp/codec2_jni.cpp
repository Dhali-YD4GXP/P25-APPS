// JNI bridge exposing the Codec 2 vocoder to Kotlin.
// Package: com.p25.apx1000.audio.Codec2
#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "codec2.h"

#define LOG_TAG "Codec2JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_p25_apx1000_audio_Codec2_nativeCreate(JNIEnv *env, jclass clazz, jint mode) {
    (void) env; (void) clazz;
    struct CODEC2 *state = codec2_create((int) mode);
    if (state == NULL) {
        LOGE("codec2_create failed for mode %d", (int) mode);
        return 0;
    }
    LOGI("codec2 mode %d: nsam=%d bits=%d bytes=%d",
         (int) mode,
         codec2_samples_per_frame(state),
         codec2_bits_per_frame(state),
         codec2_bytes_per_frame(state));
    return (jlong) (intptr_t) state;
}

JNIEXPORT void JNICALL
Java_com_p25_apx1000_audio_Codec2_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle != 0) {
        codec2_destroy((struct CODEC2 *) (intptr_t) handle);
    }
}

JNIEXPORT jint JNICALL
Java_com_p25_apx1000_audio_Codec2_nativeSamplesPerFrame(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return 0;
    return codec2_samples_per_frame((struct CODEC2 *) (intptr_t) handle);
}

JNIEXPORT jint JNICALL
Java_com_p25_apx1000_audio_Codec2_nativeBitsPerFrame(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return 0;
    return codec2_bits_per_frame((struct CODEC2 *) (intptr_t) handle);
}

JNIEXPORT jint JNICALL
Java_com_p25_apx1000_audio_Codec2_nativeBytesPerFrame(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return 0;
    return codec2_bytes_per_frame((struct CODEC2 *) (intptr_t) handle);
}

// Encode one frame of 16-bit PCM speech into packed vocoder bits.
// Returns the number of bytes written, or -1 on error.
JNIEXPORT jint JNICALL
Java_com_p25_apx1000_audio_Codec2_nativeEncode(JNIEnv *env, jclass clazz, jlong handle,
                                               jshortArray speech, jbyteArray out) {
    (void) clazz;
    if (handle == 0 || speech == nullptr || out == nullptr) return -1;

    struct CODEC2 *state = (struct CODEC2 *) (intptr_t) handle;
    const int nsam = codec2_samples_per_frame(state);
    const int nbytes = codec2_bytes_per_frame(state);

    if (env->GetArrayLength(speech) < nsam) {
        LOGE("encode: speech buffer too small");
        return -1;
    }
    if (env->GetArrayLength(out) < nbytes) {
        LOGE("encode: output buffer too small");
        return -1;
    }

    jshort *samples = env->GetShortArrayElements(speech, nullptr);
    jbyte *bits = env->GetByteArrayElements(out, nullptr);
    if (samples == nullptr || bits == nullptr) {
        if (samples != nullptr) env->ReleaseShortArrayElements(speech, samples, JNI_ABORT);
        if (bits != nullptr) env->ReleaseByteArrayElements(out, bits, JNI_ABORT);
        return -1;
    }

    codec2_encode(state, (unsigned char *) bits, (short *) samples);

    env->ReleaseShortArrayElements(speech, samples, JNI_ABORT);
    env->ReleaseByteArrayElements(out, bits, 0);
    return nbytes;
}

// Decode packed vocoder bits into one frame of 16-bit PCM speech.
// Returns the number of samples written, or -1 on error.
JNIEXPORT jint JNICALL
Java_com_p25_apx1000_audio_Codec2_nativeDecode(JNIEnv *env, jclass clazz, jlong handle,
                                               jbyteArray bits, jshortArray speech) {
    (void) clazz;
    if (handle == 0 || bits == nullptr || speech == nullptr) return -1;

    struct CODEC2 *state = (struct CODEC2 *) (intptr_t) handle;
    const int nsam = codec2_samples_per_frame(state);
    const int nbytes = codec2_bytes_per_frame(state);

    if (env->GetArrayLength(bits) < nbytes) {
        LOGE("decode: bit buffer too small");
        return -1;
    }
    if (env->GetArrayLength(speech) < nsam) {
        LOGE("decode: speech buffer too small");
        return -1;
    }

    jbyte *in = env->GetByteArrayElements(bits, nullptr);
    jshort *out = env->GetShortArrayElements(speech, nullptr);
    if (in == nullptr || out == nullptr) {
        if (in != nullptr) env->ReleaseByteArrayElements(bits, in, JNI_ABORT);
        if (out != nullptr) env->ReleaseShortArrayElements(speech, out, JNI_ABORT);
        return -1;
    }

    codec2_decode(state, (short *) out, (const unsigned char *) in);

    env->ReleaseByteArrayElements(bits, in, JNI_ABORT);
    env->ReleaseShortArrayElements(speech, out, 0);
    return nsam;
}

} // extern "C"
