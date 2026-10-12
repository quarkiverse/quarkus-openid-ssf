package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import jakarta.inject.Inject;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * Where the audience of the SETs comes from the stream the receiver registers, a SET that
 * arrives before the stream is known is not accepted unchecked: it is answered with
 * {@code 503}, so that the transmitter delivers it again; once the stream is registered,
 * SETs are checked against its audience. The transmitter's stream management is down
 * while the application starts, its metadata and keys are reachable.
 */
public class StreamAudienceTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive())
            .setBeforeAllCustomizer(() -> TestTransmitters.start().setAvailable(false))
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "RECEIVER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "PUSH")
            .overrideConfigKey("quarkus.openid-ssf.receiver.push.delivery-endpoint-url",
                    TestTransmitters.PUSH_DELIVERY_URL)
            .overrideConfigKey("quarkus.openid-ssf.receiver.events-requested", "CaepSessionRevoked")
            .overrideConfigKey("quarkus.openid-ssf.receiver.receiver-managed.delete-on-shutdown", "true")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @Inject
    SsfTransmitters transmitters;

    @Inject
    SsfReceiverStreamClient streamClient;

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    private static String set(String audience) {
        TestTransmitter transmitter = TestTransmitters.current();
        return transmitter.signSet(transmitter
                .setClaims("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234")).audience(audience).build());
    }

    @Test
    @DisplayName("Stream not registered yet -> 503; registered -> checked against its audience")
    void setsWaitForTheStreamAndAreCheckedAgainstItsAudience() {
        // the stream is not registered yet: the SET is left to the redelivery
        given().header("Content-Type", "application/secevent+jwt").body(set(TestTransmitter.AUDIENCE))
                .when().post("/ssf/push")
                .then().statusCode(503);

        TestTransmitters.current().setAvailable(true);
        SsfTransmitter transmitter = transmitters.primary().orElseThrow();
        TestTransmitters.awaitRegistered(transmitter);
        String streamAudience = streamClient.stream().orElseThrow().audience().get(0);

        // the stream is known: SETs for other receivers are rejected, the stream's are accepted
        given().header("Content-Type", "application/secevent+jwt").body(set("https://other-receiver.example"))
                .when().post("/ssf/push")
                .then().statusCode(400)
                .body("err", equalTo("invalid_audience"));
        given().header("Content-Type", "application/secevent+jwt").body(set(streamAudience))
                .when().post("/ssf/push")
                .then().statusCode(202);
    }
}
