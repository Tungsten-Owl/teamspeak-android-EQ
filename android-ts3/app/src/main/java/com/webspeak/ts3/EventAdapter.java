package com.webspeak.ts3;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.webspeak.ts3.core.SessionEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Session log. */
final class EventAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final List<SessionEvent> rows = new ArrayList<>();

    EventAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void add(SessionEvent event) {
        rows.add(event);
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
    public SessionEvent getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_event, parent, false);
        SessionEvent event = rows.get(position);
        TextView line = view.findViewById(R.id.event_line);
        line.setText(time.format(new Date(event.timestamp)) + "  " + event.text);
        line.setTextColor(Color.parseColor(event.level == SessionEvent.ERROR ? "#ff8a8a"
                : event.level == SessionEvent.WARNING ? "#ffd28a" : "#8fa1b3"));
        return view;
    }
}
