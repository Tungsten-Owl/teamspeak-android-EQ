package com.webspeak.nativeclient.core;

import java.util.Collections;
import java.util.List;

/** Immutable channel description, mirroring the gateway's {@code ChannelInfo}. */
public final class ChannelInfo {
    public final String id;
    public final String parentId;
    public final String name;
    public final String order;
    public final String description;
    public final Integer maxClients;
    public final Boolean maxClientsLimited;
    public final Boolean passwordProtected;
    public final Boolean permanent;
    /** Members reported for this channel in the last snapshot, or null when omitted. */
    public final List<Member> members;

    public ChannelInfo(String id, String parentId, String name, String order, String description,
                       Integer maxClients, Boolean maxClientsLimited, Boolean passwordProtected,
                       Boolean permanent, List<Member> members) {
        this.id = id;
        this.parentId = parentId == null ? "0" : parentId;
        this.name = name == null ? "" : name;
        this.order = order;
        this.description = description;
        this.maxClients = maxClients;
        this.maxClientsLimited = maxClientsLimited;
        this.passwordProtected = passwordProtected;
        this.permanent = permanent;
        this.members = members == null ? null : Collections.unmodifiableList(members);
    }

    public boolean isPasswordProtected() {
        return Boolean.TRUE.equals(passwordProtected);
    }

    public boolean isFull() {
        if (members == null || maxClients == null || !Boolean.TRUE.equals(maxClientsLimited)) return false;
        return members.size() >= maxClients;
    }
}
