package io.quarkiverse.ssf.receiver.runtime.delivery.push;

public class SsfVerificationException extends Exception {
    public SsfVerificationException(String message) {
        super(message);
    }

    public SsfVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
