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
package com.easyssf.quarkus.ssfreceiver.runtime.event;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.easyssf.quarkus.ssfreceiver.runtime.SsfReceiverConfig;

import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class SsfReceiverStartupValidator {

    private static final Logger LOG = Logger.getLogger(SsfReceiverStartupValidator.class);

    @Inject
    SsfReceiverConfig config;

    void onStart(@Observes @Priority(100) StartupEvent event) {
        if (!config.enabled()) {
            // Single boot log line; the other observers stay silent when disabled
            // so we don't spam five "skipping" lines for the same kill switch.
            LOG.infof("ssf.receiver.enabled=false — SSF receiver is disabled, no automatic activity will run");
            return;
        }
        switch (config.streamManagement()) {
            case TRANSMITTER -> {
                if (config.streamId().isEmpty()) {
                    throw new IllegalStateException(
                            "ssf.receiver.stream-id is required when stream-management=TRANSMITTER");
                }
            }
            case RECEIVER -> {
                if (config.deliveryMethod() == SsfReceiverConfig.DeliveryMethod.PUSH
                        && config.push().deliveryEndpointUrl().isEmpty()) {
                    throw new IllegalStateException(
                            "ssf.receiver.push.delivery-endpoint-url is required when "
                                    + "stream-management=RECEIVER and delivery-method=PUSH");
                }
                if (config.receiverManaged().registerStream()
                        && config.streamId().isEmpty()
                        && config.eventsRequested().map(java.util.List::isEmpty).orElse(true)) {
                    throw new IllegalStateException(
                            "ssf.receiver.events-requested must list at least one event URI when "
                                    + "stream-management=RECEIVER and the extension is asked to register a stream");
                }
            }
        }
    }
}
