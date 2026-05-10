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
package com.easyssf.quarkus.ssfreceiver.runtime.stream;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.easyssf.quarkus.ssfreceiver.runtime.SsfReceiverConfig;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfAliases;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * Discover-or-create startup hook for receiver-managed streams.
 *
 * <p>
 * When {@code ssf.receiver.stream-management=RECEIVER} and
 * {@code ssf.receiver.receiver-managed.register-stream=true} (the default), this
 * bean lists the receiver's existing streams on the transmitter, looks for one
 * whose {@code delivery.endpoint_url} (or audience) matches this receiver, and
 * either reuses its {@code stream_id} or creates a new stream.
 *
 * <p>
 * Runs at a higher observer priority than the validator so configuration is
 * verified before we try to talk to the transmitter.
 */
@ApplicationScoped
public class ReceiverManagedStreamRegistrar {

    private static final Logger LOG = Logger.getLogger(ReceiverManagedStreamRegistrar.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    SsfStreamClient streamClient;

    @Inject
    ReceiverManagedStreamState state;

    @Inject
    SsfAliases aliases;

    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Thread retryThread;

    void onStart(@Observes @Priority(200) StartupEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (config.streamManagement() != SsfReceiverConfig.StreamManagement.RECEIVER) {
            return;
        }
        if (!config.receiverManaged().registerStream()) {
            LOG.infof("ssf.receiver.receiver-managed.register-stream=false — skipping startup stream registration");
            return;
        }
        // Honor an explicit stream-id if the operator pinned one. Cheap path —
        // no retry needed, populate state immediately and probe in best-effort.
        if (config.streamId().isPresent()) {
            String pinned = config.streamId().get();
            state.setStreamId(pinned);
            probePinned(pinned);
            return;
        }

        boolean push = config.deliveryMethod() == SsfReceiverConfig.DeliveryMethod.PUSH;
        URI deliveryUrl = push
                ? config.push().deliveryEndpointUrl().orElseThrow(() -> new IllegalStateException(
                        "ssf.receiver.push.delivery-endpoint-url is required when stream-management=RECEIVER and delivery-method=PUSH"))
                : null;

        // Discover-or-create runs on a background virtual thread so a slow /
        // unreachable transmitter never blocks the JVM from coming up. The
        // push route is registered independently (Vert.x router observer)
        // so the receiver can already accept inbound SETs while we keep
        // retrying registration in the background.
        retryThread = Thread.ofVirtual()
                .name("ssf-receiver-managed-registrar")
                .start(() -> registerWithBackoff(deliveryUrl));
    }

    /**
     * Background retry loop: discover-or-create with exponential backoff
     * (1s → 2s → 4s → 8s → 16s → 30s, capped). Runs until the registration
     * succeeds, the stream-id is otherwise populated (e.g. operator pinned at
     * runtime), or the application shuts down.
     */
    private void registerWithBackoff(URI deliveryUrl) {
        long delayMs = INITIAL_BACKOFF_MS;
        int attempt = 0;
        while (!stopped.get() && state.streamId().isEmpty()) {
            attempt++;
            try {
                registerOnce(deliveryUrl);
                return;
            } catch (RuntimeException e) {
                long secs = Math.max(1L, delayMs / 1_000L);
                LOG.warnf("Receiver-managed registration attempt %d failed (%s) — retrying in %ds",
                        attempt, summarize(e), secs);
                LOG.debugf(e, "Receiver-managed registration attempt %d — full stack", attempt);
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.debugf("Receiver-managed registration loop interrupted on attempt %d", attempt);
                return;
            }
            delayMs = Math.min(delayMs * 2, MAX_BACKOFF_MS);
        }
    }

    /** Single-shot discover-or-create. May throw if the transmitter rejects the call. */
    private void registerOnce(URI deliveryUrl) {
        StreamConfiguration existing = findExistingStream(deliveryUrl);
        if (existing != null) {
            LOG.infof(
                    "Receiver-managed mode: reusing existing stream stream_id=%s (status=%s, delivery-method=%s, %s=%s, events_delivered=%s)",
                    existing.streamId(),
                    safeStatusLabel(existing.streamId()),
                    StreamLogFormat.describeDeliveryMethod(existing),
                    StreamLogFormat.endpointFieldName(existing),
                    deliveryUrl != null ? deliveryUrl : StreamLogFormat.endpointOrNone(existing),
                    StreamLogFormat.eventsDelivered(existing, aliases));
            state.setStreamId(existing.streamId());
            return;
        }

        LOG.infof("Receiver-managed mode: no existing stream matched (delivery-method=%s) — creating a new one",
                config.deliveryMethod());
        StreamConfiguration created = streamClient.createStream(buildCreateRequest(deliveryUrl));
        LOG.infof(
                "Receiver-managed mode: created stream stream_id=%s (status=%s, delivery-method=%s, %s=%s, events_delivered=%s)",
                created.streamId(),
                safeStatusLabel(created.streamId()),
                StreamLogFormat.describeDeliveryMethod(created),
                StreamLogFormat.endpointFieldName(created),
                StreamLogFormat.endpointOrNone(created),
                StreamLogFormat.eventsDelivered(created, aliases));
        state.setStreamId(created.streamId());
    }

    private void probePinned(String pinned) {
        StreamConfiguration probedCfg;
        try {
            probedCfg = streamClient.configurationOf(pinned);
        } catch (SsfStreamException e) {
            LOG.warnf("Receiver-managed mode using pinned stream_id=%s; could not read its configuration: %s",
                    pinned, summarize(e));
            LOG.debugf(e, "Pinned-stream config probe — full stack");
            return;
        }
        LOG.infof(
                "Receiver-managed mode using pinned stream stream_id=%s (status=%s, delivery-method=%s, %s=%s, events_delivered=%s)",
                pinned,
                safeStatusLabel(pinned),
                StreamLogFormat.describeDeliveryMethod(probedCfg),
                StreamLogFormat.endpointFieldName(probedCfg),
                StreamLogFormat.endpointOrNone(probedCfg),
                StreamLogFormat.eventsDelivered(probedCfg, aliases));
    }

    /**
     * Trims an exception's message to its first line, capped at 200 chars.
     * Matches the same helper in {@code SsfPoller}; keeps log lines one-row
     * even when the transmitter returns an HTML error body.
     */
    private static String summarize(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        int newline = msg.indexOf('\n');
        if (newline >= 0) {
            msg = msg.substring(0, newline);
        }
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "…";
        }
        return t.getClass().getSimpleName() + ": " + msg.trim();
    }

