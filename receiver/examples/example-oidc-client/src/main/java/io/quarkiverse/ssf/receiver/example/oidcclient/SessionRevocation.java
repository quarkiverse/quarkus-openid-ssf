package io.quarkiverse.ssf.receiver.example.oidcclient;

import java.time.Duration;
import java.util.List;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.easyssf.receiver.revocation.InMemorySsfTokenRevocationStore;
import org.easyssf.receiver.revocation.SsfTokenRevocationEventHandler;
import org.easyssf.receiver.revocation.SsfTokenRevocationStore;

import io.quarkus.arc.Unremovable;

/**
 * Records which Keycloak sessions and users were revoked: the
 * {@link SsfTokenRevocationEventHandler} of easyssf, an {@code SsfEventHandler}, puts the
 * session ({@code sid}) or user of every event into the {@link SsfTokenRevocationStore}.
 * {@link RevocationAwareTokenStateManager} ends the local sessions found there.
 */
@Singleton
@Unremovable
public class SessionRevocation {

    /** How long a revocation is kept: at least the SSO session lifetime that matters. */
    private static final Duration REVOCATION_TTL = Duration.ofHours(12);

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
}
