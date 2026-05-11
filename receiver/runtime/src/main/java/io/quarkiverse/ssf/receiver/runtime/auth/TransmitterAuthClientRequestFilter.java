package io.quarkiverse.ssf.receiver.runtime.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * REST Client filter that attaches {@code Authorization: Bearer <token>} from the
 * configured {@link TransmitterTokenProvider}. When OIDC is wired up via
 * {@code quarkus-oidc-client}, this is a fresh access token from the
 * client_credentials grant; otherwise the no-op provider yields no header.
 */
public final class TransmitterAuthClientRequestFilter implements ClientRequestFilter {

    private final TransmitterTokenProvider tokenProvider;

    public TransmitterAuthClientRequestFilter(TransmitterTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        tokenProvider.accessToken()
                .ifPresent(token -> requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
