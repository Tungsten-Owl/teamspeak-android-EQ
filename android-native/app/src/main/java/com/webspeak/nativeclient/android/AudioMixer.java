package com.webspeak.nativeclient.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mixes decoded speakers into one 48 kHz mono output.
 *
 * <p>Every 20 ms tick takes at most one frame from each speaker, applies that
 * member's local volume, saturates the sum and writes it to a streaming
 * AudioTrack. Blocking writes pace the loop to real time. When nobody has sent
 * audio for a moment the track is paused and the loop sleeps until the next
 * packet, which keeps an idle room from holding a wake lock.
 */
final class AudioMixer implements Runnable {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SAMPLES = 960;
    private static final long IDLE_PAUSE_MS = 1_200;
    private static final float VOICE_THRESHOLD = 0.02f;

    private final SpeakingSink sink;
    private final Object lock = new Object();
    private final Map<Integer, OpusSpeaker> speakers = new LinkedHashMap<>();
    private final Map<Integer, Float> pendingVolumes = new HashMap<>();
    private final Map<Integer, Long> failedSpeakers = new HashMap<>();
    private final short[] mixBuffer = new short[FRAME_SAMPLES];

    private volatile boolean running = true;
    private volatile float masterVolume = 1f;
    private AudioTrack track;
    private Thread thread;
    private boolean playing;

    AudioMixer(SpeakingSink sink) {
        this.sink = sink;
    }

    boolean start() {
        int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) return false;
        AudioTrack created;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            created = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minimum, FRAME_SAMPLES * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException error) {
            return false;
        }
        if (created.getState() != AudioTrack.STATE_INITIALIZED) {
            created.release();
            return false;
        }
        track = created;
        thread = new Thread(this, "webspeak-mixer");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    void feed(int clientId, byte[] opus) {
        synchronized (lock) {
            if (!running) return;
            Long failedAt = failedSpeakers.get(clientId);
            if (failedAt != null) {
                // Retry a device that lacks Opus decoding at most once a minute
                // instead of creating a decoder per audio frame.
                if (System.currentTimeMillis() - failedAt < 60_000) return;
                failedSpeakers.remove(clientId);
            }
            OpusSpeaker speaker = speakers.get(clientId);
            if (speaker == null) {
                speaker = new OpusSpeaker(clientId, sink);
                Float volume = pendingVolumes.get(clientId);
                if (volume != null) speaker.setVolume(volume);
                speakers.put(clientId, speaker);
                speaker.start();
            }
            speaker.push(opus);
            lock.notifyAll();
        }
    }

    void setVolume(int clientId, float volume) {
        synchronized (lock) {
            pendingVolumes.put(clientId, volume);
            OpusSpeaker speaker = speakers.get(clientId);
            if (speaker != null) speaker.setVolume(volume);
        }
    }

    void setMasterVolume(float volume) {
        masterVolume = Math.max(0f, Math.min(2f, volume));
    }

    boolean isSpeaking(int clientId) {
        synchronized (lock) {
            OpusSpeaker speaker = speakers.get(clientId);
            return speaker != null && !speaker.isFailed() && speaker.isSpeakingNow();
        }
    }

    void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        AudioTrack current = track;
        if (current != null) {
            try {
                current.pause();
            } catch (IllegalStateException ignored) {
                // Track is not playing.
            }
            try {
                current.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped.
            }
        }
        Thread worker = thread;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try {
                worker.join(400);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        long lastAudioAt = System.currentTimeMillis();
        try {
            while (running) {
                List<OpusSpeaker> snapshot;
                synchronized (lock) {
                    snapshot = new ArrayList<>(speakers.values());
                }
                Arrays.fill(mixBuffer, (short) 0);
                boolean any = false;
                long now = System.currentTimeMillis();
                for (OpusSpeaker speaker : snapshot) {
                    if (speaker.isFailed()) {
                        removeSpeaker(speaker);
                        continue;
                    }
                    short[] frame = speaker.pollFrame();
                    if (frame != null) {
                        any = true;
                        lastAudioAt = now;
                        mix(frame, masterVolume * speaker.volume());
                        if (rms(frame) > VOICE_THRESHOLD) speaker.markVoice(now);
                    }
                    speaker.updateSpeaking(now);
                    if (speaker.isIdle(now)) removeSpeaker(speaker);
                }
                if (!any && now - lastAudioAt > IDLE_PAUSE_MS) {
                    if (playing) {
                        pauseTrack();
                        playing = false;
                    }
                    synchronized (lock) {
                        lock.wait(150);
                    }
                    continue;
                }
                if (!playing) {
                    if (!playTrack()) break;
                    playing = true;
                }
                int written = track.write(mixBuffer, 0, FRAME_SAMPLES);
                if (written < 0) {
                    sink.onAudioNotice("音频输出失败（错误码 " + written + "）");
                    break;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            shutdown();
        }
    }

    private void mix(short[] frame, float gain) {
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            int value = mixBuffer[i] + (int) (frame[i] * gain);
            if (value > Short.MAX_VALUE) value = Short.MAX_VALUE;
            else if (value < Short.MIN_VALUE) value = Short.MIN_VALUE;
            mixBuffer[i] = (short) value;
        }
    }

    private static float rms(short[] frame) {
        double sum = 0;
        for (short sample : frame) {
            double value = sample / 32768d;
            sum += value * value;
        }
        return (float) Math.sqrt(sum / frame.length);
    }

    private boolean playTrack() {
        AudioTrack current = track;
        if (current == null) return false;
        try {
            current.play();
            return true;
        } catch (IllegalStateException error) {
            sink.onAudioNotice("扬声器不可用：" + error.getMessage());
            return false;
        }
    }

    private void pauseTrack() {
        AudioTrack current = track;
        if (current == null) return;
        try {
            current.pause();
        } catch (IllegalStateException ignored) {
            // Already paused or released.
        }
    }

    private void removeSpeaker(OpusSpeaker speaker) {
        boolean removed;
        synchronized (lock) {
            removed = speakers.remove(speaker.clientId()) == speaker;
            if (removed && speaker.isFailed()) {
                failedSpeakers.put(speaker.clientId(), System.currentTimeMillis());
            }
            lock.notifyAll();
        }
        if (removed) {
            speaker.close();
            sink.onSpeaking(speaker.clientId(), false);
        }
    }

    private void shutdown() {
        List<OpusSpeaker> remaining;
        synchronized (lock) {
            running = false;
            remaining = new ArrayList<>(speakers.values());
            speakers.clear();
            lock.notifyAll();
        }
        for (OpusSpeaker speaker : remaining) speaker.close();
        AudioTrack current = track;
        track = null;
        if (current != null) {
            try {
                current.pause();
            } catch (IllegalStateException ignored) {
                // Not playing.
            }
            try {
                current.flush();
            } catch (IllegalStateException ignored) {
                // Not playing.
            }
            try {
                current.release();
            } catch (RuntimeException ignored) {
                // Already released.
            }
        }
    }
}
