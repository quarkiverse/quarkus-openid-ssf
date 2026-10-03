package io.quarkiverse.ssf.receiver.example.receivermanaged;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

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
 * End to end against the test transmitter: the receiver registers its stream on
 * startup, a pushed SET is verified, handled and shows up at {@code /events/latest}.
 * {@link SsfReceiverIT} runs the same against the native binary.
 */
@QuarkusTest
@QuarkusTestResource(TestTransmitterResource.class)
public class SsfReceiverTest {

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    @Test
    void streamIsRegisteredAndPushedSetIsHandled() {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> given()
                        .when().get("/transmitter/registration")
                        .then().statusCode(200)
                        .body("registration", equalTo("REGISTERED"))
                        .body("streamId", notNullValue()));

        given().header("Content-Type", "application/secevent+jwt")
                .body(System.getProperty(TestTransmitterResource.PROP_SET))
                .when().post("/ssf/push")
                .then().statusCode(202);

        given()
                .when().get("/events/latest")
                .then().statusCode(200)
                .body("jti", equalTo(System.getProperty(TestTransmitterResource.PROP_JTI)))
                .body("transmitter", equalTo("default"))
                .body("events.CaepSessionRevoked", notNullValue());

        given()
                .when().get("/streams/default/status")
                .then().statusCode(200)
                .body("status", equalTo("enabled"));

        given()
                .when().get("/transmitter/metadata")
                .then().statusCode(200)
                .body("jwks_uri", notNullValue());
    }
}
