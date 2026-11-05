package com.webspeak.nativeclient.android;

import android.content.Context;
import android.content.SharedPreferences;

/** Local form state and per-member volumes. */
public final class Settings {

    private static final String FILE = "webspeak_native";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String gateway() {
        return prefs.getString("gateway", "");
    }

    public String nickname() {
        return prefs.getString("nickname", "");
    }

    public String channel() {
        return prefs.getString("channel", "");
    }

    public String target() {
        return prefs.getString("target", "");
    }

    public String serverPassword() {
        return prefs.getString("serverPassword", "");
    }

    public String invite() {
        return prefs.getString("invite", "");
    }

    public String identity() {
        return prefs.getString("identity", "");
    }

    public boolean rememberIdentity() {
        return prefs.getBoolean("rememberIdentity", false);
    }

    public boolean trustSelfSigned() {
        return prefs.getBoolean("trustSelfSigned", false);
    }

    public boolean muted() {
        return prefs.getBoolean("muted", false);
    }

    public void saveForm(String gateway, String nickname, String channel, String target, String serverPassword,
                         String invite, String identity, boolean rememberIdentity, boolean trustSelfSigned) {
        prefs.edit()
                .putString("gateway", gateway)
                .putString("nickname", nickname)
                .putString("channel", channel)
                .putString("target", target)
                .putString("serverPassword", serverPassword)
                .putString("invite", invite)
                .putString("identity", identity)
                .putBoolean("rememberIdentity", rememberIdentity)
                .putBoolean("trustSelfSigned", trustSelfSigned)
                .apply();
    }

    public void saveMuted(boolean muted) {
        prefs.edit().putBoolean("muted", muted).apply();
    }

    public void saveIdentity(String identity) {
        prefs.edit().putString("identity", identity == null ? "" : identity).apply();
    }

    public float volume(String preferenceKey) {
        return prefs.getFloat("volume:" + preferenceKey, 1f);
    }

    public void saveVolume(String preferenceKey, float volume) {
        prefs.edit().putFloat("volume:" + preferenceKey, volume).apply();
    }
}
