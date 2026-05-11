package io.quarkiverse.ssf.receiver.runtime.metrics;

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
