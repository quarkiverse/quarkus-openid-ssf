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
package com.easyssf.quarkus.ssfreceiver.runtime.metrics;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;

/**
 * Default {@link SsfReceiverMetrics} — does nothing. Stays in effect when the
 * consumer doesn't have {@code quarkus-micrometer} (or one of its registry
 * extensions) on the classpath.
 */
@ApplicationScoped
@DefaultBean
public class NoopSsfReceiverMetrics implements SsfReceiverMetrics {

    @Override
    public void pushAccepted() {
    }

    @Override
    public void pushRejected(PushRejectReason reason) {
    }

    @Override
    public void pushHandlerError() {
    }

    @Override
    public void pollCycle(PollOutcome outcome, long durationNanos) {
    }

    @Override
    public void pollEventsReceived(int count) {
    }

    @Override
    public void pollEventHandled() {
    }

    @Override
    public void pollEventFailed(PollEventFailureReason reason) {
    }

    @Override
    public void pollAcksSent(int count) {
    }

    @Override
    public void eventProcessed(String eventTypeUri, String issuer, DeliverySource source) {
    }

    @Override
    public void dedupSkipped(DeliverySource source) {
    }
}
