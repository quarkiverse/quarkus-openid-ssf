package io.quarkiverse.ssf.receiver.example.resourceserver;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * End to end against the test transmitter: the API accepts an access token until a
 * {@code session-revoked} event for its session was pushed, the stream was registered on
 * startup with the authorization header the push endpoint expects, and the metrics show
 * the SETs. {@link ResourceServerIT} runs the same against the native binary.
 */
@QuarkusTest
@QuarkusTestResource(TestTransmitterResource.class)
public class ResourceServerTest {

    private static final String PUSH_AUTHORIZATION = "Bearer example-resource-server-push-secret";

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    @Test
    void accessTokenIsRejectedOnceItsSessionWasRevoked() {
        String accessToken = System.getProperty(TestTransmitterResource.PROP_ACCESS_TOKEN);

        given().header("Authorization", "Bearer " + accessToken)
                .when().get("/api/me")
                .then().statusCode(200)
                .body("subject", equalTo(TestTransmitterResource.USER))
                .body("sessionId", equalTo(TestTransmitterResource.SESSION));

        // the stream was registered in the background; the push endpoint works regardless
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> given()
                        .when().get("/q/health/well")
                        .then().statusCode(200)
                        .body(containsString("\"streamRegistration\": \"registered\"")));

        // another session of the user is revoked: the token stays valid
        push(System.getProperty(TestTransmitterResource.PROP_OTHER_SESSION_REVOKED_SET));
        given().header("Authorization", "Bearer " + accessToken)
                .when().get("/api/me")
                .then().statusCode(200);

        // the session of the token is revoked: the token has not expired, but is rejected
        push(System.getProperty(TestTransmitterResource.PROP_SESSION_REVOKED_SET));
        given().header("Authorization", "Bearer " + accessToken)
                .when().get("/api/me")
                .then().statusCode(401);

        given()
                .when().get("/q/metrics")
                .then().statusCode(200)
                .body(containsString("easyssf_receiver_sets_total"));
    }

    @Test
    void pushEndpointRequiresTheAuthorizationHeaderOfTheStream() {
        given().header("Content-Type", "application/secevent+jwt")
                .body(System.getProperty(TestTransmitterResource.PROP_OTHER_SESSION_REVOKED_SET))
                .when().post("/ssf/push")
                .then().statusCode(401)
                .body("err", equalTo("authentication_failed"));
    }

    private static void push(String set) {
        given().header("Content-Type", "application/secevent+jwt").header("Authorization", PUSH_AUTHORIZATION)
                .body(set)
                .when().post("/ssf/push")
                .then().statusCode(202);
    }
}
