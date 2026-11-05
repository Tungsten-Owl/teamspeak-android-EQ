package com.webspeak.ts3;

import android.graphics.Color;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.text.style.URLSpan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Renders the TeamSpeak channel-name tags used for visual layout.
 *
 * <p>Supported: {@code [cspacer]}, {@code [spacer]}, {@code [rspacer]},
 * {@code [lspacer]}, {@code [center]}, {@code [left]}, {@code [right]},
 * {@code [b]}, {@code [i]}, {@code [u]}, {@code [s]},
 * {@code [color=#rrggbb]}, {@code [size=n]}, {@code [url]...[/url]},
 * {@code [url=...]...[/url]}, {@code [img]...[/img]}, {@code [hr]},
 * {@code [br]} and {@code [*]}. Unknown tags stay visible, because they may be
 * part of the channel name rather than a formatting tag.
 */
final class ChannelNameFormatter {
    private ChannelNameFormatter() {
    }

    static CharSequence format(String raw, int depth) {
        String source = raw == null ? "" : raw;
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = 0; i < depth; i++) out.append("    ");

        Deque<OpenTag> stack = new ArrayDeque<>();
        Layout.Alignment alignment = null;
        int index = 0;
        while (index < source.length()) {
            int open = source.indexOf('[', index);
            if (open < 0) {
                out.append(source, index, source.length());
                break;
            }
            out.append(source, index, open);
            int close = source.indexOf(']', open + 1);
            if (close < 0) {
                out.append(source, open, source.length());
                break;
            }
            String tag = source.substring(open + 1, close).trim();
            String lower = tag.toLowerCase(Locale.ROOT);
            index = close + 1;

            if (lower.startsWith("/")) {
                String name = lower.substring(1);
                while (!stack.isEmpty()) {
                    OpenTag opened = stack.pop();
                    apply(out, opened);
                    if (opened.name.equals(name)) break;
                }
                continue;
            }

            if (lower.startsWith("cspacer") || lower.equals("spacer")
                    || lower.startsWith("rspacer") || lower.startsWith("lspacer")) {
                alignment = Layout.Alignment.ALIGN_CENTER;
                if (lower.startsWith("rspacer")) alignment = Layout.Alignment.ALIGN_OPPOSITE;
                if (lower.startsWith("lspacer")) alignment = Layout.Alignment.ALIGN_NORMAL;
                continue;
            }
            if (lower.equals("center")) {
                alignment = Layout.Alignment.ALIGN_CENTER;
                stack.push(new OpenTag("center", out.length(), null, null));
                continue;
            }
            if (lower.equals("left")) {
                alignment = Layout.Alignment.ALIGN_NORMAL;
                stack.push(new OpenTag("left", out.length(), null, null));
                continue;
            }
            if (lower.equals("right")) {
                alignment = Layout.Alignment.ALIGN_OPPOSITE;
                stack.push(new OpenTag("right", out.length(), null, null));
                continue;
            }
            if (lower.equals("hr")) {
                out.append("────────");
                continue;
            }
            if (lower.equals("br")) {
                out.append('\n');
                continue;
            }
            if (lower.equals("*")) {
                out.append("• ");
                continue;
            }
            if (lower.equals("img")) {
                int end = source.toLowerCase(Locale.ROOT).indexOf("[/img]", index);
                if (end < 0) {
                    out.append("🖼");
                } else {
                    out.append("🖼");
                    index = end + "[/img]".length();
                }
                continue;
            }
            if (lower.equals("b")) {
                stack.push(new OpenTag("b", out.length(), new StyleSpan(android.graphics.Typeface.BOLD), null));
                continue;
            }
            if (lower.equals("i")) {
                stack.push(new OpenTag("i", out.length(), new StyleSpan(android.graphics.Typeface.ITALIC), null));
                continue;
            }
            if (lower.equals("u")) {
                stack.push(new OpenTag("u", out.length(), new UnderlineSpan(), null));
                continue;
            }
            if (lower.equals("s")) {
                stack.push(new OpenTag("s", out.length(), new StrikethroughSpan(), null));
                continue;
            }
            if (lower.startsWith("color=")) {
                Integer color = parseColor(tag.substring(tag.indexOf('=') + 1));
                if (color != null) {
                    stack.push(new OpenTag("color", out.length(),
                            new ForegroundColorSpan(color), null));
                }
                continue;
            }
            if (lower.startsWith("size=")) {
                try {
                    float size = Float.parseFloat(tag.substring(tag.indexOf('=') + 1).trim());
                    float scale = Math.max(0.6f, Math.min(2.0f, size / 14f));
                    stack.push(new OpenTag("size", out.length(), new RelativeSizeSpan(scale), null));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            if (lower.startsWith("url=")) {
                String url = tag.substring(tag.indexOf('=') + 1).trim();
                stack.push(new OpenTag("url", out.length(), new URLSpan(url), null));
                continue;
            }
            if (lower.equals("url")) {
                stack.push(new OpenTag("url", out.length(), null, ""));
                continue;
            }

            // Unknown tag: preserve it as literal text.
            out.append(source, open, close + 1);
        }

        while (!stack.isEmpty()) apply(out, stack.pop());
        if (alignment != null && out.length() > 0) {
            out.setSpan(new AlignmentSpan.Standard(alignment), 0, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private static void apply(SpannableStringBuilder out, OpenTag opened) {
        int end = out.length();
        if (opened.start >= end) return;
        if (opened.style != null) {
            out.setSpan(opened.style, opened.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }
        if ("url".equals(opened.name) && opened.url != null) {
            String url = out.subSequence(opened.start, end).toString().trim();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                out.setSpan(new URLSpan(url), opened.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static Integer parseColor(String value) {
        try {
            String color = value.trim();
            if (color.startsWith("#")) return Color.parseColor(color);
            if (color.matches("[0-9a-fA-F]{6}")) return Color.parseColor("#" + color);
            if (color.matches("[0-9a-fA-F]{8}")) return Color.parseColor("#" + color);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final class OpenTag {
        final String name;
        final int start;
        final Object style;
        final String url;

        OpenTag(String name, int start, Object style, String url) {
            this.name = name;
            this.start = start;
            this.style = style;
            this.url = url;
        }
    }
}
