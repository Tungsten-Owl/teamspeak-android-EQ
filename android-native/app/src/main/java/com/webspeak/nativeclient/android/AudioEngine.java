package com.webspeak.nativeclient.android;

import android.os.Handler;
import android.os.Looper;

import com.webspeak.nativeclient.voice.AudioBridge;
import com.webspeak.nativeclient.voice.PcmSink;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Android audio boundary for the session: microphone capture in, Opus decode and
 * mixing out.
 *
 * <p>Audio failures are reported as non-fatal notices, matching the browser: a
 * missing Opus decoder or an occupied microphone must not tear down the voice
 * session, because chat and the directory remain usable.
 */
public final class AudioEngine implements AudioBridge, SpeakingSink {

    public interface Listener {
        void onAudioNotice(String message);

        void onSpeakingChanged();
    }

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final Map<Integer, Float> volumes = new ConcurrentHashMap<>();
    private final Set<Integer> speaking = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    private volatile boolean muted;
    private volatile float outputVolume = 1f;
    private volatile boolean playbackUnavailable;
    private MicCapture capture;
    private AudioMixer mixer;

    public AudioEngine(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void startCapture(PcmSink sink) {
        synchronized (lock) {
            if (capture != null) return;
            MicCapture created = new MicCapture(sink, this::onCaptureStopped, muted);
            try {
                created.start();
                capture = created;
            } catch (RuntimeException error) {
                created.stop();
                String message = error.getMessage();
                onAudioNotice("麦克风不可用：" + (message == null || message.isEmpty()
                        ? "请检查录音权限" : message));
            }
        }
    }

    @Override
    public void stopCapture() {
        MicCapture current;
        synchronized (lock) {
            current = capture;
            capture = null;
        }
        if (current != null) current.stop();
    }

    @Override
    public void playOpus(int clientId, byte[] opus) {
        if (playbackUnavailable) return;
        AudioMixer current;
        synchronized (lock) {
            if (mixer == null) {
                AudioMixer created = new AudioMixer(this);
                if (!created.start()) {
                    playbackUnavailable = true;
                    onAudioNotice("无法启动音频播放：设备没有可用的音频输出");
                    return;
                }
                mixer = created;
                created.setMasterVolume(outputVolume);
                for (Map.Entry<Integer, Float> entry : volumes.entrySet()) {
                    created.setVolume(entry.getKey(), entry.getValue());
                }
            }
            current = mixer;
        }
        current.feed(clientId, opus);
    }

    @Override
    public void stopPlayback() {
        AudioMixer current;
        synchronized (lock) {
            current = mixer;
            mixer = null;
        }
        if (current != null) current.stop();
        speaking.clear();
        postSpeakingChanged();
    }

    @Override
    public void setMuted(boolean value) {
        muted = value;
        MicCapture current;
        synchronized (lock) {
            current = capture;
        }
        if (current != null) current.setMuted(value);
    }

    @Override
    public void setMemberVolume(int clientId, float volume) {
        volumes.put(clientId, volume);
        AudioMixer current;
        synchronized (lock) {
            current = mixer;
        }
        if (current != null) current.setVolume(clientId, volume);
    }

    @Override
    public void clearMemberVolumes() {
        volumes.clear();
    }

    @Override
    public void setOutputVolume(float volume) {
        outputVolume = Math.max(0f, Math.min(2f, volume));
        AudioMixer current;
        synchronized (lock) {
            current = mixer;
        }
        if (current != null) current.setMasterVolume(outputVolume);
    }

    public float memberVolume(int clientId) {
        Float volume = volumes.get(clientId);
        return volume == null ? 1f : volume;
    }

    public boolean isSpeaking(int clientId) {
        return speaking.contains(clientId);
    }

    public boolean isMuted() {
        return muted;
    }

    @Override
    public void release() {
        stopCapture();
        stopPlayback();
        playbackUnavailable = false;
    }

    // -------------------------------------------------------------- SpeakingSink

    @Override
    public void onSpeaking(int clientId, boolean active) {
        if (active) speaking.add(clientId);
        else speaking.remove(clientId);
        postSpeakingChanged();
    }

    @Override
    public void onAudioNotice(String message) {
        String text = message == null ? "" : message;
        mainHandler.post(() -> listener.onAudioNotice(text));
    }

    private void onCaptureStopped(String message) {
        synchronized (lock) {
            capture = null;
        }
        if (message != null && !message.isEmpty()) onAudioNotice(message);
    }

    private void postSpeakingChanged() {
        mainHandler.post(listener::onSpeakingChanged);
    }
}
