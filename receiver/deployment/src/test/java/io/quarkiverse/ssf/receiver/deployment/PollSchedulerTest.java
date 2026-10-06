package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.awaitility.Awaitility;
import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.poll.InMemorySsfPollAckStore;
import org.easyssf.receiver.poll.SsfPollAckStore;
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
 * Periodic polling with {@code poll.auto-start=true} (the default): the poller of the
 * transmitter runs on its own thread, asks the transmitter every {@code poll.interval}
 * with a request answered immediately, and a queued SET reaches the handler without the
 * application doing anything. With {@code jdbc.enabled=false} the acknowledgements wait
 * in memory.
 */
public class PollSchedulerTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive(CapturingHandler.class))
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
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
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.interval", "500ms")
            // Dev Services would start an H2 datasource otherwise, and the stores would be JDBC ones
            .overrideConfigKey("quarkus.openid-ssf.receiver.jdbc.enabled", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN)
            .overrideConfigKey("quarkus.openid-ssf.receiver.dedup.enabled", "false");

    @Inject
    SsfTransmitters transmitters;

    @Inject
    SsfPollAckStore ackStore;

    @Inject
    SsfEventHandler handler;

    private SsfPoller poller;

    @BeforeEach
    void reset() {
        TestTransmitters.awaitRegistered(transmitters.primary().orElseThrow());
        poller = transmitters.primary().orElseThrow().getPoller();
        TestTransmitters.current().acknowledgedSets().clear();
        ((CapturingHandler) handler).captured.clear();
    }

    @Test
    @DisplayName("The poller runs on its own thread and polls periodically with immediate requests")
    void pollsPeriodically() {
        assertTrue(poller.isRunning(), "poll.auto-start=true starts the poller");
        assertFalse(poller.isLongPolling());
        TestTransmitter transmitter = TestTransmitters.current();
        int requests = transmitter.pollRequests();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.pollRequests() > requests + 1);
        assertThat(transmitter.lastPollRequest().get("returnImmediately"), equalTo(Boolean.TRUE));
    }

    @Test
    @DisplayName("A queued SET reaches the handler and is acknowledged without the application polling")
    void deliversQueuedSet() throws Exception {
        TestTransmitter transmitter = TestTransmitters.current();
        String set = transmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        String jti = SignedJWT.parse(set).getJWTClaimsSet().getJWTID();
        transmitter.queueSet(set);

        List<SsfEventToken> captured = ((CapturingHandler) handler).captured;
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !captured.isEmpty());
        assertEquals(jti, captured.get(0).jti());
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.acknowledgedSets().contains(jti));
        assertThat(transmitter.acknowledgedSets(), hasItem(jti));
        assertEquals(0, poller.getPendingAckCount(), "the acknowledgement went out");
    }

    @Test
    @DisplayName("jdbc.enabled=false -> the acknowledgements wait in memory")
    void inMemoryAckStore() {
        assertThat(ackStore, instanceOf(InMemorySsfPollAckStore.class));
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
