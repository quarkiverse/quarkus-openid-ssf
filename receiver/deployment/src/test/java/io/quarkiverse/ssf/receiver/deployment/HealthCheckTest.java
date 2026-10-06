package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import jakarta.inject.Inject;

import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * The wellness check with {@code quarkus-smallrye-health}: a transmitter with POLL
 * delivery reports its stream, how it is polled and the acknowledgements waiting for the
 * next request.
 */
public class HealthCheckTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive())
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                TestTransmitters.addPollStream(TestTransmitters.DEFAULT, transmitter);
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "POLL")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.auto-start", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @Inject
    SsfTransmitters transmitters;

    @BeforeEach
    void awaitStream() {
        TestTransmitters.awaitRegistered(transmitters.primary().orElseThrow());
    }

    @Test
    @DisplayName("/q/health/well reports the poller of the transmitter with its pending acknowledgements")
    void wellnessCheck() {
        given().when().get("/q/health/well")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks[0].name", equalTo("SSF receiver"))
                .body("checks[0].data.delivery", equalTo("poll"))
                .body("checks[0].data.streamRegistration", equalTo("registered"))
                .body("checks[0].data.polling", equalTo("manual"))
                .body("checks[0].data.pendingAcks", equalTo(0));
    }
}
