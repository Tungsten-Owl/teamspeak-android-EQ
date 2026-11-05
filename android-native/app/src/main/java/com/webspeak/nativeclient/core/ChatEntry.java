package com.webspeak.nativeclient.core;

/** One chat line, local or remote. */
public final class ChatEntry {
    public final String scope;          // channel | server | private | system
    public final String targetId;       // channel id for channel scope, client id for private
    public final int senderId;
    public final String senderUid;
    public final String senderName;
    public final String text;
    public final long timestamp;
    public final boolean self;
    public final String conversationName;

    public ChatEntry(String scope, String targetId, int senderId, String senderUid, String senderName,
                     String text, long timestamp, boolean self, String conversationName) {
        this.scope = scope == null ? "system" : scope;
        this.targetId = targetId;
        this.senderId = senderId;
        this.senderUid = senderUid;
        this.senderName = senderName == null ? "" : senderName;
        this.text = text == null ? "" : text;
        this.timestamp = timestamp;
        this.self = self;
        this.conversationName = conversationName;
    }

    /** Short label used by the chat list. */
    public String scopeLabel() {
        if ("private".equals(scope)) return "私聊";
        if ("server".equals(scope)) return "服务器";
        if ("channel".equals(scope)) return "频道";
        return "系统";
    }
}
