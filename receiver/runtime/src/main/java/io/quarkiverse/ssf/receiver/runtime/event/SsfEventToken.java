package io.quarkiverse.ssf.receiver.runtime.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A verified inbound Security Event Token (SET) — RFC 8417 with the SSF profile additions.
 *
 * <p>
 * {@code aud}, {@code events}, {@code subjectId} ({@code sub_id}) and {@code txn} are
 * surfaced as dedicated accessors to mirror the structure emitted by the Keycloak SSF
 * transmitter. {@code aud} is always a list — even for SETs with a single audience
 * value — so receivers can do {@code aud.contains(expected)} uniformly.
 *
 * <p>
 * Fields whose source claim was absent in the SET are {@code null} for scalars,
 * an empty list for {@code aud}, and an empty map for {@code events}.
 *
 * <p>
 * {@code additionalProperties} carries any JWT claim the transmitter sent that
 * isn't modelled by a dedicated accessor — including standard JWT claims like
 * {@code exp} / {@code nbf} / {@code sub}, transmitter-specific extensions, and
 * future SSF profile fields. The map preserves insertion order from the SET and
 * is unmodifiable.
 */
public record SsfEventToken(
        String jti,
        String iss,
        Instant iat,
        List<String> aud,
        Map<String, Object> events,
        Map<String, Object> subjectId,
        String txn,
        Map<String, Object> additionalProperties) {
}
