package com.webspeak.nativeclient.android;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes one speaker's Opus stream into 20 ms PCM frames.
 *
 * <p>The gateway's compatibility downlink is Opus, mono, 48 kHz, one 20 ms packet
 * per frame (the browser decodes it with WebCodecs). Android ships a software Opus
 * decoder in MediaCodec, so no NDK or third-party codec is required. Each speaker
 * owns its decoder thread and a small frame queue; the mixer consumes at most one
 * frame per tick, which keeps latency bounded the way the browser's 80 ms
 * play-ahead window does.
 */
final class OpusSpeaker {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SAMPLES = 960;
    /** Four frames ≈ 80 ms, matching the browser's bounded play-ahead window. */
    private static final int MAX_FRAMES = 4;
    private static final int MAX_PACKETS = 24;
    private static final long VOICE_HOLD_MS = 360;

    private final int clientId;
    private final SpeakingSink sink;
    private final BlockingQueue<byte[]> packets = new ArrayBlockingQueue<>(MAX_PACKETS);
    private final ArrayDeque<short[]> frames = new ArrayDeque<>();
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private short[] accumulator = new short[FRAME_SAMPLES * 8];

    private volatile boolean running = true;
    private volatile boolean failed;
    private volatile float volume = 1f;
    private volatile long lastActivityAt = System.currentTimeMillis();
    private volatile long lastVoiceAt;
    private volatile boolean speaking;
    private int accumulatorLength;
    private int inputEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private int inputChannels = 1;
    private long presentationTimeUs;
    private MediaCodec codec;
    private Thread thread;

    OpusSpeaker(int clientId, SpeakingSink sink) {
        this.clientId = clientId;
        this.sink = sink;
    }

    int clientId() {
        return clientId;
    }

    boolean isFailed() {
        return failed;
    }

    float volume() {
        return volume;
    }

    void setVolume(float value) {
        this.volume = Math.max(0f, Math.min(4f, value));
    }

    void start() {
        thread = new Thread(this::decodeLoop, "webspeak-opus-" + clientId);
        thread.setDaemon(true);
        thread.start();
    }

    void push(byte[] opus) {
        if (!running || failed) return;
        lastActivityAt = System.currentTimeMillis();
        if (!packets.offer(opus)) {
            packets.poll();
            packets.offer(opus);
        }
    }

    /** Next decoded 20 ms frame, or null when this speaker is currently silent. */
    short[] pollFrame() {
        synchronized (frames) {
            short[] frame = frames.pollFirst();
            if (frame != null) lastActivityAt = System.currentTimeMillis();
            return frame;
        }
    }

    /** Decays the speaking indicator; called once per mixer tick. */
    void updateSpeaking(long now) {
        boolean current = speaking;
        if (current && now - lastVoiceAt > VOICE_HOLD_MS) {
            speaking = false;
            sink.onSpeaking(clientId, false);
        }
    }

    void markVoice(long now) {
        lastVoiceAt = now;
        if (!speaking) {
            speaking = true;
            sink.onSpeaking(clientId, true);
        }
    }

    boolean isIdle(long now) {
        synchronized (frames) {
            return frames.isEmpty() && packets.isEmpty() && now - lastActivityAt > 15_000;
        }
    }

    boolean isSpeakingNow() {
        return speaking;
    }

