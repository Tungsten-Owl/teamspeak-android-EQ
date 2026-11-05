package com.webspeak.ts3;

/**
 * Thin JNI wrapper around RNNoise (xiph/rnnoise), a lightweight recurrent
 * neural-network noise suppressor with a built-in model (about 85k weights).
 * The native library is built by app/src/main/cpp/CMakeLists.txt.
 */
final class Rnnoise {
    private static final boolean LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("ts3rnnoise");
            loaded = true;
        } catch (Throwable ignored) {
        }
        LOADED = loaded;
    }

    private long handle;

    Rnnoise() {
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

    /** Processes 48 kHz mono PCM in place and returns the RNNoise VAD probability. */
    float process(short[] samples, int length) {
        if (handle != 0 && samples != null && length > 0) {
            try {
                return nativeProcess(handle, samples, length);
            } catch (Throwable ignored) {
                // A native frame failure must never stop the microphone.
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

    private static native float nativeProcess(long handle, short[] samples, int length);
}
