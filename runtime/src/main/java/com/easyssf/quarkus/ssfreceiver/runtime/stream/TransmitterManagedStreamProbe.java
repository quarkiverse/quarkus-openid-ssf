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

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.easyssf.quarkus.ssfreceiver.runtime.SsfReceiverConfig;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfAliases;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.status.StreamStatus;

import io.quarkus.runtime.StartupEvent;

/**
 * Startup probe for transmitter-managed streams. Mirrors
 * {@link ReceiverManagedStreamRegistrar} on the TRANSMITTER side: when
 * {@code ssf.receiver.stream-management=TRANSMITTER} (and the probe isn't
 * disabled), this bean reads the configured stream's configuration and status
 * from the transmitter once at startup and logs a one-line summary so the
 * operator can confirm the stream is in place without the receiver having to
 * receive an actual SET first.
 *
 * <p>
 * Failures are logged as warnings rather than rethrown — the receiver
 * should still come up so it can accept inbound pushes when the transmitter is
 * available. Disable via {@code ssf.receiver.transmitter-managed.probe-on-startup=false}
 * if the receiver has no outbound credentials (e.g. PUSH-only with public JWKS).
 *
 * <p>
 * Runs at observer priority 200, after the validator (100) — same slot as
 * the receiver-managed registrar; only one of the two fires per app since
 * stream-management is mutually exclusive.
 */
@ApplicationScoped
public class TransmitterManagedStreamProbe {

    private static final Logger LOG = Logger.getLogger(TransmitterManagedStreamProbe.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    SsfStreamClient streamClient;

    @Inject
    SsfAliases aliases;

    void onStart(@Observes @Priority(200) StartupEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (config.streamManagement() != SsfReceiverConfig.StreamManagement.TRANSMITTER) {
            return;
        }
        if (!config.transmitterManaged().probeOnStartup()) {
            LOG.debugf("ssf.receiver.transmitter-managed.probe-on-startup=false — skipping startup stream probe");
            return;
        }
        // Validator already enforces this; defensive recheck for the log message.
        String streamId = config.streamId().orElse(null);
        if (streamId == null) {
            return;
        }

        StreamConfiguration cfg;
        try {
            cfg = streamClient.configurationOf(streamId);
        } catch (SsfStreamException e) {
            LOG.warnf("Transmitter-managed mode: failed to read stream configuration for stream_id=%s: %s",
                    streamId, e.getMessage());
            LOG.debugf(e, "Stream configuration probe — full stack");
            return;
        }

        StreamStatus status = null;
        try {
            status = streamClient.statusOf(streamId);
        } catch (SsfStreamException e) {
            LOG.warnf("Transmitter-managed mode: stream config OK but status read failed for stream_id=%s: %s",
                    streamId, e.getMessage());
            LOG.debugf(e, "Stream status probe — full stack");
        }

        LOG.infof(
                "Transmitter-managed mode: using stream stream_id=%s (status=%s, delivery-method=%s, %s=%s, events_delivered=%s)",
                streamId,
                StreamLogFormat.statusLabel(status),
                StreamLogFormat.describeDeliveryMethod(cfg),
                StreamLogFormat.endpointFieldName(cfg),
                StreamLogFormat.endpointOrNone(cfg),
                StreamLogFormat.eventsDelivered(cfg, aliases));
    }
}
