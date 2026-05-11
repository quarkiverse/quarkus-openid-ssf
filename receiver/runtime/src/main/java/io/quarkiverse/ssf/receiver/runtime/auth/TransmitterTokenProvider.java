package io.quarkiverse.ssf.receiver.runtime.auth;

import java.util.Optional;

/**
 * Supplies a Bearer access token to use on outbound calls to the SSF transmitter.
 *
 * <p>
 * The default implementation ({@link NoopTransmitterTokenProvider}) always returns
 * {@link Optional#empty()}. When {@code quarkus-oidc-client} is on the consumer's
 * classpath, the deployment processor swaps in {@link OidcTransmitterTokenProvider}
 * which fetches a token via the configured {@code quarkus.oidc-client.*} settings
 * (client_credentials grant by default).
 */
public interface TransmitterTokenProvider {
    Optional<String> accessToken();
}
