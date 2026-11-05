package com.webspeak.nativeclient.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable view of the TeamSpeak directory published by the gateway.
 *
 * <p>Channel topology is projected iteratively-safe: missing parents and cycles are
 * promoted to roots, siblings are ordered by TeamSpeak's numeric order token, and
 * every channel appears once. Member placement comes from {@code channelList}
 * snapshots; {@code memberEnter} only updates identity data until the next
 * snapshot places the member, mirroring the server's directory synchronizer.
 *
 * <p>All methods are synchronized because the gateway reader thread updates the
 * directory while UI threads read it.
 */
public final class Directory {

    private static final int MAX_TREE_DEPTH = 200;

    private final Map<Integer, Member> members = new LinkedHashMap<>();
    private final Map<String, ChannelInfo> channels = new LinkedHashMap<>();
    private final Map<String, List<Member>> channelMembers = new LinkedHashMap<>();
    private int selfId;

    public synchronized void reset() {
        members.clear();
        channels.clear();
        channelMembers.clear();
    }

    public synchronized int selfId() {
        return selfId;
    }

    public synchronized void setSelfId(int id) {
        this.selfId = id;
        List<Integer> ids = new ArrayList<>(members.keySet());
        for (Integer key : ids) {
            Member member = members.get(key);
            if (member != null) members.put(key, member.withSelf(key == selfId));
        }
        for (Map.Entry<String, List<Member>> entry : channelMembers.entrySet()) {
            List<Member> list = entry.getValue();
            for (int i = 0; i < list.size(); i++) {
                Member member = list.get(i);
                list.set(i, member.withSelf(member.id == selfId));
            }
        }
    }

    /** Applies the {@code connected} member list; placement follows with channelList. */
    public synchronized void applyMembers(List<Member> snapshot) {
        members.clear();
        for (Member member : snapshot) {
            members.put(member.id, member.withSelf(member.id == selfId));
        }
    }

    public synchronized void applyChannels(List<ChannelInfo> snapshot) {
        boolean complete = true;
        for (ChannelInfo channel : snapshot) {
            if (channel.members == null) {
                complete = false;
                break;
            }
        }
        Map<Integer, Member> nextMembers = complete ? new LinkedHashMap<>() : new LinkedHashMap<>(members);
        Map<String, List<Member>> nextPlacement = new LinkedHashMap<>();
        Map<String, ChannelInfo> nextChannels = new LinkedHashMap<>();
        for (ChannelInfo channel : snapshot) {
            nextChannels.put(channel.id, channel);
            List<Member> previous = channelMembers.get(channel.id);
            List<Member> placement = new ArrayList<>();
            if (channel.members != null) {
                for (Member member : channel.members) {
                    Member normalized = member.withSelf(member.id == selfId);
                    nextMembers.put(member.id, normalized);
                    placement.add(normalized);
                }
            } else if (previous != null) {
                // Legacy snapshots may omit members; absence is unknown, not empty.
                for (Member member : previous) {
                    Member normalized = member.withSelf(member.id == selfId);
                    nextMembers.put(member.id, normalized);
                    placement.add(normalized);
                }
            }
            nextPlacement.put(channel.id, placement);
        }
        members.clear();
        members.putAll(nextMembers);
        channels.clear();
        channels.putAll(nextChannels);
        channelMembers.clear();
        channelMembers.putAll(nextPlacement);
    }

