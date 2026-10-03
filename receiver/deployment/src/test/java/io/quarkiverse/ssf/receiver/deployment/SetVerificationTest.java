package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * Every verification failure of a pushed SET answers {@code 400} with the RFC 8935 error
 * document, through the configured {@code set-validation.*} and the verifier of easyssf.
 */
public class SetVerificationTest {

    private static final JOSEObjectType SECEVENT = new JOSEObjectType("secevent+jwt");

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> TestTransmitters.archive())
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                TestTransmitters.addPushStream(TestTransmitters.DEFAULT, transmitter);
                // a 1024 bit key the transmitter publishes but the receiver must not accept
                transmitter.publishJwks(transmitter.key(), TestTransmitter.generateKey(1024, "weak-key"));
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "PUSH")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    private static JWTClaimsSet.Builder claims() {
        return TestTransmitters.current().setClaims("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
    }

    private static void postAndExpect400(String set, String errorCode) {
        given().header("Content-Type", "application/secevent+jwt").body(set)
                .when().post("/ssf/push")
                .then().statusCode(400)
                .contentType("application/json")
                .header("Content-Language", "en")
                .body("err", equalTo(errorCode));
    }

    @Test
    @DisplayName("SET signed by another key with the same kid -> 400 invalid_key")
    void badSignature() {
        RSAKey attackerKey = TestTransmitter.generateKey(2048, TestTransmitters.current().key().getKeyID());
        postAndExpect400(TestTransmitter.sign(attackerKey, SECEVENT, claims().build()), "invalid_key");
    }

    @Test
    @DisplayName("SET with a kid not in the JWK Set -> 400 invalid_key")
    void unknownKid() {
        RSAKey unknownKey = TestTransmitter.generateKey(2048, "unknown-kid-not-in-jwks");
        postAndExpect400(TestTransmitter.sign(unknownKey, SECEVENT, claims().build()), "invalid_key");
    }

    @Test
    @DisplayName("SET signed with a 1024 bit key the JWK Set publishes -> 400 (min-rsa-key-size)")
    void weakKeyRejected() {
        RSAKey weakKey = TestTransmitter.generateKey(1024, "weak-key");
        TestTransmitters.current().publishJwks(TestTransmitters.current().key(), weakKey);
        given().header("Content-Type", "application/secevent+jwt")
                .body(TestTransmitter.sign(weakKey, SECEVENT, claims().build()))
                .when().post("/ssf/push")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("SET without iss -> 400 invalid_request (no transmitter to route it to)")
    void missingIss() {
        postAndExpect400(TestTransmitters.current().signSet(claims().issuer(null).build()), "invalid_request");
    }

    @Test
    @DisplayName("SET with another iss than transmitter-issuer -> 400 invalid_issuer")
    void wrongIss() {
        postAndExpect400(TestTransmitters.current().signSet(claims().issuer("https://attacker.example/realms/evil").build()),
                "invalid_issuer");
    }

    @Test
    @DisplayName("SET without iat -> 400 invalid_request")
    void missingIat() {
        postAndExpect400(TestTransmitters.current().signSet(claims().issueTime(null).build()), "invalid_request");
    }

    @Test
    @DisplayName("SET without jti -> 400 invalid_request")
    void missingJti() {
        postAndExpect400(TestTransmitters.current().signSet(claims().jwtID(null).build()), "invalid_request");
    }

    @Test
    @DisplayName("SET without events -> 400 invalid_request")
    void missingEvents() {
        postAndExpect400(TestTransmitters.current().signSet(claims().claim("events", null).build()), "invalid_request");
    }

    @Test
    @DisplayName("SET without the secevent+jwt typ header -> 400 invalid_request (require-type-header)")
    void missingTypeHeader() {
        TestTransmitter transmitter = TestTransmitters.current();
        postAndExpect400(TestTransmitter.sign(transmitter.key(), JOSEObjectType.JWT, claims().build()), "invalid_request");
    }

    @Test
    @DisplayName("SET signed with RS384 -> 400 (accepted-algorithms is RS256 only)")
    void disallowedAlgorithmRejected() {
        TestTransmitter transmitter = TestTransmitters.current();
        given().header("Content-Type", "application/secevent+jwt")
                .body(TestTransmitter.sign(transmitter.key(), JWSAlgorithm.RS384, SECEVENT, claims().build()))
                .when().post("/ssf/push")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("SET with a wrong audience -> 400 invalid_audience")
    void wrongAudience() {
        postAndExpect400(TestTransmitters.current().signSet(claims().audience("https://other.example").build()),
                "invalid_audience");
    }

    @Test
    @DisplayName("Empty body -> 400 invalid_request")
    void emptyBody() {
        postAndExpect400("", "invalid_request");
    }

    @Test
    @DisplayName("Body that is not a JWT -> 400 invalid_request")
    void notAJwt() {
        postAndExpect400("definitely-not-a-jwt", "invalid_request");
    }
}
