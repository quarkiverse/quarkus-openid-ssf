package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.nimbusds.jwt.SignedJWT;

import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPollScheduler;
import io.quarkus.test.QuarkusUnitTest;

/**
 * {@code allow-insecure-http} reaches the poller too: a poll endpoint with plain
 * {@code http} on a host that is not a loopback address is polled. {@code 0.0.0.0} is
 * not a loopback address for the URI policy, but connects to the test transmitter
 * listening on {@code 127.0.0.1}.
 */
public class PollEndpointAllowInsecureTest {

    private static final String POLL_URI_PROPERTY = "test.ssf.default.poll-uri";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive(CapturingHandler.class))
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                System.setProperty(POLL_URI_PROPERTY, transmitter.pollUri().replace("127.0.0.1", "0.0.0.0"));
            })
            .setAfterAllCustomizer(() -> {
                TestTransmitters.stop();
                System.clearProperty(POLL_URI_PROPERTY);
            })
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "POLL")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.endpoint-url", TestTransmitters.ref(POLL_URI_PROPERTY))
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.auto-start", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.allow-insecure-http", "true")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN)
            .overrideConfigKey("quarkus.openid-ssf.receiver.dedup.enabled", "false");

    @Inject
    SsfPollScheduler scheduler;

    @Inject
    SsfEventHandler handler;

    @Test
    @DisplayName("allow-insecure-http -> a poll endpoint with plain http on a remote host is polled")
    void polled() throws Exception {
        String set = TestTransmitters.current().set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        TestTransmitters.current().queueSet(set);

        assertEquals(1, scheduler.pollNow());

        String jti = SignedJWT.parse(set).getJWTClaimsSet().getJWTID();
        assertEquals(1, ((CapturingHandler) handler).captured.size());
        assertThat(TestTransmitters.current().acknowledgedSets(), hasItem(jti));
    }

    @Singleton
    public static class CapturingHandler implements SsfEventHandler {
        final List<String> captured = new CopyOnWriteArrayList<>();

        @Override
        public void handle(SsfEventContext eventContext) {
            captured.add(eventContext.eventToken().jti());
        }
    }
}
