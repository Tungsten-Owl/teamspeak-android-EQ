package com.webspeak.nativeclient.core;

/** Flat member list row: either a channel header or a member inside that channel. */
public final class MemberRow {
    public final ChannelInfo channel;
    public final int depth;
    public final Member member;

    private MemberRow(ChannelInfo channel, int depth, Member member) {
        this.channel = channel;
        this.depth = depth;
        this.member = member;
    }

    public static MemberRow header(ChannelInfo channel, int depth) {
        return new MemberRow(channel, depth, null);
    }

    public static MemberRow member(ChannelInfo channel, int depth, Member member) {
        return new MemberRow(channel, depth, member);
    }

    public boolean isHeader() {
        return member == null;
    }
}
