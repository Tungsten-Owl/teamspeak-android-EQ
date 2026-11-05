package com.webspeak.nativeclient.voice;

/** Main-thread handoff for session callbacks (implemented by the Android UI layer). */
public interface Dispatcher {
    void post(Runnable action);
}
