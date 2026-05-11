package io.quarkiverse.ssf.receiver.deployment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkiverse.ssf.receiver.runtime.stream.ReceiverManagedStreamState;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Layer-2 test for the receiver-managed startup hook.
 *
 * <p>
 * This boots Quarkus in {@code RECEIVER} mode with no pinned {@code stream-id};
 * the registrar runs on a background virtual thread, calls
 * {@code GET /streams/configuration} (list) on the WireMock transmitter, finds
 * no match, then POSTs a create. The new stream-id should land in
 * {@link ReceiverManagedStreamState}.
 *
 * <p>
 * Covers the create-on-first-boot happy path. Reuse and retry-on-failure
 * would require independent boot cycles or fault injection in WireMock —
 * tracked separately if/when those scenarios prove necessary.
 */
public class ReceiverManagedRegistrarTest {

    private static final String ISSUER = "https://test.transmitter/realms/r1";
    private static final String AUDIENCE = "https://my-receiver.example/ssf";
    private static final String DELIVERY_URL = "https://my-receiver.example/ssf/push";
    private static final String EVENT_TYPE = JwksWireMock.EVENT_TYPE;
    private static final String CREATED_STREAM_ID = "created-by-registrar-001";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(JwksWireMock.class))
            .setBeforeAllCustomizer(() -> {
                JwksWireMock.start(ISSUER, AUDIENCE);
                // GET /streams/configuration (no stream_id) → list → empty.
                // Use urlPathEqualTo so the stub matches regardless of trailing
                // query string (WireMock's default urlEqualTo would otherwise
                // collide with the per-stream-id read stub registered by
                // JwksWireMock.start).
                JwksWireMock.server().stubFor(get(urlPathEqualTo("/streams/configuration"))
                        .atPriority(5)
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("[]")));

                // POST /streams/configuration → echo back with assigned stream_id.
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
                created.put("min_verification_interval", JwksWireMock.MIN_VERIFICATION_INTERVAL);
                created.put("inactivity_timeout", JwksWireMock.INACTIVITY_TIMEOUT);
                created.put("description", JwksWireMock.STREAM_DESCRIPTION);
                String createdJson;
                try {
                    createdJson = new ObjectMapper().writeValueAsString(created);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                JwksWireMock.server().stubFor(post(urlEqualTo("/streams/configuration"))
                        .willReturn(aResponse()
                                .withStatus(201)
                                .withHeader("Content-Type", "application/json")
                                .withBody(createdJson)));

                // The status endpoint stub registered by JwksWireMock uses
                // stream_id=stream-1; the registrar will probe the created stream
                // for its log line. Stub a generic "enabled" status for it too.
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
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer", ISSUER)
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "RECEIVER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "PUSH")
            .overrideConfigKey("quarkus.openid-ssf.receiver.push.delivery-endpoint-url", DELIVERY_URL)
            .overrideConfigKey("quarkus.openid-ssf.receiver.events-requested", EVENT_TYPE)
            // Don't actually delete on shutdown — the test framework doesn't
            // need the cleanup, and stubbing DELETE adds noise.
            .overrideConfigKey("quarkus.openid-ssf.receiver.receiver-managed.delete-on-shutdown", "false");

    @Inject
    ReceiverManagedStreamState state;

    @Test
    @DisplayName("Discover-or-create: empty list → POST create → state holds the new stream_id")
    void createsNewStreamWhenNoneExist() throws Exception {
        Optional<String> sid = waitForStreamId(state, 10, TimeUnit.SECONDS);
        assertTrue(sid.isPresent(), "registrar must publish a stream_id within 10s");
        assertEquals(CREATED_STREAM_ID, sid.get());

        // Exactly one POST to /streams/configuration (the create call).
        // JwksWireMock.server() is null in the Quarkus classloader, so go via
        // the wiremock HTTP admin API instead.
        int port = Integer.parseInt(System.getProperty(JwksWireMock.PROP_WIREMOCK_PORT));
        WireMock wm = new WireMock("localhost", port);
        int posts = wm.find(postRequestedFor(urlEqualTo("/streams/configuration"))).size();
        assertEquals(1, posts, "expected exactly one POST to /streams/configuration");
    }

    @Test
    @DisplayName("Created stream is reachable via the state and matches the transmitter's echo")
    void streamIdMatchesTransmitterAssignedId() throws Exception {
        Optional<String> sid = waitForStreamId(state, 10, TimeUnit.SECONDS);
        assertTrue(sid.isPresent());
        assertThat(sid.get(), equalTo(CREATED_STREAM_ID));
    }

    /**
     * Polls the state until {@code streamId()} is populated or the timeout
     * expires. The registrar runs on a virtual thread so the state may not be
     * set at startup-event return.
     */
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