    /**
     * Best-effort status fetch for log purposes. Returns {@code unknown} (rather
     * than throwing) so a flaky status endpoint never breaks the registrar's
     * info log line — actual stream-management failures earlier in the flow
     * already get their own warnings.
     */
    private String safeStatusLabel(String streamId) {
        try {
            return StreamLogFormat.statusLabel(streamClient.statusOf(streamId));
        } catch (SsfStreamException e) {
            LOG.debugf(e, "Stream status read failed for stream_id=%s", streamId);
            return "unknown";
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        // Stop the retry loop unconditionally — even if the receiver is disabled
        // or in TRANSMITTER mode, the thread might still be alive from an earlier
        // dev-mode reload. Cheap no-op if it never started.
        stopped.set(true);
        Thread t = retryThread;
        if (t != null) {
            t.interrupt();
        }
        if (!config.enabled()) {
            return;
        }
        if (config.streamManagement() != SsfReceiverConfig.StreamManagement.RECEIVER) {
            return;
        }
        if (!config.receiverManaged().deleteOnShutdown()) {
            return;
        }
        state.streamId().ifPresent(streamId -> {
            try {
                LOG.infof("Receiver-managed mode: deleting stream_id=%s on shutdown", streamId);
                streamClient.deleteStream(streamId);
            } catch (RuntimeException e) {
                LOG.warnf("Failed to delete stream_id=%s on shutdown: %s", streamId, e.getMessage());
            } finally {
                state.clear();
            }
        });
    }

    private StreamConfiguration findExistingStream(URI deliveryUrl) {
        List<StreamConfiguration> existing;
        try {
            existing = streamClient.listStreams();
        } catch (SsfStreamException e) {
            // Some transmitters reject GET-without-stream_id with 4xx; treat as "nothing to discover".
            LOG.debugf("Stream discovery failed (%s) — proceeding to create a new stream", e.getMessage());
            return null;
        }
        if (existing.isEmpty()) {
            return null;
        }
        String expectedAud = config.expectedAudience().orElse(null);
        String expectedMethod = deliveryMethodUri();
        StreamConfiguration match = null;
        int matches = 0;
        for (StreamConfiguration s : existing) {
            // PUSH match is on the exact delivery URL we asked for; POLL has no
            // receiver-supplied URL (the transmitter assigns it), so we fall back
            // to matching delivery method + audience.
            boolean matched = (deliveryUrl != null && matchesDelivery(s, deliveryUrl))
                    || (deliveryUrl == null && matchesMethodAndAudience(s, expectedMethod, expectedAud))
                    || matchesAudience(s, expectedAud);
            if (matched) {
                if (match == null) {
                    match = s;
                }
                matches++;
            }
        }
        if (matches > 1) {
            LOG.warnf("Receiver-managed mode: %d existing streams matched delivery=%s / aud=%s — using stream_id=%s",
                    matches, deliveryUrl != null ? deliveryUrl : expectedMethod, expectedAud, match.streamId());
        }
        return match;
    }

    private static boolean matchesDelivery(StreamConfiguration s, URI deliveryUrl) {
        return s.delivery() != null
                && deliveryUrl.equals(s.delivery().endpointUrl());
    }

    private static boolean matchesMethodAndAudience(StreamConfiguration s, String method, String aud) {
        if (s.delivery() == null || !method.equals(s.delivery().method())) {
            return false;
        }
        return matchesAudience(s, aud);
    }

    private static boolean matchesAudience(StreamConfiguration s, String expectedAudience) {
        if (expectedAudience == null || expectedAudience.isBlank()) {
            return false;
        }
        return s.aud() != null && s.aud().contains(expectedAudience);
    }

    private StreamConfiguration buildCreateRequest(URI deliveryUrl) {
        List<URI> eventsRequested = config.eventsRequested().orElse(List.of());
        if (eventsRequested.isEmpty()) {
            throw new IllegalStateException(
                    "ssf.receiver.events-requested must list at least one event URI when stream-management=RECEIVER");
        }
        // PUSH: receiver dictates delivery.endpoint_url. POLL: transmitter assigns it,
        // so we send the method only.
        StreamConfiguration.Delivery delivery = new StreamConfiguration.Delivery(
                deliveryMethodUri(),
                deliveryUrl,
                java.util.Map.of());
        // Per SSF §8.1.1, `iss`, `aud`, `events_supported` and `events_delivered`
        // are transmitter-supplied — the receiver MUST NOT set them on create.
        // (Keycloak's SSF transmitter rejects requests that try.) We pass them
        // as null/empty here so the JSON omits them via @JsonInclude(NON_NULL).
        return new StreamConfiguration(
                /* streamId */ null,
                /* iss */ null,
                /* aud */ List.of(),
                /* eventsSupported */ List.of(),
                eventsRequested,
                /* eventsDelivered */ List.of(),
                delivery,
                /* minVerificationInterval */ null,
                /* inactivityTimeout */ null,
                config.receiverManaged().description().orElse(null));
    }

    private String deliveryMethodUri() {
        return config.deliveryMethod() == SsfReceiverConfig.DeliveryMethod.POLL
                ? StreamLogFormat.POLL_DELIVERY_METHOD
                : StreamLogFormat.PUSH_DELIVERY_METHOD;
    }
}
