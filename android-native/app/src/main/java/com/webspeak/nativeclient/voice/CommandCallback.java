package com.webspeak.nativeclient.voice;

/** Result of an acknowledged gateway command. Invoked on the UI dispatcher. */
public interface CommandCallback {
    void onSuccess();

    void onError(String code, String message);
}
