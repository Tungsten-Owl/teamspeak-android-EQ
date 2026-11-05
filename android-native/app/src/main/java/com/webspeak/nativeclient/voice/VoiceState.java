package com.webspeak.nativeclient.voice;

/** Immutable snapshot of the session phase for the UI. */
public final class VoiceState {
    public enum Phase {
        IDLE, TICKET, CONNECTING, CONNECTED, RECONNECTING, FAILED, CLOSED
    }

    public final Phase phase;
    public final String message;
    public final int selfId;
    public final String serverName;
    public final String channelName;
    public final int reconnectAttempt;

    public VoiceState(Phase phase, String message, int selfId, String serverName, String channelName,
                      int reconnectAttempt) {
        this.phase = phase;
        this.message = message == null ? "" : message;
        this.selfId = selfId;
        this.serverName = serverName == null ? "" : serverName;
        this.channelName = channelName;
        this.reconnectAttempt = reconnectAttempt;
    }

    public static VoiceState idle() {
        return new VoiceState(Phase.IDLE, "", 0, "", null, 0);
    }

    public boolean isConnected() {
        return phase == Phase.CONNECTED;
    }

    public boolean isBusy() {
        return phase == Phase.TICKET || phase == Phase.CONNECTING;
    }

    public boolean isTerminal() {
        return phase == Phase.FAILED || phase == Phase.CLOSED;
    }
}
