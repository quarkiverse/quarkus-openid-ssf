package io.quarkiverse.ssf.receiver.example.oidcclient;

import java.text.ParseException;
import java.util.Date;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import org.easyssf.receiver.revocation.SsfTokenRevocationStore;
import org.jboss.logging.Logger;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.quarkus.oidc.AuthorizationCodeTokens;
import io.quarkus.oidc.OidcRequestContext;
import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.TokenStateManager;
import io.quarkus.oidc.runtime.DefaultTokenStateManager;
import io.quarkus.oidc.runtime.OidcUtils;
import io.quarkus.oidc.runtime.TenantConfigContext;
import io.quarkus.security.AuthenticationFailedException;
import io.smallrye.jwt.algorithm.KeyEncryptionAlgorithm;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;

/**
 * Ends the local session of a user once Keycloak reported that their Keycloak session was
 * revoked or their credentials changed. The local session of {@code quarkus-oidc} is the
 * session cookie; this {@link TokenStateManager} wraps the default one and refuses the
 * tokens of the cookie when the {@link SsfTokenRevocationStore} has the session
 * ({@code sid} of the ID token) or the user. {@code quarkus-oidc} then removes the cookie
 * and sends the browser to the login, the same way it ends a session on a back-channel
 * logout.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RevocationAwareTokenStateManager implements TokenStateManager {

    private static final Logger LOG = Logger.getLogger(RevocationAwareTokenStateManager.class);

    @Inject
    DefaultTokenStateManager delegate;

    @Inject
    SsfTokenRevocationStore revocationStore;

    @Override
    public Uni<String> createTokenState(RoutingContext routingContext, OidcTenantConfig oidcConfig,
            AuthorizationCodeTokens tokens, OidcRequestContext<String> requestContext) {
        return delegate.createTokenState(routingContext, oidcConfig, tokens, requestContext);
    }

    @Override
    public Uni<AuthorizationCodeTokens> getTokens(RoutingContext routingContext, OidcTenantConfig oidcConfig,
            String tokenState, OidcRequestContext<AuthorizationCodeTokens> requestContext) {
        return delegate.getTokens(routingContext, oidcConfig, tokenState, requestContext)
                .onItem().transformToUni(tokens -> isRevoked(idToken(routingContext, oidcConfig, tokens))
                        ? Uni.createFrom().failure(new AuthenticationFailedException("The session was revoked"))
                        : Uni.createFrom().item(tokens));
    }

    @Override
    public Uni<Void> deleteTokens(RoutingContext routingContext, OidcTenantConfig oidcConfig, String tokenState,
            OidcRequestContext<Void> requestContext) {
        return delegate.deleteTokens(routingContext, oidcConfig, tokenState, requestContext);
    }

    /**
     * A token state manager other than the default one gets the tokens as
     * {@code quarkus-oidc} stores them: encrypted, when
     * {@code quarkus.oidc.token-state-manager.encryption-required} is on (the default).
     * The mechanism decrypts them after this manager returned them, so the check has to
     * decrypt the ID token itself.
     */
    private static String idToken(RoutingContext routingContext, OidcTenantConfig oidcConfig,
            AuthorizationCodeTokens tokens) {
        if (oidcConfig.tokenStateManager().encryptionRequired()) {
            // The tokens are encrypted the way DefaultTokenStateManager encrypts them:
            // with the session cookie key of the tenant, which the OIDC mechanism puts
            // into the routing context, and the configured key encryption algorithm.
            TenantConfigContext tenant = routingContext.get(TenantConfigContext.class.getName());
            try {
                KeyEncryptionAlgorithm algorithm = KeyEncryptionAlgorithm
                        .valueOf(oidcConfig.tokenStateManager().encryptionAlgorithm().name());
                return OidcUtils.decryptString(tokens.getIdToken(), tenant.getSessionCookieEncryptionKey(), algorithm);
            } catch (Exception e) {
                throw new AuthenticationFailedException(e);
            }
        }
        return tokens.getIdToken();
    }

    private boolean isRevoked(String idToken) {
        JWTClaimsSet claims;
        try {
            claims = SignedJWT.parse(idToken).getJWTClaimsSet();
        } catch (ParseException e) {
            // not a signed ID token (encrypted by the provider): nothing to check
            return false;
        }
        String sessionId = (claims.getClaim("sid") instanceof String sid) ? sid : null;
        Date issuedAt = claims.getIssueTime();
        boolean revoked = revocationStore.isRevoked(claims.getIssuer(), sessionId, claims.getSubject(),
                (issuedAt != null) ? issuedAt.toInstant() : null);
        if (revoked) {
            LOG.infof("Ending the local session of %s: Keycloak session %s was revoked", claims.getSubject(),
                    sessionId);
        }
        return revoked;
    }

}
