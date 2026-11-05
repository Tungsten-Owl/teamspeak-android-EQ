package com.webspeak.ts3;

import com.webspeak.ts3.core.ChannelInfo;
import com.webspeak.ts3.core.MemberInfo;

/** One channel row or a member nested under its channel. */
public final class ChannelRow {
    public final ChannelInfo channel;
    public final MemberInfo member;
    public final int depth;

    public ChannelRow(ChannelInfo channel, int depth) {
        this(channel, null, depth);
    }

    public ChannelRow(ChannelInfo channel, MemberInfo member, int depth) {
        this.channel = channel;
        this.member = member;
        this.depth = depth;
    }

    public boolean isMember() {
        return member != null;
    }
}
