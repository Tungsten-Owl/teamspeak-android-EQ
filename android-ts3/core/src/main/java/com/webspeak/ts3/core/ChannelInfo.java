package com.webspeak.ts3.core;

/** One channel as reported by the welcome sequence or a channel notification. */
public final class ChannelInfo {
    public int id;
    public int parentId;
    public int order;
    public String name = "";
    public String topic = "";
    public int codec;
    public int codecQuality;
    public int maxClients = -1;
    public int neededTalkPower;
    public boolean password;
    public boolean isDefault;
    public boolean permanent;

    public int memberCount;

    /** TeamSpeak spacer channels are layout labels, not joinable rooms. */
    public boolean isSpacer() {
        String value = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("[cspacer")
                || value.startsWith("[spacer")
                || value.startsWith("[rspacer")
                || value.startsWith("[lspacer");
    }
}
