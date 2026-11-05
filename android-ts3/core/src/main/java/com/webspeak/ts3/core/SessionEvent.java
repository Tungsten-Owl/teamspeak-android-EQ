package com.webspeak.ts3.core;

/** A human-readable session event shown in the events tab. */
public final class SessionEvent {
    public static final int INFO = 0;
    public static final int WARNING = 1;
    public static final int ERROR = 2;

    public final long timestamp = System.currentTimeMillis();
    public final int level;
    public final String text;

    public SessionEvent(int level, String text) {
        this.level = level;
        this.text = text == null ? "" : text;
    }
}
