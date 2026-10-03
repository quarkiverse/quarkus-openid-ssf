package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A transmitter that is down while the application starts does not keep it from
 * starting: the registrar retries in the background until the stream is registered.
 */
public class RegistrarRetryTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive())
            .setBeforeAllCustomizer(() -> TestTransmitters.start().setAvailable(false))
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "RECEIVER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "POLL")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.auto-start", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.events-requested", "CaepSessionRevoked")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @Inject
    SsfTransmitters transmitters;

    @Inject
    SsfReceiverStreamClient streamClient;

    @Test
    @DisplayName("Transmitter down at startup -> registering with retries, registered once it is back")
    void registrarRetriesUntilTheTransmitterIsBack() {
        SsfTransmitter transmitter = transmitters.primary().orElseThrow();
        SsfStreamRegistrar registrar = transmitter.getStreamRegistrar();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> registrar.getState() == SsfStreamRegistrar.State.REGISTERING
                        && registrar.getLastError() != null);
        assertThat(registrar.getLastError(), notNullValue());
        assertEquals(0, TestTransmitters.current().streams().size());

        TestTransmitters.current().setAvailable(true);
        TestTransmitters.awaitRegistered(transmitter);

        assertEquals(1, TestTransmitters.current().streams().size());
        assertThat(streamClient.streamId(), equalTo(TestTransmitters.current().streams().get(0).get("stream_id")));
        // the poll endpoint of the created stream is known now
        assertThat(streamClient.stream().orElseThrow().deliveryEndpointUrl().toString(),
                equalTo(TestTransmitters.current().pollUri()));
    }
}
