package io.quarkiverse.ssf.receiver.example.receivermanaged;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;

/**
 * Demo-friendly snapshot of an inbound SET. Wraps the verified {@link SsfEventToken} with:
 * <ul>
 * <li>{@code capturedAt}: local wall-clock time when the receiver accepted the SET, so
 * the demo can show end-to-end latency against the SET's {@code iat}.</li>
 * <li>{@code transmitter}: the name of the transmitter the SET came from
 * ({@code default}, or the name it is configured under).</li>
 * <li>{@code events}: the per-event-type payload map, keyed by the alias of the event
 * type (or the URI when it has none). The same data the transmitter put in the SET's
 * {@code events} claim, keyed for readability.</li>
 * </ul>
 *
 * <p>
 * Returned by {@code GET /events/recent-events} and {@code /events/latest}.
 */
public record CapturedEvent(
        Instant capturedAt,
        String jti,
        String transmitter,
        String iss,
        Instant iat,
        List<String> aud,
        String txn,
        Map<String, Object> subjectId,
        Map<String, Object> events,
        SsfEventToken raw) {

    public static CapturedEvent of(SsfEventToken token, String transmitter) {
        Map<String, Object> events = new LinkedHashMap<>();
        // Preserve insertion order so the JSON keys match what the transmitter sent.
        token.events().forEach((uri, payload) -> events.put(SsfEventTypes.aliasOf(uri), payload));
        return new CapturedEvent(
                Instant.now(),
                token.jti(),
                transmitter,
                token.iss(),
                token.iat(),
                token.aud(),
                token.txn(),
                token.subjectId(),
                Map.copyOf(events),
                token);
    }
}
