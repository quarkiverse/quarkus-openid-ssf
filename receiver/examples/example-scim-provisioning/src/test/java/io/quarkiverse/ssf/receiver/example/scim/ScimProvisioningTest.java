package io.quarkiverse.ssf.receiver.example.scim;

import static io.restassured.RestAssured.given;
import static org.easyssf.core.event.SsfSubjectIdentifiers.scim;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jwt.JWTClaimsSet;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Plays the life of a user through the test transmitter, which the receiver polls, and
 * checks the directory at {@code /users} after every SCIM Event. {@link ScimProvisioningIT}
 * runs the same against the native binary.
 */
@QuarkusTest
@QuarkusTestResource(TestTransmitterResource.class)
public class ScimProvisioningTest {

    private static final String ALICE_ID = "2b2f880af6674ac284bae9381673d462";
    private static final String ALICE = "/Users/" + ALICE_ID;

    TestTransmitter transmitter;

    @BeforeEach
    void waitForTheStream() {
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> !transmitter.streams().isEmpty());
    }

    @Test
    void mirrorsTheLifeOfAUser() {
        transmit(SsfEventTypes.SCIM_PROV_CREATE_FULL, Map.of("version", "1", "data",
                Map.of("userName", "alice", "name", Map.of("givenName", "Alice", "familyName", "Adams"),
                        "emails", List.of(Map.of("value", "alice@example.com")))));
        awaitUser(() -> given().when().get("/users/" + ALICE_ID).then().statusCode(200)
                .body("userName", equalTo("alice"))
                .body("displayName", equalTo("Alice Adams"))
                .body("emails", contains("alice@example.com"))
                .body("externalId", equalTo("alice"))
                .body("active", equalTo(true))
                .body("version", equalTo("1")));
        given().when().get("/users").then().statusCode(200).body("userName", contains("alice"));

        transmit(SsfEventTypes.SCIM_PROV_PATCH_FULL, Map.of("version", "2", "data",
                Map.of("Operations", List.of(
                        Map.of("op", "replace", "path", "displayName", "value", "Alice Baker"),
                        Map.of("op", "replace", "value", Map.of("active", false))))));
        awaitUser(() -> given().when().get("/users/" + ALICE_ID).then().statusCode(200)
                .body("displayName", equalTo("Alice Baker"))
                .body("active", equalTo(false))
                .body("version", equalTo("2")));

        transmit(SsfEventTypes.SCIM_PROV_ACTIVATE, Map.of());
        awaitUser(() -> given().when().get("/users/" + ALICE_ID).then().statusCode(200)
                .body("active", equalTo(true)));

        // a notice carries no data and changes nothing
        transmit(SsfEventTypes.SCIM_PROV_PUT_NOTICE, Map.of("attributes", List.of("userName")));
        transmit(SsfEventTypes.SCIM_PROV_DELETE, Map.of());
        awaitUser(() -> given().when().get("/users/" + ALICE_ID).then().statusCode(404));
    }

    @Test
    void ignoresGroups() {
        transmit(SsfEventTypes.SCIM_PROV_CREATE_FULL, "/Groups/176f397ec4c44b94b2cfcb759780b8c2", "crmUsers",
                Map.of("data", Map.of("displayName", "crmUsers")));
        transmit(SsfEventTypes.SCIM_PROV_CREATE_FULL, "/Users/44f6142df96bd6ab61e7521d9", null,
                Map.of("data", Map.of("userName", "jdoe")));
        awaitUser(() -> given().when().get("/users/44f6142df96bd6ab61e7521d9").then().statusCode(200)
                .body("userName", equalTo("jdoe")));
        given().when().get("/users/176f397ec4c44b94b2cfcb759780b8c2").then().statusCode(404);
    }

    private static void awaitUser(Runnable assertion) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(assertion::run);
    }

    private void transmit(String eventType, Map<String, Object> payload) {
        transmit(eventType, ALICE, "alice", payload);
    }

    @SuppressWarnings("unchecked")
    private void transmit(String eventType, String uri, String externalId, Map<String, Object> payload) {
        List<String> audience = (List<String>) transmitter.streams().get(0).get("aud");
        JWTClaimsSet claims = transmitter.setClaims(eventType, scim(uri, externalId), payload)
                .audience(audience)
                .build();
        transmitter.queueSet(transmitter.signSet(claims));
    }
}
