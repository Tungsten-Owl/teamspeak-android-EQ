package com.webspeak.nativeclient.android;

import com.webspeak.nativeclient.R;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.webspeak.nativeclient.core.Member;
import com.webspeak.nativeclient.core.MemberRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Members grouped by channel, with a live speaking indicator. */
final class MemberAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final AudioEngine audio;
    private final List<MemberRow> rows = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final int accent;
    private final int transparent;

    MemberAdapter(Context context, AudioEngine audio) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.audio = audio;
        this.accent = context.getResources().getColor(R.color.ws_accent, context.getTheme());
        this.transparent = Color.TRANSPARENT;
    }

    void setData(List<MemberRow> source) {
        rows.clear();
        counts.clear();
        for (MemberRow row : source) {
            if (row.isHeader()) {
                counts.put(row.channel.id, 0);
            } else {
                Integer current = counts.get(row.channel.id);
                counts.put(row.channel.id, (current == null ? 0 : current) + 1);
            }
            rows.add(row);
        }
        notifyDataSetChanged();
    }

    MemberRow row(int position) {
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
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).isHeader() ? 0 : 1;
    }

    @Override
    public boolean isEnabled(int position) {
        return !rows.get(position).isHeader();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        MemberRow row = rows.get(position);
        if (row.isHeader()) {
            View view = convertView;
            if (view == null || view.findViewById(R.id.row_member_name) != null) {
                view = inflater.inflate(R.layout.row_channel, parent, false);
            }
            TextView name = view.findViewById(R.id.row_channel_name);
            TextView meta = view.findViewById(R.id.row_channel_meta);
            name.setPadding(Ui.dp(context, 12 + row.depth * 16), name.getPaddingTop(), name.getPaddingRight(),
                    name.getPaddingBottom());
            name.setText(row.channel.name);
            name.setTextColor(Color.parseColor("#93A6AB"));
            int count = counts.getOrDefault(row.channel.id, 0);
            meta.setText(count + " 人");
            meta.setTextColor(Color.parseColor("#5E7075"));
            return view;
        }
        View view = convertView;
        if (view == null || view.findViewById(R.id.row_member_name) == null) {
            view = inflater.inflate(R.layout.row_member, parent, false);
        }
        Member member = row.member;
        TextView dot = view.findViewById(R.id.row_member_dot);
        TextView name = view.findViewById(R.id.row_member_name);
        TextView status = view.findViewById(R.id.row_member_status);
        dot.setTextColor(audio.isSpeaking(member.id) ? accent : transparent);
        name.setPadding(Ui.dp(context, 12 + row.depth * 16), name.getPaddingTop(), name.getPaddingRight(),
                name.getPaddingBottom());
        name.setText(member.self ? member.nickname + "（我）" : member.nickname);
        String flags = Ui.memberFlags(member);
        status.setText(flags.isEmpty() ? " " : flags);
        return view;
    }
}
