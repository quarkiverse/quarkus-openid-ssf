package io.quarkiverse.ssf.receiver.runtime.stream.status;

import java.util.Locale;

/**
 * Result of an SSF stream-status request (spec §8.1.2.1). {@code status} is the
 * verbatim string the transmitter returned — use {@link #known()} to map it onto
 * the spec-defined enum, which falls back to {@link Status#UNKNOWN} for forward
 * compatibility.
 */
public record StreamStatus(String streamId, String status, String reason) {

    public Status known() {
        if (status == null)
            return Status.UNKNOWN;
        try {
            return Status.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Status.UNKNOWN;
        }
    }

    public enum Status {
        ENABLED,
        PAUSED,
        DISABLED,
        UNKNOWN
    }
}
