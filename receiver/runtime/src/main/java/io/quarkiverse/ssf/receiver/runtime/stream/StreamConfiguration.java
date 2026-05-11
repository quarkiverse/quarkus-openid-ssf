package io.quarkiverse.ssf.receiver.runtime.stream;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The transmitter-side configuration of an SSF stream — SSF spec §8.1.1.
 * Returned by the {@code configuration_endpoint} for a given {@code stream_id}.
 *
 * <p>
 * Fields in the spec that are absent in the response are {@code null} (scalars),
 * empty list ({@code aud} / {@code events_*}), or {@code null} for {@code delivery}.
 */
public record StreamConfiguration(
        String streamId,
        String iss,
        List<String> aud,
        List<URI> eventsSupported,
        List<URI> eventsRequested,
        List<URI> eventsDelivered,
        Delivery delivery,
        Integer minVerificationInterval,
        Integer inactivityTimeout,
        String description) {

    /**
     * Stream delivery descriptor. Per §8.1.1 / §6.1 the spec says {@code delivery} is a
     * JSON object whose {@code method} field is a URI identifying a delivery profile
     * (e.g. {@code urn:ietf:rfc:8935} for PUSH); other fields depend on the method.
     * {@code endpointUrl} is surfaced explicitly for the common PUSH case;
     * {@code additionalParameters} carries everything else for forward compatibility.
     */
    public record Delivery(
            String method,
            URI endpointUrl,
            Map<String, Object> additionalParameters) {
    }
}
