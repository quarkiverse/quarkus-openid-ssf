package io.quarkiverse.ssf.receiver.runtime.auth;

import java.time.Duration;

import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;

/**
 * Obtains access tokens from a Quarkus {@link OidcClient}, configured by the application
 * with {@code quarkus.oidc-client.*} (auth server, client id, credentials, grant). Every
 * call fetches a token; the client is expected to be configured with the
 * {@code client} (client credentials) grant.
 */
public final class OidcTransmitterTokenProvider implements SsfTransmitterTokenProvider {

    private final OidcClient oidcClient;

    private final String clientName;

    private final Duration timeout;

    public OidcTransmitterTokenProvider(OidcClient oidcClient, String clientName, Duration timeout) {
        this.oidcClient = oidcClient;
        this.clientName = clientName;
        this.timeout = timeout;
    }

    @Override
    public String getAccessToken() {
        try {
            Tokens tokens = oidcClient.getTokens().await().atMost(timeout);
            return (tokens != null) ? tokens.getAccessToken() : null;
        } catch (RuntimeException e) {
            throw new SsfTransmitterUnavailableException(
                    "Could not obtain an access token from the OIDC client '" + clientName + "': " + summarize(e), e);
        }
    }

    @Override
    public String toString() {
        return "OidcTransmitterTokenProvider(" + clientName + ")";
    }

    /**
     * One line of an exception. Some auth server failure modes (a proxy returning an HTML
     * error page) put the entire body in the message.
     */
    private static String summarize(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        int newline = msg.indexOf('\n');
        if (newline >= 0) {
            msg = msg.substring(0, newline);
        }
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return t.getClass().getSimpleName() + ": " + msg.trim();
    }
}
