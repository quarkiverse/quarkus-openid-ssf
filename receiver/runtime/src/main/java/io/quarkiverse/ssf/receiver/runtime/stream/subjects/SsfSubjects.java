package io.quarkiverse.ssf.receiver.runtime.stream.subjects;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfStreamClient;

/**
 * Helpers for building subject identifier objects (RFC 9493 / SSF) suitable for
 * {@link SsfStreamClient#addSubject(Map, Boolean)} and
 * {@link SsfStreamClient#removeSubject(Map)}.
 *
 * <p>
 * Covers the formats currently supported by the Keycloak SSF transmitter:
 * {@code iss_sub}, {@code email}, {@code opaque}, {@code complex}.
 */
public final class SsfSubjects {

    private SsfSubjects() {
    }

    /** {@code {"format": "opaque", "id": "<id>"}}. */
    public static Map<String, Object> opaque(String id) {
        return ordered(Map.entry("format", "opaque"), Map.entry("id", id));
    }

    /** {@code {"format": "email", "email": "<email>"}}. */
    public static Map<String, Object> email(String email) {
        return ordered(Map.entry("format", "email"), Map.entry("email", email));
    }

    /** {@code {"format": "iss_sub", "iss": "<iss>", "sub": "<sub>"}}. */
    public static Map<String, Object> issSub(String iss, String sub) {
        return ordered(
                Map.entry("format", "iss_sub"),
                Map.entry("iss", iss),
                Map.entry("sub", sub));
    }

    /**
     * {@code {"format": "complex", ...}} — fields are nested subject objects per
     * RFC 9493 §3.2.6 (e.g. {@code user}, {@code group}, {@code device}, {@code tenant}).
     * The {@code attributes} map is wrapped with {@code "format": "complex"} added.
     */
    public static Map<String, Object> complex(Map<String, Map<String, Object>> attributes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "complex");
        if (attributes != null) {
            attributes.forEach(out::put);
        }
        return Collections.unmodifiableMap(out);
    }

    @SafeVarargs
    private static Map<String, Object> ordered(Map.Entry<String, Object>... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) {
            out.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }
}
