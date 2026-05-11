package io.quarkiverse.ssf.receiver.runtime.metadata;

/**
 * Thrown when the SSF transmitter metadata document cannot be fetched or parsed.
 * Unchecked so callers can wrap or surface it in whatever shape suits them.
 */
public class SsfMetadataException extends RuntimeException {
    public SsfMetadataException(String message) {
        super(message);
    }

    public SsfMetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
