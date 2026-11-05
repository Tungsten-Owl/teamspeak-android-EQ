package com.webspeak.nativeclient.voice;

/**
 * Audio boundary owned by the session.
 *
 * <p>The session starts microphone capture only after the gateway reports a ready
 * TeamSpeak session and releases it on every disconnect and reconnect, which is
 * what the browser client does. The Android implementation performs PCM capture
 * and Opus decoding; tests use a fake implementation.
 */
public interface AudioBridge {

    void startCapture(PcmSink sink);

    void stopCapture();

    /** One Opus packet from another member, already stripped of the 3-byte header. */
    void playOpus(int clientId, byte[] opus);

    void stopPlayback();

    void setMuted(boolean muted);

    /** 0..4, matching the gateway's {@code setMemberVolume} bounds. */
    void setMemberVolume(int clientId, float volume);

    /** Drops per-member gains so a new session cannot inherit stale client ids. */
    void clearMemberVolumes();

    void setOutputVolume(float volume);

    /** Releases every audio resource; called when the application leaves the room. */
    void release();
}
