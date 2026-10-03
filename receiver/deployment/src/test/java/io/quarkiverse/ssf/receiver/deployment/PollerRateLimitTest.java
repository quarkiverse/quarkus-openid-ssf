package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.awaitility.Awaitility;
import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.nimbusds.jwt.SignedJWT;

import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPollScheduler;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A rate-limited poll endpoint (GH-13): a {@code 429} opens a pause driven by
 * {@code Retry-After}, falling back to {@code poll.rate-limit.fallback-backoff} without
 * the header and never exceeding {@code poll.rate-limit.max-backoff}. Polls inside the
 * pause send nothing; a pending acknowledgement survives and is sent afterwards.
 *
 * <p>
 * The test transmitter never throttles, so an {@link SsfHttpClient} bean of the test
 * stands in for the default one and answers the poll request with the status the test
 * asks for. Which also shows that the HTTP client of the extension can be replaced.
 */
public class PollerRateLimitTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive(ThrottlingHttpClient.class))
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
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.interval", "2s")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.rate-limit.fallback-backoff", "1500ms")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.rate-limit.max-backoff", "4s")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN)
            .overrideConfigKey("quarkus.openid-ssf.receiver.dedup.enabled", "false");

    @Inject
    SsfPollScheduler scheduler;

    @Inject
    SsfTransmitters transmitters;

    @Inject
    SsfHttpClient httpClient;

    private SsfPoller poller;

    private ThrottlingHttpClient throttling;

    @BeforeEach
    void reset() {
        TestTransmitters.awaitRegistered(transmitters.primary().orElseThrow());
        poller = transmitters.primary().orElseThrow().getPoller();
        throttling = (ThrottlingHttpClient) httpClient;
        throttling.reset();
        TestTransmitters.current().acknowledgedSets().clear();
        // the poller is shared by the test methods: let a pause of the previous one expire
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !paused());
    }

    private boolean paused() {
        return poller.getPausedUntil().isAfter(Instant.now());
    }

    private Duration remainingPause() {
        return Duration.between(Instant.now(), poller.getPausedUntil());
    }

    private void pollExpectingFailure() {
        assertThrows(IllegalStateException.class, () -> scheduler.pollNow());
    }

    @Test
    @DisplayName("429 + Retry-After -> polls skipped until the deadline, the pending ack is re-sent afterwards")
    void retryAfterIsHonored() throws Exception {
        TestTransmitter transmitter = TestTransmitters.current();
        String set = transmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        String jti = SignedJWT.parse(set).getJWTClaimsSet().getJWTID();
        transmitter.queueSet(set);

        // the first request fetches the SET, the second one (the acknowledgement) is throttled
        throttling.throttle(1, 429, "1");
        pollExpectingFailure();
        assertTrue(paused(), "a pause should be open");
        assertThat(remainingPause(), lessThanOrEqualTo(Duration.ofSeconds(1)));
        assertThat(transmitter.acknowledgedSets(), org.hamcrest.Matchers.not(hasItem(jti)));

        // inside the pause: nothing is sent
        int requests = throttling.pollRequests.get();
        assertEquals(0, scheduler.pollNow());
        assertEquals(requests, throttling.pollRequests.get(), "no request may be sent while pausing");

        // after the pause the acknowledgement goes out
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !paused());
        scheduler.pollNow();
        assertThat(transmitter.acknowledgedSets(), hasItem(jti));
    }

    @Test
    @DisplayName("429 without Retry-After -> pauses for fallback-backoff")
    void fallsBackToConfiguredBackoff() {
        throttling.throttle(0, 429, null);
        pollExpectingFailure();
        assertTrue(paused());
        assertThat(remainingPause(), greaterThan(Duration.ofMillis(500)));
        assertThat(remainingPause(), lessThanOrEqualTo(Duration.ofMillis(1500)));
    }

    @Test
    @DisplayName("Retry-After beyond max-backoff is capped")
    void retryAfterIsCapped() {
        throttling.throttle(0, 429, "3600");
        pollExpectingFailure();
        assertTrue(paused());
        assertThat(remainingPause(), greaterThan(Duration.ofSeconds(2)));
        assertThat(remainingPause(), lessThanOrEqualTo(Duration.ofSeconds(4)));
    }

    @Test
    @DisplayName("503 + Retry-After is honored too")
    void serviceUnavailableWithRetryAfter() {
        throttling.throttle(0, 503, "1");
        pollExpectingFailure();
        assertTrue(paused());
        assertThat(remainingPause(), lessThanOrEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("503 without Retry-After is an ordinary failure, no pause")
    void serviceUnavailableWithoutRetryAfter() {
        throttling.throttle(0, 503, null);
        pollExpectingFailure();
        assertFalse(paused(), "a plain 503 must not open a pause");

        int requests = throttling.pollRequests.get();
        scheduler.pollNow();
        assertEquals(requests + 1, throttling.pollRequests.get(), "the next poll goes straight out");
    }

    /**
     * Replaces the {@code SsfHttpClient} of the extension: passes requests on to the JDK
     * client, except for the poll requests it was told to throttle.
     */
    @Singleton
    public static class ThrottlingHttpClient implements SsfHttpClient {
        private final SsfHttpClient delegate = new JdkSsfHttpClient();
        final AtomicInteger pollRequests = new AtomicInteger();
        private final AtomicInteger passThrough = new AtomicInteger();
        private final AtomicReference<SsfHttpResponse> throttled = new AtomicReference<>();

        /** The next {@code passThrough} poll requests go out, the one after is throttled. */
        void throttle(int passThrough, int status, String retryAfter) {
            this.passThrough.set(passThrough);
            Map<String, List<String>> headers = (retryAfter != null) ? Map.of("Retry-After", List.of(retryAfter))
                    : Map.of();
            throttled.set(new SsfHttpResponse(status, headers, null));
        }

        void reset() {
            pollRequests.set(0);
            passThrough.set(0);
            throttled.set(null);
        }

        @Override
        public SsfHttpResponse execute(SsfHttpRequest request) throws IOException {
            if ("POST".equals(request.method()) && request.uri().getPath().endsWith("/poll")) {
                pollRequests.incrementAndGet();
                SsfHttpResponse response = throttled.get();
                if (response != null && passThrough.getAndDecrement() <= 0) {
                    throttled.set(null);
                    return response;
                }
            }
            return delegate.execute(request);
        }
    }
}
