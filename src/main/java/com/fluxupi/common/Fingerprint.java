package com.fluxupi.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SHA-256 of a request's meaningful fields, used to tell an honest retry from
 * idempotency-key reuse.
 *
 * <p>Fields are joined with a delimiter that cannot appear in the values being
 * hashed, so {@code ("ab", "c")} and {@code ("a", "bc")} cannot collide.
 */
public final class Fingerprint {

    private static final String DELIMITER = ""; // ASCII unit separator

    private Fingerprint() {
    }

    public static String of(Object... fields) {
        String canonical = Stream.of(fields)
                .map(field -> field == null ? "" : field.toString())
                .collect(Collectors.joining(DELIMITER));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
