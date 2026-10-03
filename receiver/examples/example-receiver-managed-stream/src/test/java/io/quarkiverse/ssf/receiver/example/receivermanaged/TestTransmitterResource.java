package io.quarkiverse.ssf.receiver.example.receivermanaged;

import java.util.Map;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.test.TestTransmitter;

import com.nimbusds.jwt.SignedJWT;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Starts easyssf's test transmitter before the application and points the receiver at
 * it: in JVM mode and, through the same configuration, for the native binary of the
 * integration test. A signed SET and its id are handed to the test through system
 * properties, as the test methods of a {@code @QuarkusTest} cannot share objects with
 * this class.
 */
public class TestTransmitterResource implements QuarkusTestResourceLifecycleManager {

    static final String PROP_SET = "test.ssf.set";
    static final String PROP_JTI = "test.ssf.jti";

    private TestTransmitter transmitter;

    @Override
    public Map<String, String> start() {
        transmitter = new TestTransmitter();
        String set = transmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        try {
            System.setProperty(PROP_JTI, SignedJWT.parse(set).getJWTClaimsSet().getJWTID());
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
        System.setProperty(PROP_SET, set);
        return Map.of(
                "quarkus.openid-ssf.receiver.transmitter-issuer", transmitter.issuer(),
                "quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN,
                "quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE,
                "quarkus.openid-ssf.receiver.delivery-method", "PUSH",
                "quarkus.openid-ssf.receiver.push.delivery-endpoint-url", "http://localhost:28081/ssf/push",
                "quarkus.openid-ssf.receiver.receiver-managed.delete-on-shutdown", "true");
    }

    @Override
    public void stop() {
        if (transmitter != null) {
            transmitter.close();
        }
        System.clearProperty(PROP_SET);
        System.clearProperty(PROP_JTI);
    }
}
