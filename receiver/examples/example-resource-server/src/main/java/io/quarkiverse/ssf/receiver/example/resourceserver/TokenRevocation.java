package io.quarkiverse.ssf.receiver.example.resourceserver;

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

import io.quarkus.arc.Unremovable;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.smallrye.mutiny.Uni;

/**
 * Rejects access tokens once Keycloak reported that their session was revoked. Two parts
 * of easyssf do the work: the {@link SsfTokenRevocationEventHandler} records the session
 * (or user) of every {@code CaepSessionRevoked} event in the
 * {@link SsfTokenRevocationStore}, and the {@link SecurityIdentityAugmentor} checks every
 * access token against the store: a token bound to a revoked session ({@code sid}), or
 * of a revoked user and issued before the revocation, fails authentication.
 */
@ApplicationScoped
@Unremovable
public class TokenRevocation implements SecurityIdentityAugmentor {

    /**
     * At least the access token lifespan of the realm (5 minutes by default): a
     * revocation only matters while a token issued before it can still be valid.
     */
    private static final Duration REVOCATION_TTL = Duration.ofMinutes(10);

    @Inject
    SsfTokenRevocationStore revocationStore;

    @Produces
    @Singleton
    static SsfTokenRevocationStore revocationStore() {
        return new InMemorySsfTokenRevocationStore(REVOCATION_TTL);
    }

    /** The {@code SsfEventHandler} of easyssf that turns the events into revocations. */
    @Produces
    @Singleton
    static SsfTokenRevocationEventHandler revocationEventHandler(SsfTokenRevocationStore revocationStore) {
        return new SsfTokenRevocationEventHandler(revocationStore, List.of("CaepSessionRevoked"));
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken token)) {
            return Uni.createFrom().item(identity);
        }
        Instant issuedAt = (token.getIssuedAtTime() > 0) ? Instant.ofEpochSecond(token.getIssuedAtTime()) : null;
        if (revocationStore.isRevoked(token.getIssuer(), token.getClaim("sid"), token.getSubject(), issuedAt)) {
            return Uni.createFrom().failure(new AuthenticationFailedException("The token was revoked"));
        }
        return Uni.createFrom().item(identity);
    }
}
