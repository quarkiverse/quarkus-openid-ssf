package io.quarkiverse.ssf.receiver.conformance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;
import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jwt.JWTClaimsSet;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * Runs the scenarios against the test transmitter of the receiver library instead of the
 * suite, with POLL delivery so that the transmitter does not have to reach this
 * application. Needs no Docker and runs in the normal build. The transmitter is started
 * by the test, so every run names its issuer.
 */
@QuarkusTest
@TestProfile(ConformanceRunLocalTransmitterTest.Profile.class)
class ConformanceRunLocalTransmitterTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cts.delivery.method", "poll",
                    "cts.delivery.poll-interval", "200ms",
                    "cts.run.idle-timeout", "2s",
                    "cts.run.verification-timeout", "5s",
                    "cts.auth.client-id", TestTransmitter.CLIENT_ID,
                    "cts.auth.client-secret", TestTransmitter.CLIENT_SECRET);
        }
    }

    private static TestTransmitter transmitter;

    @BeforeAll
    static void startTransmitter() {
        transmitter = new TestTransmitter();
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    @AfterAll
    static void stopTransmitter() {
        if (transmitter != null) {
            transmitter.close();
        }
    }

    @BeforeEach
    void resetTransmitter() {
        transmitter.reset();
    }

    @Test
    void playsTheCaepInteropScenario() {
        JsonPath run = start("caep-interop");
        assertThat(run.getString("status"), is(in(List.of("STARTING", "RUNNING"))));

        String streamId = awaitStreamAndVerificationRequest();
        String state = transmitter.verificationRequests().get(0);
        String verification = queueVerificationEvent(streamId, state);
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> transmitter.acknowledgedSets().contains(verification));

        String sessionRevoked = queueSessionRevoked(streamId);
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> transmitter.acknowledgedSets().contains(sessionRevoked));

        JsonPath finished = awaitStatus("FINISHED");
        assertTrue(transmitter.streams().isEmpty(), "the stream was deleted");
        List<String> log = log(finished);
        assertThat(log, hasItem(containsString("Read stream status")));
        assertThat(log, hasItem(allOf(
                containsString("Verification event"),
                containsString("accepted"))));
        assertThat(log,
                hasItem(containsString("Deleted stream " + streamId)));
        assertTrue(transmitter.reportedErrors().isEmpty(), "no SET was reported as an error");
    }

    @Test
    void rejectsVerificationEventWithWrongStateAndRequestsVerificationAgain() {
        start("caep-interop");
        String streamId = awaitStreamAndVerificationRequest();
        String wrong = queueVerificationEvent(streamId, "made-up");
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.reportedErrors().containsKey(wrong));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) transmitter.reportedErrors().get(wrong);
        assertEquals("invalid_state", error.get("err"));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.verificationRequests().size() == 2);

        String right = queueVerificationEvent(streamId, transmitter.verificationRequests().get(1));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.acknowledgedSets().contains(right));
        awaitStatus("FINISHED");
        assertTrue(transmitter.streams().isEmpty());
    }

    @Test
    void rejectsVerificationEventAboutAnotherStream() {
        start("caep-interop");
        String streamId = awaitStreamAndVerificationRequest();
        String wrong = queueVerificationEvent("another-stream", transmitter.verificationRequests().get(0));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.reportedErrors().containsKey(wrong));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.verificationRequests().size() == 2);
        String right = queueVerificationEvent(streamId, transmitter.verificationRequests().get(1));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.acknowledgedSets().contains(right));
        awaitStatus("FINISHED");
    }

    @Test
    void refusesStreamWithAnotherIssuer() {
        transmitter.setStreamIssuer("https://other.example");
        start("caep-interop");
        JsonPath run = awaitStatus("REFUSED_STREAM");
        assertTrue(transmitter.streams().isEmpty());
        assertTrue(transmitter.verificationRequests().isEmpty());
        assertThat(log(run),
                hasItem(containsString("Refusing the stream")));
    }

    @Test
    void createsAndDeletesTheStream() {
        start("create-delete");
        awaitStatus("FINISHED");
        assertTrue(transmitter.streams().isEmpty());
        assertTrue(transmitter.verificationRequests().isEmpty());
    }

    @Test
    void managesStreamAndStatusAndSubjects() {
        for (String scenario : List.of("stream-management", "status-update", "remove-subject")) {
            transmitter.reset();
            start(scenario);
            String streamId = awaitStreamAndVerificationRequest();
            queueVerificationEvent(streamId, transmitter.verificationRequests().get(0));
            JsonPath run = awaitStatus("FINISHED");
            assertTrue(transmitter.streams().isEmpty(), scenario);
            assertThat(scenario, log(run), hasItem(anyOf(
                    containsString("Replaced stream"),
                    containsString("Enabling the stream"),
                    containsString("Removed subject"))));
        }
    }

    @Test
    void pushEndpointAnswersWithoutRun() {
        // a run of another test may still be active, stop it first
        given().when().post("/cts/runs/current/stop");
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !"RUNNING".equals(current().getString("status")));

        given().header("Content-Type", "application/secevent+jwt").body("x")
                .when().post("/ssf/push")
                .then().statusCode(503);
    }

    @Test
    void usageAndUnknownScenario() {
        given().when().get("/").then().statusCode(200).header("Content-Type", startsWith("text/plain"));
        given().queryParam("scenario", "no-such-scenario")
                .when().post("/cts/runs")
                .then().statusCode(400);
    }

    private String awaitStreamAndVerificationRequest() {
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !transmitter.verificationRequests().isEmpty());
        assertEquals(1, transmitter.streams().size());
        return (String) transmitter.streams().get(0).get("stream_id");
    }

    private String queueVerificationEvent(String streamId, String state) {
        JWTClaimsSet claims = transmitter
                .setClaims("SsfStreamVerification", SsfSubjectIdentifiers.opaque(streamId), Map.of("state", state))
                .audience(audience())
                .build();
        transmitter.queueSet(transmitter.signSet(claims));
        return claims.getJWTID();
    }

    private String queueSessionRevoked(String streamId) {
        JWTClaimsSet claims = transmitter
                .setClaims("CaepSessionRevoked", SsfSubjectIdentifiers.issSub(transmitter.issuer(), "alice"))
                .audience(audience())
                .build();
        transmitter.queueSet(transmitter.signSet(claims));
        return claims.getJWTID();
    }

    @SuppressWarnings("unchecked")
    private static List<String> audience() {
        return (List<String>) transmitter.streams().get(0).get("aud");
    }

    private JsonPath start(String scenario) {
        return given().queryParam("scenario", scenario).queryParam("issuer", transmitter.issuer())
                .when().post("/cts/runs")
                .then().statusCode(202)
                .extract().jsonPath();
    }

    private JsonPath current() {
        return given().when().get("/cts/runs/current").then().statusCode(200).extract().jsonPath();
    }

    private JsonPath awaitStatus(String status) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> status.equals(current().getString("status")));
        return current();
    }

    private static List<String> log(JsonPath run) {
        return run.getList("log.message", String.class);
    }
}
