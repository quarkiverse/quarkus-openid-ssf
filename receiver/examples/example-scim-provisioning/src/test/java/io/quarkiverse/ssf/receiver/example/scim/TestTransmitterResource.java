package io.quarkiverse.ssf.receiver.example.scim;

import java.util.Map;

import org.easyssf.test.TestTransmitter;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Starts easyssf's test transmitter before the application and points the receiver at
 * it with a POLL stream: in JVM mode and, through the same configuration, for the native
 * binary of the integration test. The transmitter is injected into the fields of the
 * test of its type, so the test can transmit SETs.
 */
public class TestTransmitterResource implements QuarkusTestResourceLifecycleManager {

    private TestTransmitter transmitter;

    @Override
    public Map<String, String> start() {
        transmitter = new TestTransmitter();
        return Map.of(
                "quarkus.openid-ssf.receiver.transmitter-issuer", transmitter.issuer(),
                "quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN,
                "quarkus.openid-ssf.receiver.delivery-method", "POLL",
                "quarkus.openid-ssf.receiver.poll.start-delay", "100ms",
                "quarkus.openid-ssf.receiver.poll.interval", "200ms");
    }

    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(transmitter, new TestInjector.MatchesType(TestTransmitter.class));
    }

    @Override
    public void stop() {
        if (transmitter != null) {
            transmitter.close();
        }
    }
}
