package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * Several transmitters: the default one at {@code quarkus.openid-ssf.receiver.*} and a
 * named one at {@code quarkus.openid-ssf.receiver.second.*}. The issuer of a pushed SET
 * selects the transmitter that verifies it, each with its own push authorization.
 */
public class NamedTransmittersTest {

    private static final String SECOND = "second";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive(CapturingHandler.class))
            .setBeforeAllCustomizer(() -> {
                TestTransmitters.addPushStream(TestTransmitters.DEFAULT, TestTransmitters.start());
                TestTransmitters.addPushStream(SECOND, TestTransmitters.start(SECOND));
            })
            .setAfterAllCustomizer(() -> {
                TestTransmitters.stop();
                TestTransmitters.stop(SECOND);
            })
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN)
            .overrideConfigKey("quarkus.openid-ssf.receiver.second.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(SECOND)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.second.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.second.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.second.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(SECOND)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.second.push.expected-auth-header", "Bearer second-secret")
            .overrideConfigKey("quarkus.openid-ssf.receiver.second.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @Inject
    SsfTransmitters transmitters;

    @Inject
    SsfReceiverStreamClient streamClient;

    @Inject
    SsfEventHandler handler;

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
    @DisplayName("Both transmitters are configured, the default one is the primary")
    void twoTransmitters() {
        assertThat(transmitters.all().stream().map(SsfTransmitter::getName).toList(),
                containsInAnyOrder(SsfTransmitter.DEFAULT_NAME, SECOND));
        assertThat(transmitters.primary().orElseThrow().getName(), equalTo(SsfTransmitter.DEFAULT_NAME));
        assertThat(transmitters.nameOf(TestTransmitters.current(SECOND).issuer()), equalTo(SECOND));
        assertThat(streamClient.transmitter().getIssuer(), equalTo(TestTransmitters.current().issuer()));
        assertThat(transmitters.get(SECOND).orElseThrow().getPushAuthorizationHeader(), equalTo("Bearer second-secret"));
    }

    @Test
    @DisplayName("A SET of the named transmitter is verified by it and needs its push authorization")
    void setOfTheNamedTransmitter() {
        TestTransmitter second = TestTransmitters.current(SECOND);
        String set = second.set("CaepCredentialChange", SsfSubjectIdentifiers.email("user@example.com"));

        given().header("Content-Type", "application/secevent+jwt").body(set)
                .when().post("/ssf/push")
                .then().statusCode(401);

        given().header("Content-Type", "application/secevent+jwt").header("Authorization", "Bearer second-secret")
                .body(set)
                .when().post("/ssf/push")
                .then().statusCode(202);

        List<SsfEventToken> captured = ((CapturingHandler) handler).captured;
        assertEquals(1, captured.size());
        assertThat(captured.get(0).iss(), equalTo(second.issuer()));
    }

    @Test
    @DisplayName("A SET of the default transmitter needs no push authorization")
    void setOfTheDefaultTransmitter() {
        String set = TestTransmitters.current().set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        given().header("Content-Type", "application/secevent+jwt").body(set)
                .when().post("/ssf/push")
                .then().statusCode(202);
        assertThat(((CapturingHandler) handler).captured.get(0).iss(), equalTo(TestTransmitters.current().issuer()));
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
