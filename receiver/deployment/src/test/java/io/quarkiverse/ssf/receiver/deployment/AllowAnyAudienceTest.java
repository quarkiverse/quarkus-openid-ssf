package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * {@code set-validation.allow-any-audience=true} lets a receiver that knows neither an
 * {@code expected-audience} nor a stream start, and accept SETs whatever their
 * {@code aud}, for a transmitter that sends none.
 */
public class AllowAnyAudienceTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive())
            .setBeforeAllCustomizer(TestTransmitters::start)
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.set-validation.allow-any-audience", "true")
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "PUSH");

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    private static void postAndExpect202(String set) {
        given().header("Content-Type", "application/secevent+jwt").body(set)
                .when().post("/ssf/push")
                .then().statusCode(202);
    }

    @Test
    @DisplayName("allow-any-audience -> SETs for this and for other receivers are accepted")
    void anyAudienceIsAccepted() {
        TestTransmitter transmitter = TestTransmitters.current();
        postAndExpect202(transmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234")));
        postAndExpect202(transmitter.signSet(transmitter
                .setClaims("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"))
                .audience("https://other-receiver.example").build()));
        postAndExpect202(transmitter.signSet(transmitter
                .setClaims("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"))
                .audience((String) null).build()));
    }
}
