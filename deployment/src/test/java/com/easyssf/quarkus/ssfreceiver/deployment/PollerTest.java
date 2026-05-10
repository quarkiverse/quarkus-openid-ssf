/*
 * Copyright 2026 Thomas Darimont and the easyssf.com contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.easyssf.quarkus.ssfreceiver.deployment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.easyssf.quarkus.ssfreceiver.runtime.delivery.poll.SsfPoller;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventHandler;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventContext;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Layer-2 test: drives {@link SsfPoller#pollNow()} against a WireMock
 * transmitter and verifies the documented contract:
 * <ul>
 * <li>Empty response → handler not invoked.</li>
 * <li>Single SET → handler invoked once, jti acked on next poll.</li>
 * <li>Multi-SET batch → handler invoked once per SET, all jti's acked on next poll.</li>
 * <li>Bad-signature SET → handler not invoked, jti NOT acked (transmitter will redeliver).</li>
 * <li>Handler throws → jti NOT acked.</li>
 * </ul>
 *
 * <p>
 * Drives polls synchronously via {@code pollNow()} ({@code poll.auto-start=false})
 * so test methods don't race against the periodic timer.
 *
 * <p>
 * Stubs are registered per @Test against the running WireMock's HTTP admin
 * API ({@code WireMock(host, port)}) because the WireMock server instance
 * lives in the test classloader and isn't reachable from the Quarkus
 * classloader where {@link Test @Test} methods run.
 */
public class PollerTest {

