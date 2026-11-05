package com.webspeak.ts3;

/**
 * JNI wrapper for DeepFilterNet3 (deep-filter-rt 0.1.1, MIT/Apache-2.0).
 * The embedded model runs at 48 kHz in 10 ms hops with a 30 ms model delay.
 * A native library is currently shipped for arm64-v8a; other ABIs keep RNNoise
 * as the available denoiser.
 */
final class DeepFilterNet {
    private static final boolean LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("ts3dfn");
            loaded = true;
        } catch (Throwable ignored) {
        }
        LOADED = loaded;
    }

    private long handle;

    DeepFilterNet() {
        if (LOADED) {
            try {
                handle = nativeCreate();
            } catch (Throwable ignored) {
                handle = 0;
            }
        }
    }

    static boolean isAvailable() {
        return LOADED;
    }

    boolean handleAvailable() {
        return handle != 0;
    }

    /** Limits maximum attenuation in dB; lower values keep more dry voice. */
    void setAttenuationLimit(float db) {
        if (handle != 0) {
            try {
                nativeSetAttenuation(handle, Math.max(0f, Math.min(100f, db)));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Post-filter beta, 0 disables it. */
    void setPostFilter(float beta) {
        if (handle != 0) {
            try {
                nativeSetPostFilter(handle, Math.max(0f, beta));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Processes 48 kHz mono PCM in place; returns the local SNR estimate. */
    float process(short[] samples, int length) {
        if (handle != 0 && samples != null && length > 0) {
            try {
                return nativeProcess(handle, samples, length);
            } catch (Throwable ignored) {
            }
        }
        return 0f;
    }

    void close() {
        long current = handle;
        handle = 0;
        if (current != 0) {
            try {
                nativeDestroy(current);
            } catch (Throwable ignored) {
            }
        }
    }

    private static native long nativeCreate();

    private static native void nativeDestroy(long handle);

    private static native void nativeSetAttenuation(long handle, float db);

    private static native void nativeSetPostFilter(long handle, float beta);

    private static native float nativeProcess(long handle, short[] samples, int length);
}
