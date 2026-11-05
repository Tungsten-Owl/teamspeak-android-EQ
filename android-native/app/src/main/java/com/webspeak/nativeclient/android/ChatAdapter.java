package com.webspeak.nativeclient.android;

import com.webspeak.nativeclient.R;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.webspeak.nativeclient.core.ChatEntry;

import java.util.ArrayList;
import java.util.List;

/** Chat lines with scope, sender and time. */
final class ChatAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<ChatEntry> entries = new ArrayList<>();

    ChatAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    void setData(List<ChatEntry> source) {
        entries.clear();
        entries.addAll(source);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public Object getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_chat, parent, false);
        ChatEntry entry = entries.get(position);
        TextView head = view.findViewById(R.id.row_chat_head);
        TextView text = view.findViewById(R.id.row_chat_text);
        StringBuilder header = new StringBuilder();
        header.append('[').append(entry.scopeLabel()).append(']');
        if ("private".equals(entry.scope) && entry.conversationName != null) {
            header.append(' ').append(entry.conversationName);
        }
        header.append(" · ").append(entry.self ? "我" : entry.senderName);
        if (entry.timestamp > 0) header.append(" · ").append(Ui.time(entry.timestamp));
        head.setText(header.toString());
        text.setText(entry.text);
        text.setTextColor(entry.self ? Color.parseColor("#BDEBE4") : Color.parseColor("#E8F1F2"));
        return view;
    }
}
