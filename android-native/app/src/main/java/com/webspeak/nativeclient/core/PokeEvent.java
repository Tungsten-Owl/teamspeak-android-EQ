package com.webspeak.nativeclient.core;

/** Incoming poke notification. */
public final class PokeEvent {
    public final int invokerId;
    public final String invokerUid;
    public final String invokerName;
    public final String message;
    public final long timestamp;

    public PokeEvent(int invokerId, String invokerUid, String invokerName, String message, long timestamp) {
        this.invokerId = invokerId;
        this.invokerUid = invokerUid;
        this.invokerName = invokerName == null ? "" : invokerName;
        this.message = message == null ? "" : message;
        this.timestamp = timestamp;
    }
}
