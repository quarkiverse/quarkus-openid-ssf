package io.quarkiverse.ssf.receiver.runtime.event;

/**
 * Application callback for Security Event Tokens delivered to this receiver,
 * via PUSH or POLL. Provide a CDI bean implementing it to replace the default
 * {@link LoggingSsfEventHandler}.
 *
 * <p>
 * Every SET passed in has already been signature-verified, claim-checked and
 * de-duplicated by {@code jti}. The handler runs on a virtual thread (blocking
 * I/O is fine) and may be invoked concurrently.
 *
 * <p>
 * Exceptions are caught and logged. For PUSH the SET is not redelivered
 * ({@code 202} was already sent); for POLL the {@code jti} stays unacked so the
 * transmitter redelivers — but with dedup enabled (the default) the redelivery
 * is skipped and acked, i.e. each SET gets a single attempt.
 */
public interface SsfEventHandler {

    /**
     * Processes one verified SET.
     *
     * @param eventContext the verified token plus alias-resolved views; never {@code null}
     */
    void handle(SsfEventContext eventContext);
}