    public synchronized void memberEnter(Member member) {
        Member normalized = member.withSelf(member.id == selfId);
        members.put(member.id, normalized);
        // Update every occurrence so a member already placed in a channel keeps its
        // position; a member seen for the first time is placed by the next snapshot.
        for (List<Member> list : channelMembers.values()) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == member.id) list.set(i, normalized);
            }
        }
    }

    public synchronized Member memberLeave(int id) {
        Member removed = members.remove(id);
        for (List<Member> list : channelMembers.values()) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).id == id) list.remove(i);
            }
        }
        return removed;
    }

    public synchronized Member member(int id) {
        return members.get(id);
    }

    public synchronized ChannelInfo channel(String channelId) {
        return channels.get(channelId);
    }

    public synchronized List<Member> members() {
        return Collections.unmodifiableList(new ArrayList<>(members.values()));
    }

    public synchronized List<ChannelInfo> channels() {
        return Collections.unmodifiableList(new ArrayList<>(channels.values()));
    }

    public synchronized int channelMemberCount(String channelId) {
        List<Member> list = channelMembers.get(channelId);
        return list == null ? 0 : list.size();
    }

    public synchronized String selfChannelId() {
        for (Map.Entry<String, List<Member>> entry : channelMembers.entrySet()) {
            for (Member member : entry.getValue()) {
                if (member.id == selfId) return entry.getKey();
            }
        }
        return null;
    }

    public synchronized String selfChannelName() {
        String id = selfChannelId();
        ChannelInfo channel = id == null ? null : channels.get(id);
        return channel == null ? null : channel.name;
    }

    /** Members that arrived before the next directory snapshot placed them. */
    public synchronized List<Member> unplacedMembers() {
        Set<Integer> placed = new HashSet<>();
        for (List<Member> list : channelMembers.values()) {
            for (Member member : list) placed.add(member.id);
        }
        List<Member> result = new ArrayList<>();
        for (Member member : members.values()) {
            if (!placed.contains(member.id)) result.add(member);
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized List<ChannelNode> roots() {
        Map<String, ChannelInfo> byId = new LinkedHashMap<>(channels);
        List<ChannelInfo> roots = new ArrayList<>();
        Map<String, List<ChannelInfo>> children = new HashMap<>();
        for (ChannelInfo channel : byId.values()) {
            String parent = channel.parentId;
            boolean root = parent == null || parent.isEmpty() || "0".equals(parent)
                    || parent.equals(channel.id) || !byId.containsKey(parent) || hasCycle(channel, byId);
            if (root) roots.add(channel);
            else children.computeIfAbsent(parent, key -> new ArrayList<>()).add(channel);
        }
        sortChannels(roots);
        for (List<ChannelInfo> list : children.values()) sortChannels(list);

        List<ChannelNode> projected = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (ChannelInfo root : roots) {
            if (emitted.contains(root.id)) continue;
            projected.add(buildNode(root, 0, children, emitted));
        }
        // A channel unreachable from any root (defensive: duplicate parent chains)
        // is promoted so the directory never silently loses entries.
        for (ChannelInfo channel : byId.values()) {
            if (!emitted.contains(channel.id)) projected.add(buildNode(channel, 0, children, emitted));
        }
        return Collections.unmodifiableList(projected);
    }

    /** Flattened channel headers plus member rows, in display order. */
    public synchronized List<MemberRow> memberRows() {
        List<MemberRow> rows = new ArrayList<>();
        java.util.ArrayDeque<ChannelNode> pending = new java.util.ArrayDeque<>(roots());
        while (!pending.isEmpty()) {
            ChannelNode node = pending.removeFirst();
            rows.add(MemberRow.header(node.channel, node.depth));
            for (Member member : node.members) {
                rows.add(MemberRow.member(node.channel, node.depth + 1, member));
            }
            for (int i = node.children.size() - 1; i >= 0; i--) {
                pending.addFirst(node.children.get(i));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    private ChannelNode buildNode(ChannelInfo channel, int depth, Map<String, List<ChannelInfo>> children,
                                  Set<String> emitted) {
        emitted.add(channel.id);
        List<ChannelNode> childNodes = new ArrayList<>();
        List<Member> channelMembersList = channelMembers.get(channel.id);
        if (depth < MAX_TREE_DEPTH) {
            for (ChannelInfo child : children.getOrDefault(channel.id, Collections.emptyList())) {
                if (emitted.contains(child.id)) continue;
                childNodes.add(buildNode(child, depth + 1, children, emitted));
            }
        }
        return new ChannelNode(channel, depth, childNodes,
                channelMembersList == null ? Collections.emptyList() : new ArrayList<>(channelMembersList));
    }

    private static boolean hasCycle(ChannelInfo start, Map<String, ChannelInfo> byId) {
        Set<String> seen = new HashSet<>();
        ChannelInfo current = start;
        while (current != null) {
            if (!seen.add(current.id)) return true;
            String parent = current.parentId;
            if (parent == null || parent.isEmpty() || "0".equals(parent) || parent.equals(current.id)) return false;
            current = byId.get(parent);
        }
        return false;
    }

    private static void sortChannels(List<ChannelInfo> list) {
        list.sort((left, right) -> {
            int leftOrder = orderKey(left.order);
            int rightOrder = orderKey(right.order);
            if (leftOrder != rightOrder) return Integer.compare(leftOrder, rightOrder);
            int byName = left.name.compareToIgnoreCase(right.name);
            if (byName != 0) return byName;
            return left.id.compareTo(right.id);
        });
    }

    /** TeamSpeak order tokens are comma separated sort keys; compare the first integer. */
    private static int orderKey(String order) {
        if (order == null || order.isEmpty()) return Integer.MAX_VALUE;
        int end = 0;
        while (end < order.length() && Character.isDigit(order.charAt(end))) end++;
        if (end == 0) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(order.substring(0, end));
        } catch (NumberFormatException error) {
            return Integer.MAX_VALUE;
        }
    }
}
