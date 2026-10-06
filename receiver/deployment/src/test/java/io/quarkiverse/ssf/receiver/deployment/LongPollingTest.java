package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.awaitility.Awaitility;
import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.nimbusds.jwt.SignedJWT;

import io.quarkus.test.QuarkusUnitTest;

/**
 * Long polling (RFC 8936, section 2.5) with {@code poll.long-polling=true}: the poller
 * keeps one request outstanding that the transmitter holds, so a SET queued during the
 * hold arrives at once although {@code poll.interval} is far longer, and the
 * acknowledgement rides on the request that follows.
 */
public class LongPollingTest {

    private static final Duration HOLD = Duration.ofSeconds(3);

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive(CapturingHandler.class))
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                transmitter.setLongPollHold(HOLD);
                TestTransmitters.addPollStream(TestTransmitters.DEFAULT, transmitter);
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "POLL")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.long-polling", "true")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.long-polling-hold", "3s")
            // only a held request can deliver a SET within the test's patience
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.interval", "1m")
            // shorter than the hold: the request timeout of a long poll replaces it
            .overrideConfigKey("quarkus.openid-ssf.receiver.http.read-timeout", "1s")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN)
            .overrideConfigKey("quarkus.openid-ssf.receiver.dedup.enabled", "false");

    @Inject
    SsfTransmitters transmitters;

    @Inject
    SsfEventHandler handler;

    private SsfPoller poller;

    @BeforeEach
    void reset() {
        TestTransmitters.awaitRegistered(transmitters.primary().orElseThrow());
        poller = transmitters.primary().orElseThrow().getPoller();
        ((CapturingHandler) handler).captured.clear();
    }

    @Test
    @DisplayName("poll.long-polling=true -> the started poller keeps a held request outstanding, from the registration on")
    void holdsARequest() {
        assertTrue(poller.isLongPolling());
        assertTrue(poller.isRunning());
        TestTransmitter transmitter = TestTransmitters.current();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.pollRequests() > 0);
        assertThat(transmitter.lastPollRequest().get("returnImmediately"), equalTo(Boolean.FALSE));
    }

    @Test
    @DisplayName("A SET queued during the hold arrives at once, long before the interval")
    void deliversDuringTheHold() throws Exception {
        TestTransmitter transmitter = TestTransmitters.current();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.pollRequests() > 0);
        String set = transmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        String jti = SignedJWT.parse(set).getJWTClaimsSet().getJWTID();
        Instant queued = Instant.now();
        transmitter.queueSet(set);

        List<SsfEventToken> captured = ((CapturingHandler) handler).captured;
        Awaitility.await().atMost(HOLD.plusSeconds(2)).until(() -> !captured.isEmpty());
        assertEquals(jti, captured.get(0).jti());
        assertTrue(Duration.between(queued, Instant.now()).compareTo(Duration.ofSeconds(10)) < 0,
                "the SET arrived with the held request, not after poll.interval");

        // the acknowledgement rides on the next request, which goes out at once, and leaves
        // the store once the transmitter answered
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.acknowledgedSets().contains(jti));
        assertThat(transmitter.acknowledgedSets(), hasItem(jti));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> poller.getPendingAckCount() == 0);
    }

    @Singleton
    public static class CapturingHandler implements SsfEventHandler {
        final List<SsfEventToken> captured = new CopyOnWriteArrayList<>();

        @Override
        public void handle(SsfEventContext eventContext) {
            captured.add(eventContext.eventToken());
        }
    }
}
