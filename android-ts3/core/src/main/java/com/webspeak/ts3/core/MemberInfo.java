package com.webspeak.ts3.core;

/** One connected client, kept current from client notifications. */
public final class MemberInfo {
    public int id;
    public int databaseId;
    public int channelId;
    public int channelGroupId;
    public String nickname = "";
    public String uniqueId = "";
    public String avatarKey = "";
    public String avatarHash = "";
    public int type;
    public int[] serverGroups = new int[0];
    public boolean inputMuted;
    public boolean outputMuted;
    public boolean outputOnlyMuted;
    public boolean away;
    public boolean talking;
    public boolean prioritySpeaker;
    public boolean channelCommander;
    public boolean recording;
    public String description = "";
}
