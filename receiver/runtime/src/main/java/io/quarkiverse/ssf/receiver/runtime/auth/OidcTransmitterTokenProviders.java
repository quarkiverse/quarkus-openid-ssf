package io.quarkiverse.ssf.receiver.runtime.auth;

import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;

import io.quarkiverse.ssf.receiver.runtime.SsfTransmitterConfig;

/**
 * Creates token providers backed by {@code quarkus-oidc-client}. The only implementation,
 * {@link OidcClientTransmitterTokenProviders}, is registered by the deployment processor
 * when {@code quarkus-oidc-client} is present, so this interface keeps the OIDC client
 * classes out of the extension's own class loading when it is not.
 */
public interface OidcTransmitterTokenProviders {

    /**
     * @param transmitterName the name of the transmitter, for log messages
     * @param oidc which OIDC client to use and how long to wait for a token
     */
    SsfTransmitterTokenProvider create(String transmitterName, SsfTransmitterConfig.Oidc oidc);
}
