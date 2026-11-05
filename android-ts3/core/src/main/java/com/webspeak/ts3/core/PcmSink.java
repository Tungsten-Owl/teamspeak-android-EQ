package com.webspeak.ts3.core;

/** Receives decoded PCM from the voice engine; called on a network thread. */
public interface PcmSink {
    void onPcm(int clientId, short[] samples, int length);
}
