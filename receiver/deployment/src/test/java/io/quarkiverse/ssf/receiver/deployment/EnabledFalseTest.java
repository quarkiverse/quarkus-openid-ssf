package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPollScheduler;
import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;
import io.quarkus.test.QuarkusUnitTest;

/**
 * {@code quarkus.openid-ssf.receiver.enabled=false}: the application starts without any
 * transmitter configured, no push endpoint is registered, nothing is polled. The beans
 * stay injectable.
 */
public class EnabledFalseTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.openid-ssf.receiver.enabled", "false");

    @Inject
    SsfReceiverStreamClient streamClient;

    @Inject
    SsfPollScheduler scheduler;

    @Test
    @DisplayName("App boots without transmitter-issuer when disabled, beans are injectable")
    void appBootsWithoutMandatoryConfig() {
        assertNotNull(streamClient);
        assertNotNull(scheduler);
    }

    @Test
    @DisplayName("Push endpoint is not registered -> POST /ssf/push is not accepted")
    void pushRouteNotRegistered() {
        // 404 without a route; 405 with quarkus-smallrye-health on the test classpath, whose
        // routes make the router answer that for an unmatched method. Either way no SET is taken.
        given().header("Content-Type", "text/plain").body("anything")
                .when().post("/ssf/push")
                .then().statusCode(anyOf(is(404), is(405)));
    }

    @Test
    @DisplayName("pollNow() names the kill switch")
    void pollNowRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> scheduler.pollNow());
        assertThat(ex.getMessage(), containsString("quarkus.openid-ssf.receiver.enabled"));
    }
}
