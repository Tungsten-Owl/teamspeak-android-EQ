package com.webspeak.ts3;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Streaming wrapper for a2heng/lightweight-denoise-48k (MIT).
 *
 * <p>The exported graph is self-contained: two static inputs — {@code mix_hop}
 * {@code [1,480]} (one 10 ms hop at 48 kHz) and a flat {@code cache_in}
 * {@code [1,15176]} float32 streaming cache — returning {@code enh_hop}
 * {@code [1,480]} and the next cache. The analysis window, overlap-add and the
 * envelope are constant buffers inside the graph, so this class performs no DSP
 * at all and cannot disturb the signal it forwards.
 *
 * <p>{@code enh_hop} lags one hop, which is why a one-hop delay line is kept
 * here: the caller still gets a continuous stream, shifted by 10 ms. Feeding the
 * same hop twice, or reordering hops, corrupts the recurrent state — the cache
 * is only valid for the exact hop sequence that produced it.
 *
 * <p>This is a pure Java integration: ONNX Runtime ships a JNI-bridging AAR, so
 * the project needs no CMake target and no NDK code for it.
 */
final class PureVoxDenoiser {
    /** 10 ms at 48 kHz, the hop the model was trained and exported for. */
    private static final int HOP = 480;
    /** Flat cache length of the opset-17 export (960 + 7368 + 1440 + 800 + 4608). */
    private static final int CACHE = 15_176;
    private static final long[] HOP_SHAPE = { 1, HOP };
    private static final long[] CACHE_SHAPE = { 1, CACHE };
    /**
     * Accepted file names in priority order. The upstream export ships with a
     * versioned name, so a file dropped in without renaming is still found.
     */
    private static final String[] ASSETS = {
        "models/lightweight-denoise-48k.onnx",
        "models/purevox_denoise_202606_ep0014_op17.onnx",
    };
    /** A model that keeps failing must not allocate a tensor per hop forever. */
    private static final int MAX_CONSECUTIVE_FAILURES = 50;

    /**
     * True when the model file was packaged. Checked before the settings entry is
     * offered, so a missing model reads as "not installed" instead of as a
     * silently ignored selection.
     */
    static boolean isAssetPresent(Context context) {
        return resolveAsset(context) != null;
    }

    /** First packaged model that opens, or null when none is installed. */
    private static String resolveAsset(Context context) {
        for (String path : ASSETS) {
            try (InputStream stream = context.getAssets().open(path)) {
                if (stream.read() >= 0) return path;
            } catch (Throwable ignored) {
                // Try the next accepted name.
            }
        }
        return null;
    }

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final float[] cache = new float[CACHE];
    private final float[] hopIn = new float[HOP];
    private final float[] hopOut = new float[HOP];
    private final float[] delayed = new float[HOP];
    private final float[][] hopRows = new float[1][HOP];
    private boolean primed;
    private boolean closed;
    private int failures;

    private PureVoxDenoiser(OrtEnvironment environment, OrtSession session) {
        this.environment = environment;
        this.session = session;
    }

    /** Loads the packaged model. Throws when the asset is missing or unreadable. */
    static PureVoxDenoiser load(Context context) throws Exception {
        String path = resolveAsset(context);
        if (path == null) throw new IllegalStateException("no denoiser model in assets");
        byte[] model = readAsset(context, path);
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession session;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // One hop is 10 ms; a thread pool would cost more in wakeups than the
            // inference itself.
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model, options);
        }
        return new PureVoxDenoiser(environment, session);
    }

    /**
     * Replaces a whole number of 10 ms hops with their enhanced version, in
     * place. A trailing partial hop is left untouched rather than guessed at.
     */
    void process(short[] samples, int length) {
        if (closed || samples == null) return;
        int hops = length / HOP;
        for (int hop = 0; hop < hops; hop++) {
            int offset = hop * HOP;
            for (int i = 0; i < HOP; i++) hopIn[i] = samples[offset + i] / 32768f;
            if (!run()) return;
            for (int i = 0; i < HOP; i++) {
                int value = 0;
                if (primed) {
                    value = Math.round(delayed[i] * 32767f);
                    if (value > 32767) value = 32767;
                    if (value < -32768) value = -32768;
                }
                samples[offset + i] = (short) value;
                delayed[i] = hopOut[i];
            }
            primed = true;
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            session.close();
        } catch (Throwable ignored) {
            // Releasing the session must not fail the encoder shutdown.
        }
    }

    /** Runs one hop and refreshes {@link #hopOut} and {@link #cache}. */
    private boolean run() {
        OnnxTensor mix = null;
        OnnxTensor cacheIn = null;
        try {
            mix = OnnxTensor.createTensor(environment, FloatBuffer.wrap(hopIn), HOP_SHAPE);
            cacheIn = OnnxTensor.createTensor(environment, FloatBuffer.wrap(cache), CACHE_SHAPE);
            Map<String, OnnxTensor> feeds = new HashMap<>(2);
            feeds.put("mix_hop", mix);
            feeds.put("cache_in", cacheIn);
            try (OrtSession.Result result = session.run(feeds)) {
                readRow(result.get(0), hopOut);
                readRow(result.get(1), cache);
            }
            failures = 0;
            return true;
        } catch (Throwable error) {
            // A model failure must never stop the microphone; the caller falls
            // back to the previous hop and eventually to the other denoiser.
            if (++failures >= MAX_CONSECUTIVE_FAILURES) closed = true;
            return false;
        } finally {
            if (mix != null) mix.close();
            if (cacheIn != null) cacheIn.close();
        }
    }

    private static void readRow(OnnxValue value, float[] target) throws OrtException {
        Object raw = value.getValue();
        if (!(raw instanceof float[][])) throw new OrtException("unexpected output rank");
        float[] row = ((float[][]) raw)[0];
        System.arraycopy(row, 0, target, 0, Math.min(target.length, row.length));
    }

    private static byte[] readAsset(Context context, String path) throws Exception {
        try (InputStream stream = context.getAssets().open(path)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(10 * 1024 * 1024);
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        }
    }
}
