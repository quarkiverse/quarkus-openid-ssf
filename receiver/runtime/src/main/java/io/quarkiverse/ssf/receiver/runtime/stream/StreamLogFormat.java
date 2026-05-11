package io.quarkiverse.ssf.receiver.runtime.stream;

import java.net.URI;
import java.util.List;

import io.quarkiverse.ssf.receiver.runtime.event.SsfAliases;
import io.quarkiverse.ssf.receiver.runtime.stream.status.StreamStatus;

/**
 * Small helpers for formatting {@link StreamConfiguration} fields in log lines —
 * shared between {@link ReceiverManagedStreamRegistrar} and
 * {@link TransmitterManagedStreamProbe} so both produce the same vocabulary.
 */
final class StreamLogFormat {

    /** Standard delivery method URI for HTTP PUSH (RFC 8935 §6.1). */
    static final String PUSH_DELIVERY_METHOD = "urn:ietf:rfc:8935";
    /** Standard delivery method URI for HTTP POLL (RFC 8936 §6.1.2). */
    static final String POLL_DELIVERY_METHOD = "urn:ietf:rfc:8936";

    private StreamLogFormat() {
    }

    /**
     * Friendly form of {@code delivery.method} — {@code PUSH} / {@code POLL} for
     * the standard URNs, the URN itself for anything else, {@code <none>} when
     * the transmitter didn't return a delivery block.
     */
    static String describeDeliveryMethod(StreamConfiguration cfg) {
        if (cfg.delivery() == null || cfg.delivery().method() == null) {
            return "<none>";
        }
        return switch (cfg.delivery().method()) {
            case PUSH_DELIVERY_METHOD -> "PUSH";
            case POLL_DELIVERY_METHOD -> "POLL";
            default -> cfg.delivery().method();
        };
    }

    /**
     * Field name used in log lines for the delivery URL — {@code push_endpoint}
     * for RFC 8935, {@code poll_endpoint} for RFC 8936, generic {@code endpoint}
     * otherwise. Matches the SSF/RFC vocabulary so the line reads naturally.
     */
    static String endpointFieldName(StreamConfiguration cfg) {
        if (cfg.delivery() == null || cfg.delivery().method() == null) {
            return "endpoint";
        }
        return switch (cfg.delivery().method()) {
            case PUSH_DELIVERY_METHOD -> "push_endpoint";
            case POLL_DELIVERY_METHOD -> "poll_endpoint";
            default -> "endpoint";
        };
    }

    /** Endpoint URL or {@code <none>} when the transmitter didn't advertise one. */
    static Object endpointOrNone(StreamConfiguration cfg) {
        return cfg.delivery() != null && cfg.delivery().endpointUrl() != null
                ? cfg.delivery().endpointUrl()
                : "<none>";
    }

    /** {@code events_delivered} as alias names, or an empty list. */
    static List<String> eventsDelivered(StreamConfiguration cfg, SsfAliases aliases) {
        if (cfg.eventsDelivered() == null || cfg.eventsDelivered().isEmpty()) {
            return List.of();
        }
        return cfg.eventsDelivered().stream()
                .map(URI::toString)
                .map(aliases::eventTypeAlias)
                .toList();
    }

    /**
     * One-token form of a stream status — {@code enabled} / {@code paused} /
     * {@code disabled} / {@code unknown}, with {@code /<reason>} appended when
     * the transmitter supplied one. {@code null} input renders as {@code unknown}.
     */
    static String statusLabel(StreamStatus s) {
        if (s == null) {
            return "unknown";
        }
        String label = s.known().name().toLowerCase(java.util.Locale.ROOT);
        if (s.reason() != null && !s.reason().isBlank()) {
            label = label + "/" + s.reason();
        }
        return label;
    }
}
