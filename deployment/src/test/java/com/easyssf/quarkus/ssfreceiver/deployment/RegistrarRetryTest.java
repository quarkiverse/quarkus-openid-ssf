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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.easyssf.quarkus.ssfreceiver.runtime.stream.ReceiverManagedStreamState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Layer-2 test: the receiver-managed registrar's exponential-backoff retry
 * loop. A flaky transmitter that 500s on the first POST should not stop the
 * receiver from coming up — the registrar must retry on its virtual thread
 * and eventually publish the assigned stream_id to
 * {@link ReceiverManagedStreamState}.
 *
 * <p>
 * Drives the failure transition via a WireMock scenario: the first POST is
 * stubbed to return 500 and advances the scenario; the second POST runs from
 * a different scenario state and returns 201 with the assigned stream_id.
 *
 * <p>
 * Registrar's hard-coded initial backoff is 1s — the test budgets 8s for
 * the second attempt to land (worst case is 1s sleep + a slow CI runner).
 */
public class RegistrarRetryTest {

    private static final String ISSUER = "https://test.transmitter/realms/r1";
    private static final String AUDIENCE = "https://my-receiver.example/ssf";
    private static final String DELIVERY_URL = "https://my-receiver.example/ssf/push";
    private static final String EVENT_TYPE = JwksWireMock.EVENT_TYPE;
    private static final String CREATED_STREAM_ID = "created-after-retry-001";
    private static final String SCENARIO = "registrar-create-retry";
    private static final String STATE_AFTER_FAILURE = "create-failed-once";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(JwksWireMock.class))
            .setBeforeAllCustomizer(() -> {
                JwksWireMock.start(ISSUER, AUDIENCE);

                // GET list → empty (so the registrar always falls through to create).
                JwksWireMock.server().stubFor(get(urlPathEqualTo("/streams/configuration"))
                        .atPriority(5)
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("[]")));

                // First POST → 500, advance scenario.
                JwksWireMock.server().stubFor(post(urlEqualTo("/streams/configuration"))
                        .inScenario(SCENARIO)
                        .whenScenarioStateIs(STARTED)
                        .willSetStateTo(STATE_AFTER_FAILURE)
                        .willReturn(aResponse()
                                .withStatus(500)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"error\":\"simulated transient outage\"}")));

                // Second POST → 201 with the assigned stream_id.
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("stream_id", CREATED_STREAM_ID);
                created.put("iss", ISSUER);
                created.put("aud", List.of(AUDIENCE));
                created.put("events_supported", List.of(EVENT_TYPE));
                created.put("events_requested", List.of(EVENT_TYPE));
                created.put("events_delivered", List.of(EVENT_TYPE));
                Map<String, Object> delivery = new LinkedHashMap<>();
                delivery.put("method", "urn:ietf:rfc:8935");
                delivery.put("endpoint_url", DELIVERY_URL);
                created.put("delivery", delivery);
                String createdJson;
                try {
                    createdJson = new ObjectMapper().writeValueAsString(created);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                JwksWireMock.server().stubFor(post(urlEqualTo("/streams/configuration"))
                        .inScenario(SCENARIO)
                        .whenScenarioStateIs(STATE_AFTER_FAILURE)
                        .willReturn(aResponse()
                                .withStatus(201)
                                .withHeader("Content-Type", "application/json")
                                .withBody(createdJson)));

                // Status for the eventually-created stream (used by the registrar's log line).
                Map<String, Object> statusBody = new LinkedHashMap<>();
                statusBody.put("stream_id", CREATED_STREAM_ID);
                statusBody.put("status", "enabled");
                String statusJson;
                try {
                    statusJson = new ObjectMapper().writeValueAsString(statusBody);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                JwksWireMock.server().stubFor(
                        get(urlEqualTo("/streams/status?stream_id=" + CREATED_STREAM_ID))
                                .willReturn(aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(statusJson)));
            })
            .setAfterAllCustomizer(JwksWireMock::stop)
            .overrideConfigKey("ssf.receiver.transmitter-issuer", ISSUER)
            .overrideConfigKey("ssf.receiver.expected-audience", AUDIENCE)
            .overrideConfigKey("ssf.receiver.stream-management", "RECEIVER")
            .overrideConfigKey("ssf.receiver.delivery-method", "PUSH")
            .overrideConfigKey("ssf.receiver.push.delivery-endpoint-url", DELIVERY_URL)
            .overrideConfigKey("ssf.receiver.events-requested", EVENT_TYPE)
            .overrideConfigKey("ssf.receiver.receiver-managed.delete-on-shutdown", "false");

    @Inject
    ReceiverManagedStreamState state;

    @Test
    @DisplayName("First create 500s → registrar retries → eventually publishes the stream_id")
    void registrarRetriesAfterTransientFailure() throws Exception {
        Optional<String> sid = waitForStreamId(state, 8, TimeUnit.SECONDS);
        assertTrue(sid.isPresent(),
                "registrar should publish a stream_id after retrying the create");
        assertEquals(CREATED_STREAM_ID, sid.get());

        // Exactly two POSTs were made: the failing one and the successful one.
        // No third call (the registrar must STOP retrying once it succeeds).
        int port = Integer.parseInt(System.getProperty(JwksWireMock.PROP_WIREMOCK_PORT));
        WireMock wm = new WireMock("localhost", port);
        int posts = wm.find(postRequestedFor(urlEqualTo("/streams/configuration"))).size();
        assertEquals(2, posts, "expected exactly one failing + one successful POST");
    }

    private static Optional<String> waitForStreamId(ReceiverManagedStreamState state,
            long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            Optional<String> sid = state.streamId();
            if (sid.isPresent()) {
                return sid;
            }
            Thread.sleep(50L);
        }
        return state.streamId();
    }
}
