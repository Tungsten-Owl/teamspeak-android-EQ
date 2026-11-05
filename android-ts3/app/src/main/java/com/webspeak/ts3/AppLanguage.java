package com.webspeak.ts3;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/** Per-app UI language, independent of the device locale. */
final class AppLanguage {
    private static final String PREFS = "ts3";
    private static final String KEY = "language";
    private static final String DEFAULT = "zh";

    private AppLanguage() {
    }

    static String get(Context context) {
        String value = prefs(context).getString(KEY, DEFAULT);
        return "en".equals(value) ? "en" : "zh";
    }

    static void set(Context context, String language) {
        prefs(context).edit().putString(KEY, "en".equals(language) ? "en" : "zh").apply();
    }

    static Context wrap(Context base) {
        Locale locale = new Locale(get(base));
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        return base.createConfigurationContext(configuration);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
