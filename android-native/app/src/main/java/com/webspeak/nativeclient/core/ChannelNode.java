package com.webspeak.nativeclient.core;

import java.util.Collections;
import java.util.List;

/** One projected channel tree node, produced by {@link Directory}. */
public final class ChannelNode {
    public final ChannelInfo channel;
    public final int depth;
    public final List<ChannelNode> children;
    public final List<Member> members;

    public ChannelNode(ChannelInfo channel, int depth, List<ChannelNode> children, List<Member> members) {
        this.channel = channel;
        this.depth = depth;
        this.children = Collections.unmodifiableList(children);
        this.members = Collections.unmodifiableList(members);
    }
}
