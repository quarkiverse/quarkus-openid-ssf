package io.quarkiverse.ssf.receiver.runtime.dedup;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.SsfJtiDedupStore;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkus.arc.DefaultBean;

/**
 * The {@link SsfJtiDedupStore} without a datasource: the in-memory store of easyssf with
 * {@code dedup.capacity} entries. An application bean of the type replaces it.
 */
@Singleton
public class InMemorySsfJtiDedupStoreProducer {

    @Inject
    SsfReceiverConfig config;

    @Produces
    @Singleton
    @DefaultBean
    public SsfJtiDedupStore dedupStore() {
        return new InMemorySsfJtiDedupStore(config.dedup().capacity());
    }
}
