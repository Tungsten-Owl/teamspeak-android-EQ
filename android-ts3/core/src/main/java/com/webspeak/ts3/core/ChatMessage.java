package com.webspeak.ts3.core;

/** One chat line, local or received. */
public final class ChatMessage {
    public static final int TARGET_SERVER = 0;
    public static final int TARGET_CLIENT = 1;
    public static final int TARGET_CHANNEL = 2;

    public final long timestamp = System.currentTimeMillis();
    public int targetMode;
    public int targetId;
    public int invokerId;
    public String invokerName = "";
    public String invokerUid = "";
    public String message = "";
    public boolean outgoing;

    public String targetLabel() {
        switch (targetMode) {
            case TARGET_CLIENT:
                return "Private";
            case TARGET_SERVER:
                return "Server";
            default:
                return "Channel";
        }
    }
}
