package com.webspeak.nativeclient.android;

import android.os.Handler;
import android.os.Looper;

import com.webspeak.nativeclient.voice.Dispatcher;

/** Forwards session callbacks to the UI thread. */
public final class MainThreadDispatcher implements Dispatcher {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void post(Runnable action) {
        handler.post(action);
    }
}
