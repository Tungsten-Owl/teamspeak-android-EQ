package com.webspeak.ts3;

import android.content.Context;
import android.graphics.Color;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.webspeak.ts3.core.MemberInfo;

import java.util.ArrayList;
import java.util.List;

/** Channel tree with each channel's members and avatars nested underneath. */
final class ChannelAdapter extends BaseAdapter {
    private static final int TYPE_CHANNEL = 0;
    private static final int TYPE_MEMBER = 1;

    private final LayoutInflater inflater;
    private final Context context;
    private final AvatarRepository avatars;
    private final List<ChannelRow> rows = new ArrayList<>();
    private int selfId;

    ChannelAdapter(Context context, AvatarRepository avatars) {
        this.context = context;
        this.avatars = avatars;
        inflater = LayoutInflater.from(context);
    }

    void setRows(List<ChannelRow> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    void setSelfId(int selfId) {
        this.selfId = selfId;
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public ChannelRow getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        ChannelRow row = rows.get(position);
        return row.isMember() ? row.member.id : row.channel.id;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).isMember() ? TYPE_MEMBER : TYPE_CHANNEL;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ChannelRow row = rows.get(position);
        if (row.isMember()) return memberView(row.member, convertView, parent);
        return channelView(row, convertView, parent);
    }

    private View channelView(ChannelRow row, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_channel, parent, false);
        TextView name = view.findViewById(R.id.channel_name);
        TextView meta = view.findViewById(R.id.channel_meta);
        String raw = row.channel.name.isEmpty() ? ("#" + row.channel.id) : row.channel.name;
        boolean spacer = row.channel.isSpacer();
        if (row.channel.password) raw = "🔒 " + raw;
        if (row.channel.isDefault && !spacer) raw = "* " + raw;
        CharSequence formatted = ChannelNameFormatter.format(raw, row.depth);
        name.setText(formatted);
        name.setTextColor(spacer ? Color.GRAY : Color.parseColor("#E8EDF7"));
        name.setMovementMethod(hasLink(formatted) ? LinkMovementMethod.getInstance() : null);
        StringBuilder info = new StringBuilder();
        if (!spacer) {
            info.append(row.channel.memberCount);
            if (row.channel.password) {
                info.append(" · ").append(context.getString(R.string.channel_locked));
            }
        }
        meta.setText(info.toString());
        return view;
    }

    private View memberView(MemberInfo member, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.row_channel_member, parent, false);
        }
        ImageView avatar = view.findViewById(R.id.channel_member_avatar);
        TextView name = view.findViewById(R.id.channel_member_name);
        TextView meta = view.findViewById(R.id.channel_member_meta);
        TextView self = view.findViewById(R.id.channel_member_self);
        avatar.setImageBitmap(avatars.avatarFor(member));
        name.setText(member.nickname.isEmpty()
                ? context.getString(R.string.client_fallback, member.id) : member.nickname);
        meta.setText(member.away ? R.string.member_away : R.string.member_online);
        if (member.id == selfId) self.setText(R.string.member_self);
        else self.setText("");
        return view;
    }

    private boolean hasLink(CharSequence text) {
        if (!(text instanceof Spanned)) return false;
        return ((Spanned) text).getSpans(0, text.length(), URLSpan.class).length > 0;
    }
}
