package io.quarkiverse.ssf.receiver.runtime.stream;

/**
 * Thrown by {@link SsfStreamClient} when a transmitter-side stream operation fails —
 * e.g. the transmitter doesn't advertise a status endpoint, the call returns a
 * non-2xx response, or the response body can't be parsed.
 */
public class SsfStreamException extends RuntimeException {
    public SsfStreamException(String message) {
        super(message);
    }

    public SsfStreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
