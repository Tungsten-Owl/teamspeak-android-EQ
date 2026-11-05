package com.webspeak.ts3.core;

import java.util.Base64;

/** TeamSpeak 3/6 avatar file naming in channel 0's file area. */
public final class AvatarFileName {
    private static final String NIBBLES = "abcdefghijklmnop";

    private AvatarFileName() {
    }

    /**
     * The server stores an avatar as {@code avatar_} plus the UID bytes as
     * a-to-p nibbles: 20 bytes (40 letters) on TS3, 32 bytes (64 letters) on TS6.
     */
    public static String fromUid(String uid) {
        if (uid == null || uid.isEmpty()) return "";
        try {
            byte[] data = Base64.getDecoder().decode(uid);
            StringBuilder builder = new StringBuilder("avatar_");
            for (byte value : data) {
                builder.append(NIBBLES.charAt((value >> 4) & 0x0F));
                builder.append(NIBBLES.charAt(value & 0x0F));
            }
            return builder.toString();
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    /** Prefer the server-provided avatar name/hash, then derive it from the UID. */
    public static String forMember(MemberInfo member) {
        if (member == null) return "";
        String key = member.avatarKey == null ? "" : member.avatarKey.trim();
        if (key.startsWith("avatar_")) key = key.substring("avatar_".length());
        if (!key.isEmpty() && key.matches("[a-pA-P]{20,128}")) {
            return "avatar_" + key.toLowerCase(java.util.Locale.ROOT);
        }
        String derived = fromUid(member.uniqueId);
        if (!derived.isEmpty()) return derived;
        String hash = member.avatarHash == null ? "" : member.avatarHash.trim();
        if (hash.startsWith("avatar_")) hash = hash.substring("avatar_".length());
        if (!hash.isEmpty() && hash.matches("[a-pA-P]{20,128}")) {
            return "avatar_" + hash.toLowerCase(java.util.Locale.ROOT);
        }
        return "";
    }
}
