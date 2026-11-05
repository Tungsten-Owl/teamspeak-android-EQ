package com.webspeak.ts3;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.webspeak.ts3.core.ChatMessage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Chat history with avatars, target and sender labels. */
final class ChatAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final Context context;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final List<ChatMessage> rows = new ArrayList<>();
    private final AvatarRepository avatars;

    ChatAdapter(Context context, AvatarRepository avatars) {
        this.context = context;
        inflater = LayoutInflater.from(context);
        this.avatars = avatars;
    }

    void add(ChatMessage message) {
        rows.add(message);
        while (rows.size() > 300) rows.remove(0);
        notifyDataSetChanged();
    }

    void clear() {
        rows.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public ChatMessage getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_chat, parent, false);
        ChatMessage message = rows.get(position);
        ImageView avatar = view.findViewById(R.id.chat_avatar);
        avatar.setImageBitmap(avatars.avatarFor(message));
        TextView line = view.findViewById(R.id.chat_line);
        String sender = message.outgoing ? context.getString(R.string.chat_you)
                : (message.invokerName.isEmpty()
                ? context.getString(R.string.client_fallback, message.invokerId)
                : message.invokerName);
        String target = message.targetMode == ChatMessage.TARGET_CLIENT
                ? " " + context.getString(R.string.chat_private)
                : message.targetMode == ChatMessage.TARGET_SERVER
                ? " " + context.getString(R.string.chat_server) : "";
        line.setText(time.format(new Date(message.timestamp)) + "  " + sender + target + ": " + message.message);
        line.setTextColor(message.outgoing ? Color.parseColor("#8fc7ff") : Color.parseColor("#e8eef4"));
        return view;
    }
}
