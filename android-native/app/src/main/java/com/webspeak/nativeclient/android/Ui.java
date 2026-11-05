package com.webspeak.nativeclient.android;

import android.content.Context;
import android.util.TypedValue;
import android.widget.Toast;

import com.webspeak.nativeclient.core.Member;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small presentation helpers shared by the two activities. */
final class Ui {

    private Ui() {
    }

    static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    static void toast(Context context, String message) {
        if (message == null || message.isEmpty()) return;
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    static String time(long timestamp) {
        if (timestamp <= 0) return "";
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(timestamp));
    }

    static String memberFlags(Member member) {
        StringBuilder builder = new StringBuilder();
        if (member.self) builder.append("我");
        if (member.inputMuted) append(builder, "输入静音");
        if (member.outputMuted) append(builder, "已静音");
        if (member.away) append(builder, "离开");
        if (member.channelCommander) append(builder, "频道指挥");
        if (member.uid != null && !member.uid.isEmpty()) append(builder, "UID " + shortUid(member.uid));
        return builder.toString();
    }

    static String shortUid(String uid) {
        return uid.length() <= 8 ? uid : uid.substring(0, 8);
    }

    private static void append(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append(" · ");
        builder.append(value);
    }
}
