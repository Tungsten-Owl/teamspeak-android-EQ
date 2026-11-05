package com.webspeak.ts3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.webspeak.ts3.core.AvatarFileName;
import com.webspeak.ts3.core.ChatMessage;
import com.webspeak.ts3.core.MemberInfo;
import com.webspeak.ts3.core.SessionEvent;
import com.webspeak.ts3.core.Ts3Session;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads TeamSpeak 3/6 avatars for chat rows and caches circular bitmaps.
 *
 * <p>Avatar bytes are a channel-0 file transfer keyed by the client's UID;
 * TS6 simply has a longer (SHA-256) UID. When a client has no avatar or the
 * server refuses the download, a deterministic initial-letter avatar is used.
 */
final class AvatarRepository {
    interface AvatarSource {
        Bitmap avatarFor(ChatMessage message);
    }

    private static final String TAG = "AvatarRepository";
    private static final int MAX_BYTES = 256 * 1024;
    /** Attempts per member before the initials avatar is kept for good. */
    private static final int MAX_ATTEMPTS = 3;
    private final Context context;
    private final Ts3Session session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ts3-avatar");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Bitmap> images = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    /** Members already reported as having no avatar marker, to keep the log quiet. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private volatile Runnable onLoaded;

    AvatarRepository(Context context, Ts3Session session) {
        this.context = context.getApplicationContext();
        this.session = session;
    }

    void setOnLoaded(Runnable onLoaded) {
        this.onLoaded = onLoaded;
    }

    Bitmap avatarFor(MemberInfo member) {
        if (member == null) return fallback("", "?");
        return avatar(member.uniqueId, member.avatarKey, member.nickname, member);
    }

    Bitmap avatarFor(ChatMessage message) {
        MemberInfo member = session.getMember(message.invokerId);
        if (member != null) return avatar(member.uniqueId, member.avatarKey,
                message.invokerName, member);
        return avatar(message.invokerUid, "", message.invokerName, null);
    }

    private Bitmap avatar(String uid, String avatarKey, String name, MemberInfo member) {
        final String finalUid = uid == null ? "" : uid;
        final String finalAvatarKey = avatarKey == null ? "" : avatarKey;
        if (name == null || name.isEmpty()) name = "?";
        String cacheKey = "a:" + finalUid + ":" + finalAvatarKey;
        Bitmap cached = images.get(cacheKey);
        if (cached != null) return cached;

        boolean hasAvatarMarker = member != null
                && member.avatarKey != null && !member.avatarKey.isEmpty()
                // TS6 may answer with the boolean form of the property instead of
                // a file name, where 0 means "no avatar".
                && !"0".equals(member.avatarKey);
        // The file name is derivable from the UID alone (avatar_ plus the a-p
        // nibble encoding), and TS6 servers do not send client_flag_avatar at
        // all. Requiring that marker is what left every TS6 member on initials
        // while the desktop client showed pictures.
        String fileName = member == null ? "" : AvatarFileName.forMember(member);
        boolean canDownload = !fileName.isEmpty();
        // Without a marker the file may simply not exist, so probe once instead of
        // retrying a request the server will never satisfy.
        int maxAttempts = hasAvatarMarker ? MAX_ATTEMPTS : 1;
        int attempts = failures.getOrDefault(cacheKey, 0);
        if (!canDownload && reported.add(cacheKey)) {
            Log.d(TAG, "no avatar target for " + name + " (no UID, no marker)");
            session.postDiagnostic(SessionEvent.INFO,
                    "头像缺失：" + name + "（无 UID，无法定位头像文件）");
        }
        // A single refused or timed-out transfer used to be final: that member
        // then showed initials for the rest of the session while the first one
        // in the batch kept its picture. Retry a few times instead, spaced by a
        // redraw so the row asks again.
        if (canDownload && attempts < maxAttempts && loading.add(cacheKey)) {
            final MemberInfo target = member;
            final String targetName = name;
            final int attempt = attempts + 1;
            final int limit = maxAttempts;
            executor.execute(() -> {
                Bitmap bitmap = null;
                try {
                    bitmap = decode(session.downloadAvatar(target));
                } catch (Throwable error) {
                    Log.w(TAG, "avatar download threw for " + targetName, error);
                }
                final Bitmap result = bitmap == null ? null : circle(bitmap);
                main.post(() -> {
                    loading.remove(cacheKey);
                    if (result != null) {
                        images.put(cacheKey, result);
                        failures.remove(cacheKey);
                        Log.d(TAG, "avatar ready for " + targetName);
                        Runnable callback = onLoaded;
                        if (callback != null) callback.run();
                        return;
                    }
                    failures.put(cacheKey, attempt);
                    Log.w(TAG, "avatar failed for " + targetName
                            + " (attempt " + attempt + "/" + limit + ")");
                    session.postDiagnostic(SessionEvent.WARNING,
                            "头像下载失败：" + targetName + "（第 " + attempt + "/" + limit + " 次"
                                    + (attempt < limit ? "，将重试）" : "，已放弃）"));
                    if (attempt < limit) {
                        main.postDelayed(() -> {
                            Runnable callback = onLoaded;
                            if (callback != null) callback.run();
                        }, 1_200L);
                    }
                });
            });
        }
        return fallback(finalUid, name);
    }

    private Bitmap decode(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 160
                || bounds.outHeight / options.inSampleSize > 160) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private Bitmap circle(Bitmap source) {
        int size = (int) (40 * context.getResources().getDisplayMetrics().density);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.SRC_IN));
        Rect sourceRect = new Rect(0, 0, source.getWidth(), source.getHeight());
        RectF targetRect = new RectF(0, 0, size, size);
        canvas.drawBitmap(source, sourceRect, targetRect, paint);
        return output;
    }

    private Bitmap fallback(String uid, String name) {
        String key = "f:" + (uid.isEmpty() ? name : uid);
        Bitmap cached = images.get(key);
        if (cached != null) return cached;
        int size = (int) (40 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int hash = (uid.isEmpty() ? name : uid).hashCode();
        float hue = Math.abs(hash % 360);
        paint.setColor(Color.HSVToColor(new float[]{hue, 0.45f, 0.72f}));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(size * 0.44f);
        paint.setTextAlign(Paint.Align.CENTER);
        String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(letter, size / 2f, baseline, paint);
        images.put(key, bitmap);
        return bitmap;
    }

    void close() {
        executor.shutdownNow();
    }
}
