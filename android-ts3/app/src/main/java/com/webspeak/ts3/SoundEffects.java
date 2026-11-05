package com.webspeak.ts3;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

/**
 * Short spoken prompts for channel membership changes.
 *
 * <p>The three clips are decoded once by {@link SoundPool}, so a prompt starts
 * in a couple of milliseconds instead of opening and buffering a file while the
 * call is running. They use the media usage rather than the voice-communication
 * one: prompts then follow the same route as ordinary playback and never pass
 * through the microphone's echo canceller.
 *
 * <p>{@link SoundPool} is safe to call from any thread, so the session callbacks
 * can play a prompt directly without hopping to the UI thread.
 */
final class SoundEffects {
    private static final String TAG = "SoundEffects";

    /** Relative to the media stream, so 1.0 is the loudest a prompt can be. */
    private volatile float volume = 1f;

    private final SoundPool pool;
    private final int joined;
    private final int left;
    private final int disconnected;

    SoundEffects(Context context) {
        Context app = context.getApplicationContext();
        pool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        // Priorities are equal on purpose: two clients crossing in opposite
        // directions should not let one prompt swallow the other.
        joined = pool.load(app, R.raw.user_join, 1);
        left = pool.load(app, R.raw.user_leave, 1);
        disconnected = pool.load(app, R.raw.user_disconnect, 1);
        // Decoding is asynchronous. A non-zero status here is the one failure the
        // caller can never see, because play() silently ignores an unknown id.
        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (status != 0) {
                Log.w(TAG, "clip " + sampleId + " failed to decode, status=" + status);
            }
        });
    }

    /** Sets the prompt level as a fraction of the media stream, 0 being muted. */
    void setVolume(float level) {
        volume = Math.max(0f, Math.min(1f, level));
    }

    /** "User joined your channel." */
    void memberJoined() {
        play(joined);
    }

    /** "User left your channel." — the client switched to another channel. */
    void memberLeft() {
        play(left);
    }

    /** "User disconnected from your channel." — the client left the server. */
    void memberDisconnected() {
        play(disconnected);
    }

    private void play(int sound) {
        // load() returns 0 when the resource could not be decoded; staying
        // silent is better than throwing in the middle of a voice session.
        if (sound == 0) {
            Log.w(TAG, "clip not loaded, prompt skipped");
            return;
        }
        float level = volume;
        if (level <= 0f) return;
        pool.play(sound, level, level, 1, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
