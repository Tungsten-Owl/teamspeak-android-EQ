package com.webspeak.nativeclient.core;

/** Immutable directory member, mirroring the gateway's {@code ChannelMember}. */
public final class Member {
    public final int id;
    public final String nickname;
    public final String uid;
    public final boolean self;
    public final boolean away;
    public final String awayMessage;
    public final boolean inputMuted;
    public final boolean outputMuted;
    public final boolean channelCommander;

    public Member(int id, String nickname, String uid, boolean self, boolean away, String awayMessage,
                  boolean inputMuted, boolean outputMuted, boolean channelCommander) {
        this.id = id;
        this.nickname = nickname == null ? "" : nickname;
        this.uid = uid;
        this.self = self;
        this.away = away;
        this.awayMessage = awayMessage;
        this.inputMuted = inputMuted;
        this.outputMuted = outputMuted;
        this.channelCommander = channelCommander;
    }

    public Member withSelf(boolean isSelf) {
        return new Member(id, nickname, uid, isSelf, away, awayMessage, inputMuted, outputMuted, channelCommander);
    }

    public Member withId(int newId) {
        return new Member(newId, nickname, uid, self, away, awayMessage, inputMuted, outputMuted, channelCommander);
    }

    /** Stable identity for preferences: TeamSpeak UID when present, otherwise the session client id. */
    public String preferenceKey() {
        return uid != null && !uid.isEmpty() ? "uid:" + uid : "id:" + id;
    }
}
