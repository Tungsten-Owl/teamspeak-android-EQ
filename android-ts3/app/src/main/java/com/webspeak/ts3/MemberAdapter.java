package com.webspeak.ts3;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.webspeak.ts3.core.ChannelInfo;
import com.webspeak.ts3.core.MemberInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Channel members with their current channel and voice flags. */
final class MemberAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<MemberInfo> rows = new ArrayList<>();
    private final Map<Integer, String> channelNames = new HashMap<>();
    private int selfId;

    MemberAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void setData(List<MemberInfo> members, List<ChannelInfo> channels, int selfId) {
        rows.clear();
        rows.addAll(members);
        channelNames.clear();
        for (ChannelInfo channel : channels) channelNames.put(channel.id, channel.name);
        this.selfId = selfId;
        rows.sort((left, right) -> {
            int channel = Integer.compare(left.channelId, right.channelId);
            return channel != 0 ? channel : left.nickname.compareToIgnoreCase(right.nickname);
        });
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public MemberInfo getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return rows.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_member, parent, false);
        MemberInfo member = rows.get(position);
        TextView name = view.findViewById(R.id.member_name);
        TextView meta = view.findViewById(R.id.member_meta);
        StringBuilder label = new StringBuilder(member.nickname.isEmpty()
                ? ("Client " + member.id) : member.nickname);
        if (member.id == selfId) label.append("  (you)");
        if (member.outputMuted) label.append("  [out muted]");
        if (member.inputMuted) label.append("  [in muted]");
        if (member.away) label.append("  [away]");
        if (member.prioritySpeaker) label.append("  [priority]");
        name.setText(label.toString());
        String channel = channelNames.get(member.channelId);
        if (channel == null) channel = "Channel " + member.channelId;
        String type = member.type == 1 ? "query" : "voice";
        meta.setText(channel + " · " + type + " · id " + member.id);
        return view;
    }
}
