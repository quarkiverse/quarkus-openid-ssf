package io.quarkiverse.ssf.receiver.runtime.auth;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;

import io.quarkiverse.ssf.receiver.runtime.SsfTransmitterConfig;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.OidcClients;

/**
 * {@link OidcTransmitterTokenProviders} over the {@link OidcClients} of
 * {@code quarkus-oidc-client}. Registered by the deployment processor only when that
 * extension is present, so this class is never loaded otherwise.
 */
@Singleton
public class OidcClientTransmitterTokenProviders implements OidcTransmitterTokenProviders {

    @Inject
    OidcClients oidcClients;

    @Override
    public SsfTransmitterTokenProvider create(String transmitterName, SsfTransmitterConfig.Oidc oidc) {
        String clientName = oidc.clientName().orElse(null);
        OidcClient client = (clientName != null) ? oidcClients.getClient(clientName) : oidcClients.getClient();
        if (client == null) {
            throw new IllegalStateException("The OIDC client " + ((clientName != null) ? "'" + clientName + "'" : "")
                    + " for the SSF transmitter '" + transmitterName + "' is not configured (quarkus.oidc-client."
                    + ((clientName != null) ? clientName + "." : "") + "*)");
        }
        return new OidcTransmitterTokenProvider(client, (clientName != null) ? clientName : "default",
                oidc.tokenTimeout());
    }
}
