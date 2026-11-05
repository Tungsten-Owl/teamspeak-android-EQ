#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "rnnoise.h"

#define FRAME 480

JNIEXPORT jlong JNICALL
Java_com_webspeak_ts3_Rnnoise_nativeCreate(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    DenoiseState *state = rnnoise_create(NULL);
    return (jlong) (intptr_t) state;
}

JNIEXPORT void JNICALL
Java_com_webspeak_ts3_Rnnoise_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    if (handle != 0) rnnoise_destroy((DenoiseState *) (intptr_t) handle);
}

JNIEXPORT jfloat JNICALL
Java_com_webspeak_ts3_Rnnoise_nativeProcess(JNIEnv *env, jclass clazz,
                                             jlong handle, jshortArray samples,
                                             jint length) {
    (void) clazz;
    if (handle == 0 || samples == NULL || length <= 0) return 0.0f;
    jshort *pcm = (*env)->GetShortArrayElements(env, samples, NULL);
    if (pcm == NULL) return 0.0f;

    float input[FRAME];
    float output[FRAME];
    float maxVad = 0.0f;
    DenoiseState *state = (DenoiseState *) (intptr_t) handle;
    int offset = 0;
    while (offset + FRAME <= length) {
        for (int i = 0; i < FRAME; i++) input[i] = (float) pcm[offset + i];
        float vad = rnnoise_process_frame(state, output, input);
        if (vad > maxVad) maxVad = vad;
        for (int i = 0; i < FRAME; i++) {
            float value = output[i];
            if (value > 32767.0f) value = 32767.0f;
            if (value < -32768.0f) value = -32768.0f;
            pcm[offset + i] = (jshort) value;
        }
        offset += FRAME;
    }
    // Leave a short tail untouched rather than clicking.
    (*env)->ReleaseShortArrayElements(env, samples, pcm, 0);
    return maxVad;
}
