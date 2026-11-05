package com.webspeak.nativeclient.android;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.webspeak.nativeclient.core.Wire;
import com.webspeak.nativeclient.voice.PcmSink;

/**
 * 48 kHz mono microphone capture.
 *
 * <p>One frame is exactly 960 samples / 1,920 bytes, the compatibility path the
 * gateway encodes to Opus. AudioRecord returns partial reads, so frames are
 * assembled across reads before being handed to the session; muting stops the
 * uplink without releasing the device, which is what the browser does when the
 * microphone is muted.
 */
final class MicCapture {

    interface Callback {
        void onStopped(String message);
    }

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_BYTES = Wire.PCM_FRAME_BYTES;

    private final PcmSink sink;
    private final Callback callback;
    private volatile boolean running = true;
    private volatile boolean muted;
    private AudioRecord record;
    private Thread thread;
    private boolean released;

    MicCapture(PcmSink sink, Callback callback, boolean muted) {
        this.sink = sink;
        this.callback = callback;
        this.muted = muted;
    }

    @SuppressLint("MissingPermission")
    void start() {
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("设备不支持 48 kHz 单声道录音");
        int bufferSize = Math.max(minimum * 2, FRAME_BYTES * 4);
        AudioRecord created = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        if (created.getState() != AudioRecord.STATE_INITIALIZED) {
            created.release();
            throw new IllegalStateException("麦克风初始化失败");
        }
        record = created;
        try {
            created.startRecording();
        } catch (IllegalStateException error) {
            releaseRecord();
            throw new IllegalStateException("麦克风启动失败", error);
        }
        if (created.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            releaseRecord();
            throw new IllegalStateException("麦克风没有进入录音状态");
        }
        thread = new Thread(this::loop, "webspeak-mic");
        thread.setDaemon(true);
        thread.start();
    }

    void setMuted(boolean value) {
        muted = value;
    }

    boolean isMuted() {
        return muted;
    }

    void stop() {
        running = false;
        AudioRecord current = record;
        if (current != null) {
            try {
                current.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped.
            }
        }
        Thread worker = thread;
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        releaseRecord();
    }

    private void loop() {
        byte[] frame = new byte[FRAME_BYTES];
        int filled = 0;
        try {
            while (running) {
                AudioRecord current = record;
                if (current == null) break;
                int read = current.read(frame, filled, FRAME_BYTES - filled);
                if (read < 0) {
                    if (running) callback.onStopped("麦克风读取失败（错误码 " + read + "）");
                    break;
                }
                if (read == 0) continue;
                filled += read;
                if (filled == FRAME_BYTES) {
                    if (!muted) sink.sendPcm(frame.clone());
                    filled = 0;
                }
            }
        } catch (RuntimeException error) {
            if (running) callback.onStopped("麦克风异常：" + error.getMessage());
        } finally {
            releaseRecord();
        }
    }

    private void releaseRecord() {
        synchronized (this) {
            if (released) return;
            released = true;
        }
        AudioRecord current = record;
        record = null;
        if (current == null) return;
        try {
            current.stop();
        } catch (IllegalStateException ignored) {
            // Not recording.
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
            // Already released.
        }
    }
}
