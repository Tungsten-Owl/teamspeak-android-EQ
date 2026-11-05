package com.webspeak.ts3;

/**
 * The marker this client puts in front of its nickname.
 *
 * <p>The tag is not part of what anyone types: it is added when the session is
 * built, and a typed prefix is normalised away first, so it can be neither edited
 * nor doubled. It stays visible everywhere, including the lists that show names
 * coming back from the server.
 */
final class Names {

    /** Prefix sent to TeamSpeak. */
    static final String ANDROID_TAG = "[Android]";

    private Names() {
    }

    /** Server-facing name: the tag followed by the nickname the user chose. */
    static String withTag(String nickname) {
        String trimmed = nickname == null ? "" : nickname.trim();
        if (trimmed.regionMatches(true, 0, ANDROID_TAG, 0, ANDROID_TAG.length())) return trimmed;
        return ANDROID_TAG + trimmed;
    }

    /**
     * The bare nickname behind any tag the user typed. Used to normalise the input
     * field before the tag is added back, so "[Android]张三" cannot become
     * "[Android][Android]张三".
     */
    static String withoutTag(String nickname) {
        if (nickname == null) return "";
        String trimmed = nickname.trim();
        while (trimmed.regionMatches(true, 0, ANDROID_TAG, 0, ANDROID_TAG.length())) {
            trimmed = trimmed.substring(ANDROID_TAG.length()).trim();
        }
        return trimmed;
    }
}
