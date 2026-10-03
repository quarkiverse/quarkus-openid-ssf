package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * The push endpoint honors {@code push.expected-auth-header} exactly and answers with
 * the RFC 8935 error documents. A failing handler yields {@code 500} so that the
 * transmitter delivers the SET again.
 */
public class PushRouteAuthTest {

    private static final String SHARED_SECRET = "Bearer s3cret-shared";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> TestTransmitters.archive(ThrowingHandler.class))
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
            .overrideConfigKey("quarkus.openid-ssf.receiver.push.expected-auth-header", SHARED_SECRET)
            .overrideConfigKey("quarkus.openid-ssf.receiver.push.endpoint-path", "/ssf/push")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN)
            // the same SET is posted several times
            .overrideConfigKey("quarkus.openid-ssf.receiver.dedup.enabled", "false");

    @Inject
    SsfEventHandler handler;

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    @BeforeEach
    void resetHandler() {
        ((ThrowingHandler) handler).reset();
    }

    private static String validSet() {
        return TestTransmitters.current().set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
    }

    @Test
    @DisplayName("Correct Authorization header -> 202, handler invoked")
    void correctAuthAccepts() {
        given().header("Content-Type", "application/secevent+jwt").header("Authorization", SHARED_SECRET)
                .body(validSet())
                .when().post("/ssf/push")
                .then().statusCode(202);
        assertEquals(1, ((ThrowingHandler) handler).invocations.get());
    }

    @Test
    @DisplayName("Wrong Authorization header -> 401 authentication_failed, handler not invoked")
    void wrongAuthRejects() {
        given().header("Content-Type", "application/secevent+jwt").header("Authorization", "Bearer wrong-secret")
                .body(validSet())
                .when().post("/ssf/push")
                .then().statusCode(401)
                .contentType("application/json")
                .body("err", equalTo("authentication_failed"));
        assertEquals(0, ((ThrowingHandler) handler).invocations.get());
    }

    @Test
    @DisplayName("Missing Authorization header -> 401")
    void missingAuthRejects() {
        given().header("Content-Type", "application/secevent+jwt").body(validSet())
                .when().post("/ssf/push")
                .then().statusCode(401)
                .body("err", equalTo("authentication_failed"));
    }

    @Test
    @DisplayName("Handler throws -> 500, the transmitter delivers the SET again")
    void handlerThrowsYields500() {
        ((ThrowingHandler) handler).throwOnNext.set(true);
        given().header("Content-Type", "application/secevent+jwt").header("Authorization", SHARED_SECRET)
                .body(validSet())
                .when().post("/ssf/push")
                .then().statusCode(500);
        assertEquals(1, ((ThrowingHandler) handler).invocations.get());
    }

    @Singleton
    public static class ThrowingHandler implements SsfEventHandler {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicBoolean throwOnNext = new AtomicBoolean(false);

        @Override
        public void handle(SsfEventContext eventContext) {
            invocations.incrementAndGet();
            if (throwOnNext.compareAndSet(true, false)) {
                throw new RuntimeException("handler intentionally throws");
            }
        }

        void reset() {
            invocations.set(0);
            throwOnNext.set(false);
        }
    }
}
