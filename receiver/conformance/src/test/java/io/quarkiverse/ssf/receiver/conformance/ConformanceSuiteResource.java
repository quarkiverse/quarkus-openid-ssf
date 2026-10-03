package io.quarkiverse.ssf.receiver.conformance;

import java.util.Map;

import org.easyssf.test.conformance.ConformanceSuite;
import org.easyssf.test.conformance.receiver.AbstractReceiverConformanceTest;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Starts the OpenID conformance suite with Testcontainers before the receiver under test
 * and points the receiver at it: its TLS certificate goes into the trust store the
 * receiver calls the transmitter with, and the push URL is the one under which the suite
 * reaches the receiver on the Docker host, on the configured port. The suite is a singleton the plan tests share;
 * it is stopped with the JVM.
 */
public class ConformanceSuiteResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        ConformanceSuite suite = ConformanceSuite.instance();
        return Map.of(
                "quarkus.http.test-ssl-port", String.valueOf(AbstractReceiverConformanceTest.receiverPort()),
                "quarkus.tls.conformance-suite.trust-store.pem.certs", suite.certificateFile().toString(),
                "cts.transmitter.tls-configuration-name", "conformance-suite",
                "cts.delivery.push-url", AbstractReceiverConformanceTest.receiverPushUrl(suite).toString());
    }

    @Override
    public void stop() {
        // the suite is shared by the plan tests and stopped with the JVM
    }
}
