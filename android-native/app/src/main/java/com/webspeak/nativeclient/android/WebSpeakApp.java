package com.webspeak.nativeclient.android;

import android.app.Application;

import com.webspeak.nativeclient.core.ChatEntry;
import com.webspeak.nativeclient.core.PokeEvent;
import com.webspeak.nativeclient.core.ServerEventEntry;
import com.webspeak.nativeclient.core.SessionConfig;
import com.webspeak.nativeclient.voice.Dispatcher;
import com.webspeak.nativeclient.voice.VoiceSession;
import com.webspeak.nativeclient.voice.VoiceState;

/**
 * Process-wide owner of the voice session. Both activities observe the same
 * session, so rotating the screen or moving from the join form to the room does
 * not disturb the socket, the audio engine or the command deadlines.
 */
public final class WebSpeakApp extends Application implements VoiceSession.Listener, AudioEngine.Listener {

    /** UI surface implemented by the foreground activity. */
    public interface UiListener {
        void onState(VoiceState state);

        void onServerInfo(String name, String welcomeMessage);

        void onDirectoryChanged();

        void onChat(ChatEntry entry);

        void onServerEvent(ServerEventEntry event);

        void onPoke(PokeEvent poke);

        void onWhisperTargets(int[] targetIds, boolean active);

        void onNotice(String message);

        void onAudioChanged();
    }

    private final Dispatcher dispatcher = new MainThreadDispatcher();
    private volatile AudioEngine audio;
    private volatile VoiceSession session;
    private volatile UiListener ui;
    private volatile String lastError = "";

    @Override
    public void onCreate() {
        super.onCreate();
        audio = new AudioEngine(this);
    }

    public synchronized VoiceSession session() {
        return session;
    }

    public AudioEngine audio() {
        return audio;
    }

    public void setUiListener(UiListener listener) {
        this.ui = listener;
    }

    /** Error text captured while no activity was listening (for the join form). */
    public String takeLastError() {
        String value = lastError;
        lastError = "";
        return value;
    }

    public synchronized void startSession(SessionConfig config) {
        VoiceSession previous = session;
        if (previous != null) previous.destroy();
        audio.release();
        lastError = "";
        VoiceSession created = new VoiceSession(config, dispatcher, audio, this);
        session = created;
        created.connect();
    }

    public synchronized void stopSession() {
        VoiceSession current = session;
        if (current != null) current.disconnect();
    }

    public synchronized void destroySession() {
        VoiceSession current = session;
        session = null;
        if (current != null) current.destroy();
        audio.release();
    }

    // ------------------------------------------------------------ VoiceSession

    @Override
    public void onState(VoiceState state) {
        if (state.phase == VoiceState.Phase.FAILED) lastError = state.message;
        UiListener listener = ui;
        if (listener != null) listener.onState(state);
    }

    @Override
    public void onServerInfo(String name, String welcomeMessage) {
        UiListener listener = ui;
        if (listener != null) listener.onServerInfo(name, welcomeMessage);
    }

    @Override
    public void onDirectoryChanged() {
        UiListener listener = ui;
        if (listener != null) listener.onDirectoryChanged();
    }

    @Override
    public void onChat(ChatEntry entry) {
        UiListener listener = ui;
        if (listener != null) listener.onChat(entry);
    }

    @Override
    public void onServerEvent(ServerEventEntry event) {
        UiListener listener = ui;
        if (listener != null) listener.onServerEvent(event);
    }

    @Override
    public void onPoke(PokeEvent poke) {
        UiListener listener = ui;
        if (listener != null) listener.onPoke(poke);
    }

    @Override
    public void onWhisperTargets(int[] targetIds, boolean active) {
        UiListener listener = ui;
        if (listener != null) listener.onWhisperTargets(targetIds, active);
    }

    @Override
    public void onNotice(String message) {
        UiListener listener = ui;
        if (listener != null) listener.onNotice(message);
    }

    // -------------------------------------------------------------- AudioEngine

    @Override
    public void onAudioNotice(String message) {
        // Audio failures are non-fatal: the room stays usable for text and listening.
        UiListener listener = ui;
        if (listener != null) listener.onNotice(message);
    }

    @Override
    public void onSpeakingChanged() {
        UiListener listener = ui;
        if (listener != null) listener.onAudioChanged();
    }
}
