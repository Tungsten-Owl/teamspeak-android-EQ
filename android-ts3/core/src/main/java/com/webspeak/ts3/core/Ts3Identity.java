package com.webspeak.ts3.core;

import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.util.Ts3Crypt;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates, parses and serializes TeamSpeak identities. */
public final class Ts3Identity {
    public static final int DEFAULT_SECURITY_LEVEL = 8;
    private static final Pattern IDENTITY_VALUE =
            Pattern.compile("identity\\s*=\\s*\"([^\"]+)\"");

    private Ts3Identity() {
    }

    public static String generate(int securityLevel) throws Exception {
        return LocalIdentity.generateNew(Math.max(0, securityLevel)).export();
    }

    public static LocalIdentity load(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return LocalIdentity.generateNew(DEFAULT_SECURITY_LEVEL);
        }
        String trimmed = text.trim();
        try {
            return LocalIdentity.read(new ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable ignored) {
            // Fall through to the raw identity value accepted by the TeamSpeak UI.
        }

        // A user may paste only the value, without the `identity="..."` wrapper.
        if (!trimmed.contains("identity")) {
            try {
                String wrapped = "identity=\"" + trimmed + "\"";
                return LocalIdentity.read(new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8)));
            } catch (Throwable ignored) {
                // Fall through to raw ASN.1 base64.
            }
        }

        Matcher matcher = IDENTITY_VALUE.matcher(trimmed);
        String value = matcher.find() ? matcher.group(1) : trimmed;
        byte[] asn = Base64.getDecoder().decode(value);
        return Ts3Crypt.loadIdentityFromAsn(asn);
    }

    public static long securityLevel(LocalIdentity identity) {
        return identity == null ? 0 : identity.getKeyOffset();
    }
}
