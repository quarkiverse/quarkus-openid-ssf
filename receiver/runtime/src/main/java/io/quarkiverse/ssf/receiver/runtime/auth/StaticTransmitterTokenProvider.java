package io.quarkiverse.ssf.receiver.runtime.auth;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;

/**
 * Returns a fixed bearer token configured via
 * {@code quarkus.openid-ssf.receiver.transmitter-access-token}. Registered by
 * {@code SsfReceiverProcessor} only when that property is set; in that case it
 * also displaces {@link OidcTransmitterTokenProvider} so there's no CDI
 * ambiguity over which provider supplies the outbound token.
 *
 * <p>
 * Intended for transmitters that issue long-lived bearer tokens out-of-band
 * (e.g. <a href="https://ssf.caep.dev">caep.dev</a>) rather than expecting an
 * OAuth grant.
 */
@ApplicationScoped
public class StaticTransmitterTokenProvider implements TransmitterTokenProvider {

    @Inject
    SsfReceiverConfig config;

    @Override
    public Optional<String> accessToken() {
        return config.transmitterAccessToken().filter(t -> !t.isBlank());
    }
}
