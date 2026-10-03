package io.quarkiverse.ssf.receiver.runtime.auth;

import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;

/**
 * Sends no {@code Authorization} header at all: the choice when neither a static token,
 * nor OAuth2 client credentials, nor {@code quarkus-oidc-client} is configured for a
 * transmitter. Enough for PUSH delivery with a public JWK Set.
 */
public final class NoopTransmitterTokenProvider implements SsfTransmitterTokenProvider {

    public static final NoopTransmitterTokenProvider INSTANCE = new NoopTransmitterTokenProvider();

    private NoopTransmitterTokenProvider() {
    }

    @Override
    public String getAccessToken() {
        return null;
    }

    @Override
    public String toString() {
        return "NoopTransmitterTokenProvider";
    }
}
