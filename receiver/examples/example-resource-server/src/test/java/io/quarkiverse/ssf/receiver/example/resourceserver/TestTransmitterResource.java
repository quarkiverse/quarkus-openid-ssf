package io.quarkiverse.ssf.receiver.example.resourceserver;

import java.time.Instant;
import java.util.Map;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.test.TestTransmitter;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Stands in for Keycloak: easyssf's test transmitter publishes a JWK Set, issues access
 * tokens signed with it and is the SSF transmitter the receiver registers its stream at.
 * {@code quarkus-oidc} is pointed at its JWK Set with discovery switched off. The
 * configuration reaches the application in JVM mode and, as system properties, the
 * native binary of the integration test. Tokens and SETs are minted here and handed to
 * the test through system properties, as the test methods of a {@code @QuarkusTest}
 * cannot share objects with this class.
 */
public class TestTransmitterResource implements QuarkusTestResourceLifecycleManager {

    static final String PROP_ACCESS_TOKEN = "test.ssf.access-token";
    static final String PROP_SESSION_REVOKED_SET = "test.ssf.session-revoked-set";
    static final String PROP_OTHER_SESSION_REVOKED_SET = "test.ssf.other-session-revoked-set";

    static final String USER = "user-1";
    static final String SESSION = "session-1";

    private TestTransmitter transmitter;

    @Override
    public Map<String, String> start() {
        transmitter = new TestTransmitter();
        System.setProperty(PROP_ACCESS_TOKEN, transmitter.accessToken(USER, SESSION, Instant.now()));
        System.setProperty(PROP_OTHER_SESSION_REVOKED_SET, transmitter.set("CaepSessionRevoked",
                SsfSubjectIdentifiers.complex(SsfSubjectIdentifiers.issSub(transmitter.issuer(), USER),
                        SsfSubjectIdentifiers.opaque("another-session"))));
        System.setProperty(PROP_SESSION_REVOKED_SET, transmitter.set("CaepSessionRevoked",
                SsfSubjectIdentifiers.complex(SsfSubjectIdentifiers.issSub(transmitter.issuer(), USER),
                        SsfSubjectIdentifiers.opaque(SESSION))));
        return Map.of(
                "quarkus.oidc.auth-server-url", transmitter.issuer(),
                "quarkus.oidc.discovery-enabled", "false",
                "quarkus.oidc.jwks-path", "jwks",
                "quarkus.oidc.token.issuer", transmitter.issuer(),
                "quarkus.openid-ssf.receiver.transmitter-issuer", transmitter.issuer(),
                "quarkus.openid-ssf.receiver.oauth2.token-endpoint", transmitter.tokenUri(),
                "quarkus.openid-ssf.receiver.oauth2.client-id", TestTransmitter.CLIENT_ID,
                "quarkus.openid-ssf.receiver.oauth2.client-secret", TestTransmitter.CLIENT_SECRET,
                "quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE,
                "quarkus.openid-ssf.receiver.receiver-managed.delete-on-shutdown", "true");
    }

    @Override
    public void stop() {
        if (transmitter != null) {
            transmitter.close();
        }
        System.clearProperty(PROP_ACCESS_TOKEN);
        System.clearProperty(PROP_SESSION_REVOKED_SET);
        System.clearProperty(PROP_OTHER_SESSION_REVOKED_SET);
    }
}
