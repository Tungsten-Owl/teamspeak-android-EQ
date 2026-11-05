package com.webspeak.ts3;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Streaming driver for the Alibaba DFSMN denoiser (48 kHz, causal), exported to
 * ONNX by DakeQQ/Audio-Denoiser-ONNX.
 *
 * <p>The exported graph is a plain waveform-in / waveform-out block: input
 * {@code [1, 1, L]} float32 and one output of the same shape. It carries no
 * cache tensors, so a block cannot be stitched to the next one by the graph
 * itself. What makes it usable here is that the model is <em>causal</em>: every
 * output sample depends only on the past. A sliding window that always ends at
 * the hop being produced therefore sees the same history the offline export saw,
 * and its <em>last</em> hop is the one to keep:
 *
 * <pre>
 *   window k = samples [k*HOP - (W - HOP), k*HOP + HOP)
 *   output   = model(window k)[last HOP samples]   -> hop k
 * </pre>
 *
 * <p>Each output sample is produced exactly once, so no crossfade is needed and
 * no sample is blended twice. The cost of a larger window is linear in the
 * window length; the benefit is more lookback for the DFSMN memory blocks.
 *
 * <p>This is not a full streaming implementation of the model's internal state:
 * the graph is invoked on overlapping windows instead. That is cheap because the
 * model runs at roughly 1% of real time, and it avoids depending on state I/O
 * that this export does not provide.
 */
final class DfsmnDenoiser {
    /** One engine frame: 20 ms at 48 kHz. */
    private static final int HOP = 960;
    /** Lookback multiplier when the model accepts a dynamic length. */
    private static final int DEFAULT_WINDOWS = 4;
    private static final String ASSET = "models/DFSMN.onnx";
    private static final int MAX_CONSECUTIVE_FAILURES = 50;

    static boolean isAssetPresent(Context context) {
        try (InputStream stream = context.getAssets().open(ASSET)) {
            return stream.read() >= 0;
        } catch (Throwable error) {
            return false;
        }
    }

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final int window;
    private final float[] ring;
    private final float[] output;
    private boolean primed;
    private boolean closed;
    private int failures;

    private DfsmnDenoiser(OrtEnvironment environment, OrtSession session, int window) {
        this.environment = environment;
        this.session = session;
        this.window = window;
        this.ring = new float[window];
        this.output = new float[window];
    }

    /** Loads the packaged model and sizes the window from its declared input shape. */
    static DfsmnDenoiser load(Context context) throws Exception {
        byte[] model = readAsset(context);
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession session;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model, options);
        }
        int window = resolveWindow(session);
        if (window <= 0) {
            session.close();
            throw new IllegalStateException("DFSMN window is not a multiple of the hop");
        }
        return new DfsmnDenoiser(environment, session, window);
    }

    /**
     * A fixed-length export dictates the window; a dynamic one gets
     * {@link #DEFAULT_WINDOWS} hops of lookback. Either way the window must be a
     * whole number of hops so that "the last hop" is well defined.
     */
    private static int resolveWindow(OrtSession session) throws OrtException {
        int declared = 0;
        for (NodeInfo info : session.getInputInfo().values()) {
            if (!(info.getInfo() instanceof TensorInfo)) continue;
            long[] shape = ((TensorInfo) info.getInfo()).getShape();
            if (shape.length != 3) continue;
            long last = shape[2];
            if (last > 0) declared = (int) Math.min(Integer.MAX_VALUE, last);
            break;
        }
        if (declared > 0) return declared % HOP == 0 ? declared : 0;
        return DEFAULT_WINDOWS * HOP;
    }

    /** Replaces the frame in place; the caller still owns framing and silence. */
    void process(short[] samples, int length) {
        if (closed || samples == null) return;
        int frames = length / HOP;
        for (int frame = 0; frame < frames; frame++) {
            int offset = frame * HOP;
            // Slide the window and append the new hop at its tail.
            if (window > HOP) System.arraycopy(ring, HOP, ring, 0, window - HOP);
            for (int i = 0; i < HOP; i++) ring[window - HOP + i] = samples[offset + i] / 32768f;
            if (!run()) return;
            // A causal model's last hop is the one that has the full window as
            // history, so it is the only part that is kept.
            int base = window - HOP;
            for (int i = 0; i < HOP; i++) {
                int value = primed ? Math.round(output[base + i] * 32767f) : 0;
                if (value > 32767) value = 32767;
                if (value < -32768) value = -32768;
                samples[offset + i] = (short) value;
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
            // Session release must not fail the encoder shutdown.
        }
    }

    private boolean run() {
        // The input is [1, 1, window]; a fresh tensor per hop keeps the buffer
        // ownership obvious and costs one small copy.
        OnnxTensor input = null;
        try {
            input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(ring), new long[] { 1, 1, window });
            String name = session.getInputNames().iterator().next();
            Map<String, OnnxTensor> feeds = new HashMap<>(1);
            feeds.put(name, input);
            try (OrtSession.Result result = session.run(feeds)) {
                readFirstChannel(result.get(0), output);
            }
            failures = 0;
            return true;
        } catch (Throwable error) {
            if (++failures >= MAX_CONSECUTIVE_FAILURES) closed = true;
            return false;
        } finally {
            if (input != null) input.close();
        }
    }

    /** Accepts [1,1,L], [1,L] and [L] output shapes. */
    private static void readFirstChannel(OnnxValue value, float[] target) throws OrtException {
        Object raw = value.getValue();
        float[] row;
        if (raw instanceof float[][][]) {
            row = ((float[][][]) raw)[0][0];
        } else if (raw instanceof float[][]) {
            row = ((float[][]) raw)[0];
        } else if (raw instanceof float[]) {
            row = (float[]) raw;
        } else {
            throw new OrtException("unexpected output type");
        }
        System.arraycopy(row, 0, target, 0, Math.min(target.length, row.length));
    }

    private static byte[] readAsset(Context context) throws Exception {
        try (InputStream stream = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(8 * 1024 * 1024);
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        }
    }
}
