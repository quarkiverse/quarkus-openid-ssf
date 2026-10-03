package io.quarkiverse.ssf.receiver.example.oidcclient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.receiver.revocation.InMemorySsfTokenRevocationStore;
import org.easyssf.receiver.revocation.SsfTokenRevocationEventHandler;
import org.easyssf.receiver.revocation.SsfTokenRevocationStore;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.smallrye.mutiny.Uni;

/**
 * Ends the local session of a user once Keycloak reported that their Keycloak session was
 * revoked or their credentials changed. The local session of {@code quarkus-oidc} is the
 * ID token in the session cookie; the {@link SsfTokenRevocationEventHandler} of easyssf
 * records the session ({@code sid}) or user of the events in the
 * {@link SsfTokenRevocationStore}, and the {@link SecurityIdentityAugmentor} checks the
 * ID token of every request against it. A revoked ID token fails authentication, which
 * sends the browser to the login.
 */
@ApplicationScoped
@Unremovable
public class SessionRevocation implements SecurityIdentityAugmentor {

    private static final Logger LOG = Logger.getLogger(SessionRevocation.class);

    /** How long a revocation is kept: at least the SSO session lifetime that matters. */
    private static final Duration REVOCATION_TTL = Duration.ofHours(12);

    @Inject
    SsfTokenRevocationStore revocationStore;

    @Produces
    @Singleton
    static SsfTokenRevocationStore revocationStore() {
        return new InMemorySsfTokenRevocationStore(REVOCATION_TTL);
    }

    /**
     * {@code CaepSessionRevoked} ends the session the event names (or all sessions of the
     * user if it names none), {@code CaepCredentialChange} ends all sessions of the user
     * that started before the change.
     */
    @Produces
    @Singleton
    static SsfTokenRevocationEventHandler revocationEventHandler(SsfTokenRevocationStore revocationStore) {
        return new SsfTokenRevocationEventHandler(revocationStore,
                List.of("CaepSessionRevoked", "CaepCredentialChange"));
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken token)) {
            return Uni.createFrom().item(identity);
        }
        Instant issuedAt = (token.getIssuedAtTime() > 0) ? Instant.ofEpochSecond(token.getIssuedAtTime()) : null;
        String sessionId = token.getClaim("sid");
        if (revocationStore.isRevoked(token.getIssuer(), sessionId, token.getSubject(), issuedAt)) {
            LOG.infof("Ending the local session of %s: Keycloak session %s was revoked", token.getSubject(), sessionId);
            return Uni.createFrom().failure(new AuthenticationFailedException("The session was revoked"));
        }
        return Uni.createFrom().item(identity);
    }
}
