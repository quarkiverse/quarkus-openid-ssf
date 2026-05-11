package io.quarkiverse.ssf.receiver.runtime.metrics;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.quarkiverse.ssf.receiver.runtime.dedup.SsfJtiDedupStore;
import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPollAckStore;
import io.quarkiverse.ssf.receiver.runtime.event.SsfAliases;
import io.quarkus.runtime.Startup;

/**
 * Micrometer-backed {@link SsfReceiverMetrics}. Registered by
 * {@code SsfReceiverProcessor} only when the {@code io.quarkus.micrometer}
 * capability is on the consumer's classpath; otherwise the no-op default
 * stays in effect and this class is never loaded.
 *
 * <p>
 * Counters/timers are pre-registered in {@link #init()} so they show up in
 * {@code /q/metrics} from the first scrape rather than only after the first
 * event. The {@link Startup} annotation forces eager instantiation at boot —
 * without it, the bean would only initialise when {@code SsfPushRoute} or
 * {@code SsfPoller} actually called a meter method, hiding {@code ssf.receiver.*}
 * from the scrape until the first SET arrived.
 *
 * <p>
 * Tagged variants (push reject reasons, poll outcomes, …) are pre-bound in
 * {@code EnumMap}s so the hot path doesn't re-create {@code Tags} on every call.
 */
@Startup
@ApplicationScoped
public class MicrometerSsfReceiverMetrics implements SsfReceiverMetrics {

    private static final String NS = "ssf.receiver";

    @Inject
    MeterRegistry registry;

    @Inject
    SsfPollAckStore ackStore;

    @Inject
    SsfAliases aliases;

    @Inject
    SsfJtiDedupStore dedupStore;

    private Counter pushAccepted;
    private Counter pushHandlerErrors;
    private Map<PushRejectReason, Counter> pushRejected;

    private Counter pollEventsReceived;
    private Counter pollEventHandled;
    private Counter pollAcksSent;
    private Map<PollOutcome, Timer> pollCycles;
    private Map<PollEventFailureReason, Counter> pollEventFailed;

    private Map<DeliverySource, Counter> dedupSkipped;

    @PostConstruct
    void init() {
        pushAccepted = Counter.builder(NS + ".push.accepted")
                .description("Inbound SET pushes that verified and reached the handler.")
                .register(registry);

        pushHandlerErrors = Counter.builder(NS + ".push.handler.errors")
                .description("Pushes whose handler threw after the SET verified.")
                .register(registry);

        pushRejected = new EnumMap<>(PushRejectReason.class);
        for (PushRejectReason r : PushRejectReason.values()) {
            pushRejected.put(r, Counter.builder(NS + ".push.rejected")
                    .description("Inbound pushes rejected before reaching the handler.")
                    .tags(Tags.of(Tag.of("reason", r.name().toLowerCase(Locale.ROOT))))
                    .register(registry));
        }

        pollEventsReceived = Counter.builder(NS + ".poll.events.received")
                .description("SETs returned by the transmitter from the poll endpoint.")
                .register(registry);

        pollEventHandled = Counter.builder(NS + ".poll.events.handled")
                .description("Polled SETs that verified and the handler accepted.")
                .register(registry);

        pollAcksSent = Counter.builder(NS + ".poll.acks.sent")
                .description("jti's included in the ack array on outbound poll requests.")
                .register(registry);

        pollCycles = new EnumMap<>(PollOutcome.class);
        for (PollOutcome o : PollOutcome.values()) {
            pollCycles.put(o, Timer.builder(NS + ".poll.cycles")
                    .description("Wall-clock duration of a poll cycle, by outcome.")
                    .tags(Tags.of(Tag.of("outcome", o.name().toLowerCase(Locale.ROOT))))
                    .register(registry));
        }

        pollEventFailed = new EnumMap<>(PollEventFailureReason.class);
        for (PollEventFailureReason r : PollEventFailureReason.values()) {
            pollEventFailed.put(r, Counter.builder(NS + ".poll.events.failed")
                    .description("Polled SETs that failed somewhere in verify-then-dispatch.")
                    .tags(Tags.of(Tag.of("reason", r.name().toLowerCase(Locale.ROOT))))
                    .register(registry));
        }

        // Gauge backed by SsfPollAckStore.size(). Returns 0 when the store
        // reports "unknown" (-1) so dashboards don't show a sudden negative spike.
        Gauge.builder(NS + ".poll.ack.queue.depth", ackStore, store -> {
            int s = store.size();
            return s < 0 ? 0 : s;
        })
                .description("Pending acks waiting to be sent on the next poll request.")
                .register(registry);

        dedupSkipped = new EnumMap<>(DeliverySource.class);
        for (DeliverySource s : DeliverySource.values()) {
            dedupSkipped.put(s, Counter.builder(NS + ".dedup.skipped")
                    .description("SETs dropped because their jti was already processed.")
                    .tags(Tags.of(Tag.of("delivery", s.name().toLowerCase(Locale.ROOT))))
                    .register(registry));
        }

        Gauge.builder(NS + ".dedup.store.size", dedupStore, store -> {
            int s = store.size();
            return s < 0 ? 0 : s;
        })
                .description("Approximate number of jti's currently tracked by the dedup store.")
                .register(registry);
    }

    @Override
    public void pushAccepted() {
        pushAccepted.increment();
    }

    @Override
    public void pushRejected(PushRejectReason reason) {
        pushRejected.get(reason).increment();
    }

    @Override
    public void pushHandlerError() {
        pushHandlerErrors.increment();
    }

    @Override
    public void pollCycle(PollOutcome outcome, long durationNanos) {
        pollCycles.get(outcome).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void pollEventsReceived(int count) {
        if (count > 0) {
            pollEventsReceived.increment(count);
        }
    }

    @Override
    public void pollEventHandled() {
        pollEventHandled.increment();
    }

    @Override
    public void pollEventFailed(PollEventFailureReason reason) {
        pollEventFailed.get(reason).increment();
    }

    @Override
    public void pollAcksSent(int count) {
        if (count > 0) {
            pollAcksSent.increment(count);
        }
    }

    @Override
    public void dedupSkipped(DeliverySource source) {
        dedupSkipped.get(source).increment();
    }

    @Override
    public void eventProcessed(String eventTypeUri, String issuer, DeliverySource source) {
        // registry.counter(name, tags) caches per (name, tags) so this is a fast lookup
        // on the hot path — no need to pre-register every (event, iss, delivery) tuple.
        registry.counter("ssf.receiver.events.processed",
                "event", aliases.eventTypeAlias(eventTypeUri),
                "iss", aliases.issuerAlias(issuer),
                "receiver", aliases.receiverAlias(),
                "delivery", source.name().toLowerCase(Locale.ROOT))
                .increment();
    }
}
