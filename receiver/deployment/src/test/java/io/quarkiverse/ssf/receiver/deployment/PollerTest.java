package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

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

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jwt.SignedJWT;

import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPollScheduler;
import io.quarkus.test.QuarkusUnitTest;

/**
 * POLL delivery (RFC 8936) driven synchronously with {@link SsfPollScheduler#pollNow()}
 * ({@code poll.auto-start=false}) against the poll endpoint of the test transmitter: a
 * handled SET is acknowledged, an invalid one is reported as an error, one the handler
 * could not process is left for the next poll.
 */
public class PollerTest {

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
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.auto-start", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.max-events", "10")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN)
            .overrideConfigKey("quarkus.openid-ssf.receiver.dedup.enabled", "false");

    @Inject
    SsfPollScheduler scheduler;

    @Inject
    SsfTransmitters transmitters;

    @Inject
    SsfEventHandler handler;

    @BeforeEach
    void reset() {
        // the poll endpoint comes from the stream, which is looked up on startup
        TestTransmitters.awaitRegistered(transmitters.primary().orElseThrow());
        TestTransmitter transmitter = TestTransmitters.current();
        transmitter.acknowledgedSets().clear();
        transmitter.reportedErrors().clear();
        transmitter.setAvailable(true);
        ((CapturingHandler) handler).reset();
    }

    private static String mintSet() {
        return TestTransmitters.current().set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
    }

    private static String jtiOf(String set) throws Exception {
        return SignedJWT.parse(set).getJWTClaimsSet().getJWTID();
    }

    @Test
    @DisplayName("poll.auto-start=false -> the poller thread is not started")
    void notStarted() {
        SsfPoller poller = transmitters.primary().orElseThrow().getPoller();
        assertThat(poller.isRunning(), is(false));
        assertThat(poller.isLongPolling(), is(false));
    }

    @Test
    @DisplayName("Nothing queued -> handler not invoked, nothing acknowledged")
    void emptyResponse() {
        assertEquals(0, scheduler.pollNow());
        assertThat(((CapturingHandler) handler).captured, is(empty()));
        assertThat(TestTransmitters.current().acknowledgedSets(), is(empty()));
    }

    @Test
    @DisplayName("Single SET -> handler invoked once, jti acknowledged right away")
    void singleSet() throws Exception {
        String set = mintSet();
        String jti = jtiOf(set);
        TestTransmitters.current().queueSet(set);

        assertEquals(1, scheduler.pollNow());

        List<SsfEventToken> captured = ((CapturingHandler) handler).captured;
        assertEquals(1, captured.size());
        assertThat(captured.get(0).jti(), equalTo(jti));
        assertThat(captured.get(0).iss(), equalTo(TestTransmitters.current().issuer()));
        assertThat(TestTransmitters.current().acknowledgedSets(), hasItem(jti));

        // acknowledged SETs are gone from the queue
        assertEquals(0, scheduler.pollNow());
    }

    @Test
    @DisplayName("More SETs than max-events -> fetched in several requests of one poll, all acknowledged")
    void drainsTheQueue() throws Exception {
        List<String> jtis = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String set = mintSet();
            jtis.add(jtiOf(set));
            TestTransmitters.current().queueSet(set);
        }

        assertEquals(15, scheduler.pollNow());

        List<String> handled = ((CapturingHandler) handler).captured.stream().map(SsfEventToken::jti).toList();
        assertThat(handled, containsInAnyOrder(jtis.toArray()));
        assertThat(TestTransmitters.current().acknowledgedSets(), containsInAnyOrder(jtis.toArray()));
    }

    @Test
    @DisplayName("Bad signature -> handler not invoked, jti reported as an error instead of acknowledged")
    void badSignatureSet() throws Exception {
        TestTransmitter transmitter = TestTransmitters.current();
        String bad = TestTransmitter.sign(TestTransmitter.generateKey(2048, transmitter.key().getKeyID()),
                new JOSEObjectType("secevent+jwt"),
                transmitter.setClaims("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234")).build());
        String jti = jtiOf(bad);
        transmitter.queueSet(bad);

        scheduler.pollNow();

        assertThat(((CapturingHandler) handler).captured, is(empty()));
        assertThat(transmitter.acknowledgedSets(), not(hasItem(jti)));
        assertThat(transmitter.reportedErrors(), hasKey(jti));
        assertThat(((Map<?, ?>) transmitter.reportedErrors().get(jti)).get("err"), equalTo("invalid_key"));
    }

    @Test
    @DisplayName("Handler throws -> jti not acknowledged, delivered again with the next poll")
    void handlerThrowsLeavesJtiUnacked() throws Exception {
        String set = mintSet();
        String jti = jtiOf(set);
        TestTransmitters.current().queueSet(set);
        ((CapturingHandler) handler).throwOnNext.set(true);

        scheduler.pollNow();

        CapturingHandler capturing = (CapturingHandler) handler;
        assertEquals(1, capturing.invocations.get(), "handler should have been invoked once");
        assertThat(TestTransmitters.current().acknowledgedSets(), not(hasItem(jti)));

        // the transmitter still has the SET and delivers it again
        assertEquals(1, scheduler.pollNow());
        assertEquals(2, capturing.invocations.get());
        assertThat(TestTransmitters.current().acknowledgedSets(), hasItem(jti));
    }

    @Test
    @DisplayName("Transmitter unavailable -> the poll fails and is reported, the next poll succeeds")
    void transmitterUnavailable() throws Exception {
        TestTransmitter transmitter = TestTransmitters.current();
        SsfPoller poller = transmitters.primary().orElseThrow().getPoller();
        transmitter.setAvailable(false);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> scheduler.pollNow());
        assertThat(failure.getMessage(), containsString("503"));
        assertThat(poller.getLastPollError(), is(notNullValue()));

        transmitter.setAvailable(true);
        String set = mintSet();
        transmitter.queueSet(set);
        assertEquals(1, scheduler.pollNow());
        assertThat(poller.getLastPollError(), is(nullValue()));
        assertThat(transmitter.acknowledgedSets(), hasItem(jtiOf(set)));
    }

    @Test
    @DisplayName("pollNow of an unknown transmitter name -> IllegalStateException")
    void unknownTransmitter() {
        assertThrows(IllegalStateException.class, () -> scheduler.pollNow("nope"));
    }

    @Singleton
    public static class CapturingHandler implements SsfEventHandler {
        final List<SsfEventToken> captured = new CopyOnWriteArrayList<>();
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicBoolean throwOnNext = new AtomicBoolean(false);

        @Override
        public void handle(SsfEventContext eventContext) {
            invocations.incrementAndGet();
            if (throwOnNext.compareAndSet(true, false)) {
                throw new RuntimeException("CapturingHandler intentionally throws");
            }
            captured.add(eventContext.eventToken());
        }

        void reset() {
            captured.clear();
            invocations.set(0);
            throwOnNext.set(false);
        }
    }
}
