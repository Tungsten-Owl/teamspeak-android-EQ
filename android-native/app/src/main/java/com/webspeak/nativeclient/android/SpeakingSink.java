package com.webspeak.nativeclient.android;

/**
 * Speaker state sink shared by the Opus decoders and the mixer. Implemented by
 * {@link AudioEngine}; always invoked off the main thread.
 */
interface SpeakingSink {
    void onSpeaking(int clientId, boolean speaking);

    void onAudioNotice(String message);
}
