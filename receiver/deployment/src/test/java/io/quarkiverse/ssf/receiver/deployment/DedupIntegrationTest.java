package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * The in-memory de-duplication store skips a SET that was processed before: the
 * transmitter still gets its {@code 202}, the handler is not invoked again.
 */
public class DedupIntegrationTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> TestTransmitters.archive(CountingHandler.class))
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
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN)
            .overrideConfigKey("quarkus.openid-ssf.receiver.dedup.enabled", "true");

    @Inject
    SsfEventHandler handler;

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    private static void postSet(String set) {
        given().header("Content-Type", "application/secevent+jwt").body(set)
                .when().post("/ssf/push")
                .then().statusCode(202);
    }

    @Test
    @DisplayName("Same SET posted twice -> handler invoked exactly once, 202 both times")
    void duplicateSkipped() {
        String set = TestTransmitters.current().set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        CountingHandler counting = (CountingHandler) handler;
        counting.invocations.set(0);

        postSet(set);
        postSet(set);

        assertEquals(1, counting.invocations.get(), "handler must be invoked once for two posts of the same SET");
    }

    @Singleton
    public static class CountingHandler implements SsfEventHandler {
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public void handle(SsfEventContext eventContext) {
            invocations.incrementAndGet();
        }
    }
}
