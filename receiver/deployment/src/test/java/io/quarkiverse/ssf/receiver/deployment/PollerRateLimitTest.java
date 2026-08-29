package io.quarkiverse.ssf.receiver.deployment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.jwk.RSAKey;

import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPoller;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventContext;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventHandler;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Layer-2 test for rate-limited poll endpoints (GH-13): a {@code 429} must open
 * a backoff window driven by {@code Retry-After}, falling back to
 * {@code poll.interval} without the header and never exceeding
 * {@code poll.rate-limit.max-backoff}. Polls inside the window are no-ops;
 * pending acks survive and are re-sent once the window closes.
 *
 * <p>
 * Timing knobs are deliberately tiny (interval 2s, max-backoff 4s) so each
 * bucket — {@code Retry-After: 1} ≤ 1s, fallback ∈ (1s, 2s], cap ∈ (2s, 4s] — is
 * distinguishable by inspecting {@link SsfPoller#rateLimitRemaining()}.
 */
public class PollerRateLimitTest {

    private static final String ISSUER = "https://test.transmitter/realms/r1";
    private static final String AUDIENCE = "https://my-receiver.example/ssf";
    private static final String POLL_PATH = JwksWireMock.POLL_PATH;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(NoopHandler.class, JwksWireMock.class))
            .setBeforeAllCustomizer(
                    () -> JwksWireMock.start(ISSUER, AUDIENCE, JwksWireMock.DeliveryMode.POLL))
            .setAfterAllCustomizer(JwksWireMock::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer", ISSUER)
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id", "stream-1")
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "POLL")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.auto-start", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.interval", "2s")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.rate-limit.max-backoff", "4s")
            .overrideConfigKey("quarkus.openid-ssf.receiver.dedup.enabled", "false");

    @Inject
    SsfPoller poller;

    private WireMock wm;
    private RSAKey signingKey;
    private final List<StubMapping> registeredStubs = new ArrayList<>();

    @BeforeEach
    void resetWireMock() throws Exception {
        int port = Integer.parseInt(System.getProperty(JwksWireMock.PROP_WIREMOCK_PORT));
        wm = new WireMock("localhost", port);
        for (StubMapping s : registeredStubs) {
            wm.removeStubMapping(s);
        }
        registeredStubs.clear();
        wm.resetRequests();
        signingKey = RSAKey.parse(System.getProperty(JwksWireMock.PROP_PRIVATE_JWK));
        // The poller is a singleton shared across test methods — let any
        // backoff window left behind by the previous method expire first.
        awaitBackoffCleared();
    }

    @Test
    @DisplayName("429 + Retry-After → polls skipped until the deadline, pending acks re-sent afterwards")
    void retryAfterIsHonored() throws Exception {
        // Cycle 1: one SET → handled → ack queued.
        String setJwt = mintSet();
        String jti = com.nimbusds.jwt.SignedJWT.parse(setJwt).getJWTClaimsSet().getJWTID();
        stub(ok(pollResponseJson(Map.of(jti, setJwt))));
        poller.pollNow();

        // Cycle 2: transmitter throttles us for 1s.
        StubMapping throttled = stub(aResponse().withStatus(429).withHeader("Retry-After", "1"));
        poller.pollNow();
        Optional<Duration> remaining = poller.rateLimitRemaining();
        assertTrue(remaining.isPresent(), "a backoff window should be open");
        assertThat(remaining.get(), lessThanOrEqualTo(Duration.ofSeconds(1)));

        // Inside the window: pollNow() is a no-op — no request hits the transmitter.
        poller.pollNow();
        assertEquals(2, pollRequests().size(), "no request may be sent while backing off");

        // Window closes → the ack that was in flight during the 429 is retried.
        unstub(throttled);
        stub(ok(pollResponseJson(Map.of())));
        awaitBackoffCleared();
        poller.pollNow();

        List<LoggedRequest> reqs = pollRequests();
        assertEquals(3, reqs.size());
        assertThat(acksInRequest(reqs.get(0)), is(empty()));
        assertThat(acksInRequest(reqs.get(1)), contains(jti));
        assertThat(acksInRequest(reqs.get(2)), contains(jti));
    }

    @Test
    @DisplayName("429 without Retry-After → backs off for one poll.interval")
    void fallsBackToPollInterval() {
        stub(aResponse().withStatus(429));
        poller.pollNow();

        Optional<Duration> remaining = poller.rateLimitRemaining();
        assertTrue(remaining.isPresent());
        assertThat(remaining.get(), greaterThan(Duration.ofSeconds(1)));
        assertThat(remaining.get(), lessThanOrEqualTo(Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("Retry-After beyond poll.rate-limit.max-backoff is capped")
    void retryAfterIsCapped() {
        stub(aResponse().withStatus(429).withHeader("Retry-After", "3600"));
        poller.pollNow();

        Optional<Duration> remaining = poller.rateLimitRemaining();
        assertTrue(remaining.isPresent());
        assertThat(remaining.get(), greaterThan(Duration.ofSeconds(2)));
        assertThat(remaining.get(), lessThanOrEqualTo(Duration.ofSeconds(4)));
    }

    @Test
    @DisplayName("503 + Retry-After is honored too")
    void serviceUnavailableWithRetryAfter() {
        stub(aResponse().withStatus(503).withHeader("Retry-After", "1"));
        poller.pollNow();

        Optional<Duration> remaining = poller.rateLimitRemaining();
        assertTrue(remaining.isPresent());
        assertThat(remaining.get(), lessThanOrEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("503 without Retry-After is an ordinary failure — no backoff")
    void serviceUnavailableWithoutRetryAfter() {
        stub(aResponse().withStatus(503));
        poller.pollNow();
        assertTrue(poller.rateLimitRemaining().isEmpty(), "plain 503 must not open a backoff window");

        poller.pollNow();
        assertEquals(2, pollRequests().size(), "next poll goes straight out");
    }

    // --- helpers -------------------------------------------------------------

    private void awaitBackoffCleared() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (poller.rateLimitRemaining().isPresent()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("backoff window did not clear within 10s");
            }
            Thread.sleep(25);
        }
    }

    private String mintSet() throws Exception {
        return JwksWireMock.signClaims(JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build(),
                signingKey, JwksWireMock.kid());
    }

    private static ResponseDefinitionBuilder ok(String json) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(json);
    }

    private StubMapping stub(ResponseDefinitionBuilder response) {
        StubMapping mapping = wm.register(post(urlEqualTo(POLL_PATH)).willReturn(response));
        registeredStubs.add(mapping);
        return mapping;
    }

    private void unstub(StubMapping mapping) {
        wm.removeStubMapping(mapping);
        registeredStubs.remove(mapping);
    }

    private static String pollResponseJson(Map<String, String> sets) throws Exception {
        return MAPPER.writeValueAsString(Map.of("sets", sets, "moreAvailable", false));
    }

    private List<LoggedRequest> pollRequests() {
        return wm.find(postRequestedFor(urlEqualTo(POLL_PATH)));
    }

    @SuppressWarnings("unchecked")
    private List<String> acksInRequest(LoggedRequest req) throws Exception {
        Map<String, Object> body = MAPPER.readValue(req.getBody(), Map.class);
        Object acks = body.get("ack");
        return acks == null ? List.of() : (List<String>) acks;
    }

    @Singleton
    public static class NoopHandler implements SsfEventHandler {
        @Override
        public void handle(SsfEventContext eventContext) {
            // accept everything so the jti gets acked
        }
    }
}
