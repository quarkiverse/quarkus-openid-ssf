package io.quarkiverse.ssf.receiver.example.scim;

import org.easyssf.test.TestTransmitter;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Starts the demo SCIM service provider before the application, unless a transmitter is
 * configured: Keycloak does not emit SCIM Events (RFC 9967), so easyssf's
 * {@link TestTransmitter} stands in for the SCIM service provider and
 * {@link DemoScimProvider} transmits a SCIM Event for every request to
 * {@code /demo/scim/Users}. The transmitter listens on a random port, which is why it has
 * to be up before the receiver reads {@code transmitter-issuer}: the main method sets the
 * property and starts Quarkus. In dev mode, the main method runs again with every
 * restart; the tests configure the transmitter themselves and never run it.
 */
@QuarkusMain
public class ExampleScimProvisioning {

    private static final Logger LOG = Logger.getLogger(ExampleScimProvisioning.class);

    static final String ISSUER_PROPERTY = "quarkus.openid-ssf.receiver.transmitter-issuer";

    public static void main(String... args) {
        if (System.getProperty(ISSUER_PROPERTY) != null
                || System.getenv("QUARKUS_OPENID_SSF_RECEIVER_TRANSMITTER_ISSUER") != null) {
            Quarkus.run(args);
            return;
        }
        try (TestTransmitter transmitter = new TestTransmitter()) {
            LOG.infof("Demo SCIM service provider transmits at %s", transmitter.issuer());
            System.setProperty(ISSUER_PROPERTY, transmitter.issuer());
            DemoScimProvider.transmitter = transmitter;
            Quarkus.run(args);
        } finally {
            DemoScimProvider.transmitter = null;
            System.clearProperty(ISSUER_PROPERTY);
        }
    }
}
