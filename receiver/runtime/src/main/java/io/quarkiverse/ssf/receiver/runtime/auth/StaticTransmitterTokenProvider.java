package io.quarkiverse.ssf.receiver.runtime.auth;

import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;

/**
 * Sends a fixed bearer token, configured with
 * {@code quarkus.openid-ssf.receiver.transmitter-access-token}, on every call to the
 * transmitter. For transmitters that issue long-lived tokens out-of-band, such as
 * <a href="https://ssf.caep.dev">caep.dev</a>.
 */
public final class StaticTransmitterTokenProvider implements SsfTransmitterTokenProvider {

    private final String accessToken;

    public StaticTransmitterTokenProvider(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be empty");
        }
        this.accessToken = accessToken;
    }

    @Override
    public String getAccessToken() {
        return accessToken;
    }

    @Override
    public String toString() {
        return "StaticTransmitterTokenProvider";
    }
}
