package io.quarkiverse.ssf.receiver.runtime.metrics;

/**
 * Metrics SPI for the SSF receiver. The {@link NoopSsfReceiverMetrics} default
 * keeps the extension functional without Micrometer; when the consumer adds
 * {@code quarkus-micrometer} (or a registry-specific extension), the deployment
 * processor registers {@code MicrometerSsfReceiverMetrics} which publishes
 * {@code ssf.receiver.*} meters to the consumer's {@code MeterRegistry}.
 *
 * <p>
 * Method names mirror the meter names so call sites read like the dashboard
 * label they produce.
 */
public interface SsfReceiverMetrics {

    // --- PUSH ----------------------------------------------------------------

    /** Reasons an inbound push can be rejected — surfaced as a Micrometer tag. */
    enum PushRejectReason {
        /** Inbound {@code Authorization} header didn't match {@code push.expected-auth-header}. */
        AUTH,
        /** Body was empty / not a parseable JWT. */
        BODY,
        /** JWT parsed but signature, claims, or audience verification failed. */
        VERIFY
    }

    /** Reasons a polled SET can fail end-to-end — surfaced as a Micrometer tag. */
    enum PollEventFailureReason {
        /** {@code SetVerifier.verify} threw — bad signature, missing claim, audience mismatch, … */
        VERIFY,
        /** {@code SsfEventHandler} threw — the SET verified but the application couldn't accept it. */
        HANDLER
    }

    /** Outcome of a poll cycle — surfaced as a Micrometer tag. */
    enum PollOutcome {
        SUCCESS,
        FAILURE
    }

    /** Which delivery path an event arrived through — surfaced as the {@code delivery} tag. */
    enum DeliverySource {
        PUSH,
        POLL
    }

    /** Push verified and dispatched to the {@code SsfEventHandler}. */
    void pushAccepted();

    /** Push rejected before reaching the handler. */
    void pushRejected(PushRejectReason reason);

    /** Handler threw on a successfully-verified push. */
    void pushHandlerError();

    // --- POLL ----------------------------------------------------------------

    /** A poll cycle finished. {@code durationNanos} measures the request → dispatch span. */
    void pollCycle(PollOutcome outcome, long durationNanos);

    /** Number of SETs the transmitter returned from the most recent poll. */
    void pollEventsReceived(int count);

    /** A polled SET was verified and the handler returned cleanly. */
    void pollEventHandled();

    /** A polled SET failed somewhere in the verify-then-dispatch chain. */
    void pollEventFailed(PollEventFailureReason reason);

    /** Number of {@code jti}s sent in the most recent poll's {@code ack} array. */
    void pollAcksSent(int count);

    // --- per-event-type ------------------------------------------------------

    /**
     * Records that a single event-type within an accepted SET was processed.
     * A SET can carry multiple types in its {@code events} map (RFC 8417 §2.2),
     * so this is called once per type per accepted SET. The {@code event} tag
     * resolves to the alias from {@code SsfEventTypeAliases} when one is
     * registered, otherwise the URI itself.
     */
    void eventProcessed(String eventTypeUri, String issuer, DeliverySource source);

    // --- jti dedup -----------------------------------------------------------

    /**
     * A SET was dropped because its {@code jti} matched a previously-processed
     * one. Tagged by delivery source so the dashboard can tell whether
     * duplicates are coming from PUSH retries or POLL replays.
     */
    void dedupSkipped(DeliverySource source);
}
