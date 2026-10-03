package io.quarkiverse.ssf.receiver.runtime.metrics;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.receiver.metrics.MicrometerSsfReceiverMetrics;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.SsfJtiDedupStore;
import org.easyssf.receiver.transmitter.SsfTransmitters;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;

/**
 * Records what the receiver does with Micrometer, when {@code quarkus-micrometer} is
 * present (registered by the deployment processor):
 * <ul>
 * <li>{@code easyssf.receiver.sets}: received SETs, tagged with {@code transmitter}
 * (the name of the transmitter), {@code delivery} and {@code outcome}</li>
 * <li>{@code easyssf.receiver.events}: handled events, tagged with {@code transmitter},
 * {@code delivery} and {@code event} (the alias of the event type)</li>
 * <li>{@code easyssf.receiver.poll}: poll requests, tagged with {@code transmitter} and
 * {@code outcome}</li>
 * <li>{@code easyssf.receiver.dedup.size}: the number of SET identifiers the in-memory
 * de-duplication store remembers</li>
 * </ul>
 */
@Singleton
public class MicrometerSsfReceiverMetricsProducer {

    @Inject
    SsfReceiverConfig config;

    @Produces
    @Singleton
    public SsfReceiverMetrics metrics(MeterRegistry meterRegistry, Instance<SsfTransmitters> transmitters,
            Instance<SsfJtiDedupStore> dedupStore) {
        MicrometerSsfReceiverMetrics metrics = new MicrometerSsfReceiverMetrics(meterRegistry);
        metrics.setTransmitterLabels(issuer -> transmitters.isResolvable() ? transmitters.get().nameOf(issuer) : issuer);
        if (config.dedup().enabled() && dedupStore.isResolvable()
                && dedupStore.get() instanceof InMemorySsfJtiDedupStore inMemory) {
            Gauge.builder("easyssf.receiver.dedup.size", inMemory, InMemorySsfJtiDedupStore::size)
                    .description("Security event token identifiers remembered for de-duplication")
                    .register(meterRegistry);
        }
        return metrics;
    }
}