    private static final String ISSUER = "https://test.transmitter/realms/r1";
    private static final String AUDIENCE = "https://my-receiver.example/ssf";
    private static final String POLL_PATH = JwksWireMock.POLL_PATH;

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(CapturingHandler.class, JwksWireMock.class))
            .setBeforeAllCustomizer(
                    () -> JwksWireMock.start(ISSUER, AUDIENCE, JwksWireMock.DeliveryMode.POLL))
            .setAfterAllCustomizer(JwksWireMock::stop)
            .overrideConfigKey("ssf.receiver.transmitter-issuer", ISSUER)
            .overrideConfigKey("ssf.receiver.expected-audience", AUDIENCE)
            .overrideConfigKey("ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("ssf.receiver.stream-id", "stream-1")
            .overrideConfigKey("ssf.receiver.delivery-method", "POLL")
            // Drive polling manually so test methods don't race the timer.
            .overrideConfigKey("ssf.receiver.poll.auto-start", "false")
            .overrideConfigKey("ssf.receiver.poll.max-events", "10")
            .overrideConfigKey("ssf.receiver.poll.return-immediately", "true")
            // Dedup off so the same jti can recur across test methods (each
            // method registers its own SET via the canonical claims builder,
            // which uses a fresh UUID, but disable defensively for clarity).
            .overrideConfigKey("ssf.receiver.dedup.enabled", "false");

    @Inject
    SsfPoller poller;

    @Inject
    SsfEventHandler handler;

    private WireMock wm;
    private RSAKey signingKey;
    private final List<StubMapping> registeredStubs = new ArrayList<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetWireMockAndHandler() throws Exception {
        int port = Integer.parseInt(System.getProperty(JwksWireMock.PROP_WIREMOCK_PORT));
        wm = new WireMock("localhost", port);
        // Drop just the /poll stubs from the previous test method — leave
        // the infrastructure stubs (/jwks, /streams/configuration, etc.)
        // registered by JwksWireMock.start intact.
        for (StubMapping s : registeredStubs) {
            wm.removeStubMapping(s);
        }
        registeredStubs.clear();
        wm.resetRequests();

        signingKey = RSAKey.parse(System.getProperty(JwksWireMock.PROP_PRIVATE_JWK));
        ((CapturingHandler) handler).reset();
    }

    /** Mint a valid SET signed by the wiremock's advertised JWKS key. */
    private String mintSet() throws Exception {
        JWTClaimsSet claims = JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build();
        return JwksWireMock.signClaims(claims, signingKey, JwksWireMock.kid());
    }

    /** Mint a SET signed by an attacker key — the receiver will fail verification. */
    private String mintBadSignatureSet() throws Exception {
        RSAKey attackerKey = JwksWireMock.newAttackerKey(JwksWireMock.kid());
        JWTClaimsSet claims = JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build();
        return JwksWireMock.signClaims(claims, attackerKey, JwksWireMock.kid());
    }

    private static String jtiOf(String jwt) throws Exception {
        return com.nimbusds.jwt.SignedJWT.parse(jwt).getJWTClaimsSet().getJWTID();
    }

    /** Stub /poll to return the given response body (literal JSON). */
    private void stubPollResponse(String jsonBody) {
        StubMapping mapping = wm.register(post(urlEqualTo(POLL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonBody)));
        registeredStubs.add(mapping);
    }

    private static String pollResponseJson(Map<String, String> sets, boolean moreAvailable) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sets", sets);
        body.put("moreAvailable", moreAvailable);
        return MAPPER.writeValueAsString(body);
    }

    /** Returns the list of all /poll requests received by wiremock, in order. */
    private List<LoggedRequest> pollRequests() {
        return wm.find(postRequestedFor(urlEqualTo(POLL_PATH)));
    }

    @SuppressWarnings("unchecked")
    private List<String> acksInRequest(LoggedRequest req) throws Exception {
        Map<String, Object> body = MAPPER.readValue(req.getBody(), Map.class);
        Object acks = body.get("ack");
        if (acks == null) {
            return List.of();
        }
        return (List<String>) acks;
    }

    @Test
    @DisplayName("Empty poll response → handler not invoked, ack queue stays empty")
    void emptyResponse() throws Exception {
        stubPollResponse(pollResponseJson(Map.of(), false));

        poller.pollNow();

        CapturingHandler captured = (CapturingHandler) handler;
        assertThat(captured.captured, is(empty()));
        // One request was made, with no ack array (or an empty one).
        List<LoggedRequest> reqs = pollRequests();
        assertEquals(1, reqs.size());
        assertThat(acksInRequest(reqs.get(0)), is(empty()));
    }

    @Test
    @DisplayName("Single SET → handler invoked once, jti acked on the next poll")
    void singleSet() throws Exception {
        String setJwt = mintSet();
        String jti = jtiOf(setJwt);

        stubPollResponse(pollResponseJson(Map.of(jti, setJwt), false));

        poller.pollNow();

        CapturingHandler captured = (CapturingHandler) handler;
        assertEquals(1, captured.captured.size());
        SsfEventToken event = captured.captured.get(0);
        assertNotNull(event);
        assertEquals(jti, event.jti());
        assertEquals(ISSUER, event.iss());

        // Now poll again — empty response — and verify the jti is in the ack list.
        stubPollResponse(pollResponseJson(Map.of(), false));
        poller.pollNow();

        List<LoggedRequest> reqs = pollRequests();
        assertEquals(2, reqs.size());
        // First request had no acks; the second should carry the jti we just processed.
        assertThat(acksInRequest(reqs.get(0)), is(empty()));
        assertThat(acksInRequest(reqs.get(1)), contains(jti));
    }

    @Test
    @DisplayName("Multi-SET batch → handler invoked once per SET, all jti's acked")
    void multipleSetsInOneBatch() throws Exception {
        String setA = mintSet();
        String setB = mintSet();
        String setC = mintSet();
        List<String> expectedJtis = List.of(jtiOf(setA), jtiOf(setB), jtiOf(setC));

        Map<String, String> sets = new LinkedHashMap<>();
        sets.put(expectedJtis.get(0), setA);
        sets.put(expectedJtis.get(1), setB);
        sets.put(expectedJtis.get(2), setC);
        stubPollResponse(pollResponseJson(sets, false));

        poller.pollNow();

        CapturingHandler captured = (CapturingHandler) handler;
        assertEquals(3, captured.captured.size());
        List<String> handledJtis = new ArrayList<>();
        for (SsfEventToken e : captured.captured) {
            handledJtis.add(e.jti());
        }
        assertThat(handledJtis, containsInAnyOrder(expectedJtis.toArray()));

        // Next poll: all three jti's should be acked.
        stubPollResponse(pollResponseJson(Map.of(), false));
        poller.pollNow();
        List<LoggedRequest> reqs = pollRequests();
        assertEquals(2, reqs.size());
        assertThat(acksInRequest(reqs.get(1)), containsInAnyOrder(expectedJtis.toArray()));
    }

    @Test
    @DisplayName("Bad signature → handler not invoked, jti NOT acked")
    void badSignatureSet() throws Exception {
        String bad = mintBadSignatureSet();
        String jti = jtiOf(bad);

        stubPollResponse(pollResponseJson(Map.of(jti, bad), false));

        poller.pollNow();

        CapturingHandler captured = (CapturingHandler) handler;
        assertThat(captured.captured, is(empty()));

        // Next poll: jti must NOT be acked — the transmitter will redeliver
        // per RFC 8936 §2.3 and we want to give it another chance to be
        // verifiable (e.g. after a JWKS rotation).
        stubPollResponse(pollResponseJson(Map.of(), false));
        poller.pollNow();
        List<LoggedRequest> reqs = pollRequests();
        assertEquals(2, reqs.size());
        assertThat(acksInRequest(reqs.get(1)), not(contains(jti)));
        assertThat(acksInRequest(reqs.get(1)), is(empty()));
    }

    @Test
    @DisplayName("Handler throws → jti NOT acked (unhandled SET left for retry)")
    void handlerThrowsLeavesJtiUnacked() throws Exception {
        String setJwt = mintSet();
        String jti = jtiOf(setJwt);

        ((CapturingHandler) handler).throwOnNext.set(true);
        stubPollResponse(pollResponseJson(Map.of(jti, setJwt), false));

        poller.pollNow();

        CapturingHandler captured = (CapturingHandler) handler;
        // Handler was called (and threw); the SET is dropped, not acked.
        assertEquals(1, captured.invocations.get(), "handler should have been invoked once");

        stubPollResponse(pollResponseJson(Map.of(), false));
        poller.pollNow();
        List<LoggedRequest> reqs = pollRequests();
        assertEquals(2, reqs.size());
        assertThat(acksInRequest(reqs.get(1)), is(empty()));
    }

    @Test
    @DisplayName("Poll request fails → pending acks are requeued and sent on the next successful poll")
    void failedPollRequeuesAcks() throws Exception {
        // Cycle 1: one SET → handler processes → ack queued.
        String setJwt = mintSet();
        String jti = jtiOf(setJwt);
        stubPollResponse(pollResponseJson(Map.of(jti, setJwt), false));
        poller.pollNow();
        CapturingHandler captured = (CapturingHandler) handler;
        assertEquals(1, captured.captured.size(),
                "first poll should have delivered the SET to the handler");

        // Cycle 2: transmitter is down → poller catches, requeues the ack.
        // The request body still carries the drained ack — we just can't
        // get a 200 response back.
        StubMapping failure = wm.register(post(urlEqualTo(POLL_PATH))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"simulated transient outage\"}")));
        registeredStubs.add(failure);
        poller.pollNow();

        // Drop the failure stub and let the next call see a success again.
        wm.removeStubMapping(failure);
        registeredStubs.remove(failure);
        stubPollResponse(pollResponseJson(Map.of(), false));

        // Cycle 3: should re-send the previously-pending ack.
        poller.pollNow();

        List<LoggedRequest> reqs = pollRequests();
        assertEquals(3, reqs.size(), "three poll requests in total");
        // Cycle 1: no acks yet (first ever poll).
        assertThat(acksInRequest(reqs.get(0)), is(empty()));
        // Cycle 2: drained the queued ack, sent it, transmitter 500'd.
        assertThat(acksInRequest(reqs.get(1)), contains(jti));
        // Cycle 3: same ack is retried — at-least-once delivery survives the outage.
        assertThat(acksInRequest(reqs.get(2)), contains(jti));
    }

    @Test
    @DisplayName("Poll request shape matches RFC 8936 — maxEvents + returnImmediately fields present")
    void requestShape() throws Exception {
        stubPollResponse(pollResponseJson(Map.of(), false));

        poller.pollNow();

        List<LoggedRequest> reqs = pollRequests();
        assertEquals(1, reqs.size());
        // The poll request body uses the camelCase field names from the
        // SsfPollRequest record, which is what the spec calls for.
        Map<?, ?> body = MAPPER.readValue(reqs.get(0).getBody(), Map.class);
        assertThat(body.get("maxEvents"), equalTo(10));
        assertThat(body.get("returnImmediately"), equalTo(true));
    }

    /**
     * Test-side captor: records every dispatched {@link SsfEventToken} and
     * optionally throws on the next invocation. {@code volatile} latch /
     * counters so subsequent test methods see fresh state after {@link #reset}.
     */
    @Singleton
    public static class CapturingHandler implements SsfEventHandler {
        final List<SsfEventToken> captured = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger invocations = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean throwOnNext = new java.util.concurrent.atomic.AtomicBoolean(false);

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
