package com.webspeak.nativeclient.android;

import com.webspeak.nativeclient.R;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Session events and local notices. */
final class EventAdapter extends BaseAdapter {

    static final class Line {
        final String head;
        final String text;
        final boolean warning;

        Line(String head, String text, boolean warning) {
            this.head = head;
            this.text = text;
            this.warning = warning;
        }
    }

    private final LayoutInflater inflater;
    private final List<Line> lines = new ArrayList<>();

    EventAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    void setData(List<Line> source) {
        lines.clear();
        lines.addAll(source);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return lines.size();
    }

    @Override
    public Object getItem(int position) {
        return lines.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_event, parent, false);
        Line line = lines.get(position);
        TextView head = view.findViewById(R.id.row_event_head);
        TextView text = view.findViewById(R.id.row_event_text);
        head.setText(line.head);
        text.setText(line.text);
        text.setTextColor(line.warning ? Color.parseColor("#E7B75F") : Color.parseColor("#E8F1F2"));
        return view;
    }
}
