package com.webspeak.nativeclient.voice;

/** Receives exactly one 20 ms, 1,920-byte Int16 PCM frame per call. */
public interface PcmSink {
    void sendPcm(byte[] frame);
}
