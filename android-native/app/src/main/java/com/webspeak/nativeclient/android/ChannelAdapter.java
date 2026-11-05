package com.webspeak.nativeclient.android;

import com.webspeak.nativeclient.R;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.webspeak.nativeclient.core.ChannelInfo;
import com.webspeak.nativeclient.core.ChannelNode;
import com.webspeak.nativeclient.core.Directory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Channel tree rows; tapping a row joins that channel. */
final class ChannelAdapter extends BaseAdapter {

    static final class Row {
        final ChannelInfo channel;
        final int depth;
        final int memberCount;
        final boolean current;

        Row(ChannelInfo channel, int depth, int memberCount, boolean current) {
            this.channel = channel;
            this.depth = depth;
            this.memberCount = memberCount;
            this.current = current;
        }
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();
    private final int accent;

    ChannelAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.accent = context.getResources().getColor(R.color.ws_accent, context.getTheme());
    }

    void setData(List<ChannelNode> roots, Directory directory, String selfChannelId) {
        rows.clear();
        ArrayDeque<ChannelNode> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            ChannelNode node = pending.removeFirst();
            rows.add(new Row(node.channel, node.depth, directory.channelMemberCount(node.channel.id),
                    node.channel.id.equals(selfChannelId)));
            for (int i = node.children.size() - 1; i >= 0; i--) pending.addFirst(node.children.get(i));
        }
        notifyDataSetChanged();
    }

    Row row(int position) {
        return rows.get(position);
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Object getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_channel, parent, false);
        Row row = rows.get(position);
        TextView name = view.findViewById(R.id.row_channel_name);
        TextView meta = view.findViewById(R.id.row_channel_meta);
        name.setPadding(Ui.dp(context, 12 + row.depth * 16), name.getPaddingTop(), name.getPaddingRight(),
                name.getPaddingBottom());
        StringBuilder label = new StringBuilder();
        if (row.depth > 0) label.append("└ ");
        if (row.current) label.append("● ");
        label.append(row.channel.name);
        name.setText(label.toString());
        name.setTextColor(row.current ? accent : Color.parseColor("#E8F1F2"));
        StringBuilder details = new StringBuilder();
        details.append(row.memberCount).append(" 人");
        if (row.channel.isPasswordProtected()) details.append(" · 密码");
        if (row.current) details.append(" · 当前");
        meta.setText(details.toString());
        meta.setTextColor(row.current ? accent : Color.parseColor("#93A6AB"));
        return view;
    }
}
