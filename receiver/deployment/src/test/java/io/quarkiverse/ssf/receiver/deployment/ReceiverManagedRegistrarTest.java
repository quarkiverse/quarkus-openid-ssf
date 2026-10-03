package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

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
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * A stream managed by the receiver ({@code stream-management=RECEIVER}, the default):
 * the registrar creates the stream at the transmitter on startup, in the background, and
 * the audience of the stream is the one SETs have to carry when no
 * {@code expected-audience} is configured.
 */
public class ReceiverManagedRegistrarTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> TestTransmitters.archive())
            .setBeforeAllCustomizer(TestTransmitters::start)
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "RECEIVER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "PUSH")
            .overrideConfigKey("quarkus.openid-ssf.receiver.push.delivery-endpoint-url",
                    TestTransmitters.PUSH_DELIVERY_URL)
            .overrideConfigKey("quarkus.openid-ssf.receiver.push.expected-auth-header", "Bearer push-secret")
            .overrideConfigKey("quarkus.openid-ssf.receiver.events-requested", "CaepSessionRevoked,CaepCredentialChange")
            .overrideConfigKey("quarkus.openid-ssf.receiver.receiver-managed.description", "registrar test")
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

    @Test
    @DisplayName("No stream yet -> the registrar creates one with the configured delivery and events")
    void createsStreamWhenNoneExists() {
        SsfTransmitter transmitter = transmitters.primary().orElseThrow();
        TestTransmitters.awaitRegistered(transmitter);

        List<Map<String, Object>> streams = TestTransmitters.current().streams();
        assertEquals(1, streams.size(), "exactly one stream was created");
        Map<String, Object> stream = streams.get(0);
        assertThat(stream.get("stream_id"), equalTo(streamClient.streamId()));
        assertThat(((Map<?, ?>) stream.get("delivery")).get("endpoint_url"), equalTo(TestTransmitters.PUSH_DELIVERY_URL));
        assertThat(stream.get("events_requested"), equalTo(List.of(
                "https://schemas.openid.net/secevent/caep/event-type/session-revoked",
                "https://schemas.openid.net/secevent/caep/event-type/credential-change")));
        assertThat(stream.get("description"), equalTo("registrar test"));
        assertThat(streamClient.stream().orElseThrow().audience(), equalTo(stream.get("aud")));
    }

    @Test
    @DisplayName("SETs must carry the audience of the registered stream")
    void audienceComesFromTheStream() {
        SsfTransmitter transmitter = transmitters.primary().orElseThrow();
        TestTransmitters.awaitRegistered(transmitter);
        TestTransmitter testTransmitter = TestTransmitters.current();
        String streamAudience = streamClient.stream().orElseThrow().audience().get(0);

        String forThisReceiver = testTransmitter.signSet(testTransmitter
                .setClaims("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"))
                .audience(streamAudience).build());
        given().header("Content-Type", "application/secevent+jwt").header("Authorization", "Bearer push-secret")
                .body(forThisReceiver)
                .when().post("/ssf/push")
                .then().statusCode(202);

        String forAnotherReceiver = testTransmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        given().header("Content-Type", "application/secevent+jwt").header("Authorization", "Bearer push-secret")
                .body(forAnotherReceiver)
                .when().post("/ssf/push")
                .then().statusCode(400)
                .body("err", equalTo("invalid_audience"));
    }
}
