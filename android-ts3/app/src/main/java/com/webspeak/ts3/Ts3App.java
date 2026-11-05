package com.webspeak.ts3;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.webspeak.ts3.core.Ts3Session;
import com.webspeak.ts3.core.VoiceEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-wide direct TS3 session and audio resources. */
public final class Ts3App extends Application {
    private static Ts3App instance;

    private Handler mainHandler;
    private AndroidAudioEngine audio;
    private VoiceEngine voice;
    private Ts3Session session;
    private final Map<Integer, String> channelPasswords = new ConcurrentHashMap<>();

    public static Ts3App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        audio = new AndroidAudioEngine(this);
        voice = new VoiceEngine(audio);
        audio.setVoiceEngine(voice);
        session = new Ts3Session(command -> mainHandler.post(command), voice);
    }

    public Ts3Session session() {
        return session;
    }

    public VoiceEngine voice() {
        return voice;
    }

    public AndroidAudioEngine audio() {
        return audio;
    }

    public Handler mainHandler() {
        return mainHandler;
    }

    /** Channel passwords are remembered for this app process, never persisted. */
    public String channelPassword(int channelId) {
        String password = channelPasswords.get(channelId);
        return password == null ? "" : password;
    }

    public void rememberChannelPassword(int channelId, String password) {
        if (password != null && !password.isEmpty()) {
            channelPasswords.put(channelId, password);
        }
    }

    public void forgetChannelPassword(int channelId) {
        channelPasswords.remove(channelId);
    }

    public void shutdown() {
        try {
            session.disconnect();
        } catch (Throwable ignored) {
        }
        audio.close();
    }
}
