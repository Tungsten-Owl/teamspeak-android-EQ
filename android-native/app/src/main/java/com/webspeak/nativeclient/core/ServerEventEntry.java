package com.webspeak.nativeclient.core;

/** Server log entry published by the gateway ({@code serverEvent}). */
public final class ServerEventEntry {
    public final String id;
    public final String kind;
    public final String message;
    public final long timestamp;

    public ServerEventEntry(String id, String kind, String message, long timestamp) {
        this.id = id == null ? "" : id;
        this.kind = kind == null ? "" : kind;
        this.message = message == null ? "" : message;
        this.timestamp = timestamp;
    }

    public String kindLabel() {
        switch (kind) {
            case "joined": return "加入";
            case "left": return "离开";
            case "moved": return "移动";
            case "poke": return "戳一戳";
            case "connection": return "连接";
            default: return kind.isEmpty() ? "事件" : kind;
        }
    }
}
