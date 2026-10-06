package io.quarkiverse.ssf.receiver.runtime.jdbc;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.receiver.jdbc.JdbcSsfExpiringStore;
import org.easyssf.receiver.jdbc.JdbcSsfJtiDedupStore;
import org.easyssf.receiver.jdbc.JdbcSsfSchema;
import org.easyssf.receiver.jdbc.JdbcSsfStoreCleanup;
import org.easyssf.receiver.jdbc.SsfJdbcOperations;
import org.easyssf.receiver.poll.SsfPollAckStore;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.SsfJtiDedupStore;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.InjectableInstance;

/**
 * The {@link SsfJtiDedupStore} with {@code quarkus-agroal}: processed SETs are remembered
 * in the default datasource of the application, so that all instances of the application
 * share them and they survive a restart. Falls back to the in-memory store when
 * {@code jdbc.enabled=false} or no default datasource is configured. Registered by the
 * deployment processor instead of the in-memory producer when Agroal is present.
 *
 * <p>
 * With {@code jdbc.initialize-schema=true} the table is created if it is missing and
 * gets the columns a release added ({@code STATE} in easyssf 0.3.0, {@code PROCESSED_AT}
 * renamed to {@code STATE_CHANGED_AT} in 0.4.0); with {@code false}
 * the start fails naming the statements to run.
 */
@Singleton
public class JdbcSsfJtiDedupStoreProducer {

    private static final Logger LOG = Logger.getLogger(JdbcSsfJtiDedupStoreProducer.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    InjectableInstance<DataSource> dataSource;

    @Produces
    @Singleton
    @DefaultBean
    public SsfJtiDedupStore dedupStore() {
        SsfReceiverConfig.Jdbc jdbc = config.jdbc();
        String inMemoryReason = JdbcSsfStores.inMemoryReason(jdbc, dataSource);
        if (inMemoryReason != null) {
            return inMemory(inMemoryReason);
        }
        String tablePrefix = jdbc.tablePrefix();
        String table = JdbcSsfSchema.processedSetTable(tablePrefix);
        SsfJdbcOperations operations = JdbcSsfStores.operations(dataSource);
        JdbcSsfSchema.prepareTable(operations, table, JdbcSsfSchema.createProcessedSetTable(tablePrefix),
                JdbcSsfSchema.processedSetUpgrades(tablePrefix), jdbc.initializeSchema(), JdbcSsfStores.SCHEMA_HINT);
        JdbcSsfJtiDedupStore store = new JdbcSsfJtiDedupStore(operations, tablePrefix);
        store.setRetention(config.dedup().retention());
        store.setLease(config.dedup().lease());
        LOG.infof("Processed SETs are remembered in the table %s", table);
        return store;
    }

    /**
     * Purges the expired rows of the JDBC stores every {@code jdbc.cleanup-interval}; the
     * {@code SsfReceiverLifecycle} starts and stops it.
     */
    @Produces
    @Singleton
    @DefaultBean
    public JdbcSsfStoreCleanup storeCleanup(SsfJtiDedupStore dedupStore, SsfPollAckStore ackStore) {
        List<JdbcSsfExpiringStore> stores = new ArrayList<>();
        if (dedupStore instanceof JdbcSsfExpiringStore expiring) {
            stores.add(expiring);
        }
        if (ackStore instanceof JdbcSsfExpiringStore expiring) {
            stores.add(expiring);
        }
        return new JdbcSsfStoreCleanup(stores, config.jdbc().cleanupInterval());
    }

    private SsfJtiDedupStore inMemory(String reason) {
        LOG.debugf("Processed SETs are remembered in memory, %s", reason);
        InMemorySsfJtiDedupStore store = new InMemorySsfJtiDedupStore(config.dedup().capacity());
        store.setLease(config.dedup().lease());
        return store;
    }
}