    void close() {
        running = false;
        Thread worker = thread;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try {
                worker.join(300);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        releaseCodec();
    }

    // ------------------------------------------------------------------ decode

    private void decodeLoop() {
        try {
            MediaCodec created = MediaCodec.createDecoderByType("audio/opus");
            MediaFormat format = MediaFormat.createAudioFormat("audio/opus", SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1_500);
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            created.configure(format, null, null, 0);
            created.start();
            codec = created;
            while (running) {
                byte[] packet = packets.poll(50, TimeUnit.MILLISECONDS);
                if (packet != null) queueInput(packet);
                drainOutput();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | java.io.IOException error) {
            if (running) {
                failed = true;
                sink.onAudioNotice("无法解码成员 " + clientId + " 的 Opus 音频：" + describe(error));
            }
        } finally {
            releaseCodec();
        }
    }

    private void queueInput(byte[] packet) {
        MediaCodec current = codec;
        if (current == null) return;
        try {
            int index = current.dequeueInputBuffer(10_000);
            if (index < 0) return;
            ByteBuffer buffer = current.getInputBuffer(index);
            if (buffer == null) return;
            buffer.clear();
            buffer.put(packet);
            current.queueInputBuffer(index, 0, packet.length, presentationTimeUs, 0);
            presentationTimeUs += 20_000;
        } catch (IllegalStateException ignored) {
            // Decoder is stopping.
        }
    }

    private void drainOutput() {
        MediaCodec current = codec;
        if (current == null) return;
        while (true) {
            int index;
            try {
                index = current.dequeueOutputBuffer(bufferInfo, 0);
            } catch (IllegalStateException error) {
                return;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat output = current.getOutputFormat();
                if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    inputEncoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                }
                if (output.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    inputChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
                continue;
            }
            if (index < 0) continue;
            try {
                if (bufferInfo.size > 0) {
                    ByteBuffer output = current.getOutputBuffer(index);
                    if (output != null) appendOutput(output, bufferInfo.offset, bufferInfo.size);
                }
            } finally {
                try {
                    current.releaseOutputBuffer(index, false);
                } catch (IllegalStateException ignored) {
                    return;
                }
            }
        }
    }

    private void appendOutput(ByteBuffer buffer, int offset, int size) {
        buffer.order(ByteOrder.nativeOrder());
        buffer.position(offset);
        buffer.limit(offset + size);
        if (inputEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            FloatBuffer floats = buffer.asFloatBuffer();
            short[] samples = new short[floats.remaining()];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                        floats.get(i) * 32767f));
            }
            appendSamples(samples, 1);
        } else if (inputEncoding == AudioFormat.ENCODING_PCM_8BIT) {
            short[] samples = new short[buffer.remaining()];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) ((buffer.get(offset + i) & 0xFF) - 128 << 8);
            }
            appendSamples(samples, 1);
        } else {
            ShortBuffer shorts = buffer.asShortBuffer();
            short[] samples = new short[shorts.remaining()];
            shorts.get(samples);
            appendSamples(samples, Math.max(1, inputChannels));
        }
    }

    private void appendSamples(short[] samples, int channels) {
        int framesToAdd = channels == 1 ? samples.length : samples.length / channels;
        if (framesToAdd <= 0) return;
        if (accumulator.length < accumulatorLength + framesToAdd) {
            int capacity = Math.max(accumulator.length * 2, accumulatorLength + framesToAdd);
            accumulator = Arrays.copyOf(accumulator, capacity);
        }
        if (channels == 1) {
            System.arraycopy(samples, 0, accumulator, accumulatorLength, framesToAdd);
        } else {
            for (int frame = 0; frame < framesToAdd; frame++) {
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    sum += samples[frame * channels + channel];
                }
                accumulator[accumulatorLength + frame] = (short) (sum / channels);
            }
        }
        accumulatorLength += framesToAdd;
        while (accumulatorLength >= FRAME_SAMPLES) {
            short[] frame = Arrays.copyOfRange(accumulator, 0, FRAME_SAMPLES);
            pushFrame(frame);
            System.arraycopy(accumulator, FRAME_SAMPLES, accumulator, 0, accumulatorLength - FRAME_SAMPLES);
            accumulatorLength -= FRAME_SAMPLES;
        }
    }

    private void pushFrame(short[] frame) {
        synchronized (frames) {
            if (frames.size() >= MAX_FRAMES) frames.removeFirst();
            frames.addLast(frame);
        }
    }

    private void releaseCodec() {
        MediaCodec current = codec;
        codec = null;
        if (current == null) return;
        try {
            current.stop();
        } catch (IllegalStateException ignored) {
            // Already stopped.
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
            // Already released.
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
