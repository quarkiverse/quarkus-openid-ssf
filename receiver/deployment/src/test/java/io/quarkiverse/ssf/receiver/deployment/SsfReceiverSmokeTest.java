package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.core.metadata.SsfTransmitterMetadata;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.core.stream.SsfStreamStatus;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * The receiver against easyssf's test transmitter: PUSH delivery with a stream the
 * operator created ({@code stream-management=TRANSMITTER}), the stream management API
 * through {@link SsfReceiverStreamClient}, and the transmitter metadata.
 */
public class SsfReceiverSmokeTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> TestTransmitters.archive(CapturingHandler.class))
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                TestTransmitters.addPushStream(TestTransmitters.DEFAULT, transmitter);
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "PUSH")
            .overrideConfigKey("quarkus.openid-ssf.receiver.push.endpoint-path", "/ssf/push")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @Inject
    SsfEventHandler handler;

    @Inject
    SsfReceiverStreamClient streamClient;

    @Inject
    SsfTransmitters transmitters;

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    @BeforeEach
    void reset() {
        ((CapturingHandler) handler).captured.clear();
    }

    @Test
    void pushedSetIsVerifiedAndDispatchedBeforeTheResponse() throws Exception {
        TestTransmitter transmitter = TestTransmitters.current();
        String set = transmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        String expectedJti = com.nimbusds.jwt.SignedJWT.parse(set).getJWTClaimsSet().getJWTID();

        given().header("Content-Type", "application/secevent+jwt").body(set)
                .when().post("/ssf/push")
                .then().statusCode(202);

        // the handler ran before the 202, no waiting needed
        List<SsfEventToken> captured = ((CapturingHandler) handler).captured;
        assertEquals(1, captured.size());
        SsfEventToken eventToken = captured.get(0);
        assertThat(eventToken.jti(), equalTo(expectedJti));
        assertThat(eventToken.iss(), equalTo(transmitter.issuer()));
        assertThat(eventToken.iat(), is(notNullValue()));
        assertThat(eventToken.aud(), equalTo(List.of(TestTransmitter.AUDIENCE)));
        assertThat(eventToken.subjectId().get("format"), equalTo("opaque"));
        assertThat(eventToken.subjectId().get("id"), equalTo("user-1234"));
        assertThat(eventToken.events().keySet(), equalTo(java.util.Set.of(TestTransmitters.EVENT_TYPE)));
        assertThat(((Map<?, ?>) eventToken.events().get(TestTransmitters.EVENT_TYPE)).get("event_timestamp"),
                is(notNullValue()));
    }

    @Test
    void setWithMismatchedAudienceIsRejected() {
        TestTransmitter transmitter = TestTransmitters.current();
        String set = transmitter.signSet(transmitter
                .setClaims("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"))
                .audience("https://some.other.receiver.example/")
                .build());

        given().header("Content-Type", "application/secevent+jwt").body(set)
                .when().post("/ssf/push")
                .then().statusCode(400)
                .contentType("application/json")
                .body("err", equalTo("invalid_audience"));
        assertEquals(0, ((CapturingHandler) handler).captured.size());
    }

    @Test
    void subjectsCanBeAddedAndRemoved() {
        TestTransmitter transmitter = TestTransmitters.current();
        Map<String, Object> subject = SsfSubjectIdentifiers.email("user@example.com");

        streamClient.addSubject(subject, true);
        streamClient.removeSubject(subject);
        streamClient.addSubject(SsfSubjectIdentifiers.issSub(transmitter.issuer(), "user-1234"), false);

        assertThat(transmitter.addedSubjects(), hasItem(subject));
        assertThat(transmitter.removedSubjects(), hasItem(subject));
    }

    @Test
    void verificationCanBeRequested() {
        String state = streamClient.requestVerification();

        assertNotNull(state);
        assertThat(TestTransmitters.current().verificationRequests(), hasItem(state));
        assertThat(streamClient.transmitter().getStreamVerification().isPending(), is(true));
    }

    @Test
    void streamConfigurationIsResolvedFromTransmitter() {
        SsfTransmitter transmitter = transmitters.primary().orElseThrow();
        TestTransmitters.awaitRegistered(transmitter);
        String streamId = System.getProperty(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT));

        SsfStreamConfiguration configuration = streamClient.configuration();
        assertThat(configuration.streamId(), equalTo(streamId));
        assertThat(configuration.issuer(), equalTo(TestTransmitters.current().issuer()));
        assertThat(configuration.audience(), equalTo(List.of(TestTransmitter.CLIENT_ID + "/" + streamId)));
        assertThat(configuration.deliveryMethod(), equalTo("urn:ietf:rfc:8935"));
        assertThat(configuration.deliveryEndpointUrl().toString(), equalTo(TestTransmitters.PUSH_DELIVERY_URL));
        assertThat(configuration.eventsDelivered(), equalTo(List.of(TestTransmitters.EVENT_TYPE)));
        assertThat(configuration.description(), equalTo("test stream"));

        // the startup lookup left the same configuration with the receiver
        assertThat(streamClient.stream().orElseThrow().streamId(), equalTo(streamId));
        assertThat(streamClient.streamId(), equalTo(streamId));
    }

    @Test
    void streamStatusIsReadFromTransmitter() {
        SsfStreamStatus status = streamClient.status();
        assertThat(status.streamId(), equalTo(streamClient.streamId()));
        assertThat(status.status(), equalTo(SsfStreamStatus.ENABLED));

        SsfStreamStatus paused = streamClient.updateStatus("paused", "maintenance");
        assertThat(paused.status(), equalTo(SsfStreamStatus.PAUSED));
    }

    @Test
    void transmitterMetadataIsResolved() {
        SsfTransmitterMetadata metadata = transmitters.primary().orElseThrow().getMetadataResolver().resolve();
        assertThat(metadata.issuer(), equalTo(TestTransmitters.current().issuer()));
        assertThat(metadata.jwksUri().toString(), equalTo(TestTransmitters.current().jwksUri()));
        assertThat(metadata.statusEndpoint(), is(notNullValue()));
    }

    @Singleton
    public static class CapturingHandler implements SsfEventHandler {
        final List<SsfEventToken> captured = new CopyOnWriteArrayList<>();

        @Override
        public void handle(SsfEventContext eventContext) {
            captured.add(eventContext.eventToken());
        }
    }
}
