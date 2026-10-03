package io.quarkiverse.ssf.receiver.runtime.jdbc;

import java.util.List;

import javax.sql.DataSource;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.receiver.jdbc.DataSourceSsfJdbcOperations;
import org.easyssf.receiver.jdbc.JdbcSsfJtiDedupStore;
import org.easyssf.receiver.jdbc.JdbcSsfSchema;
import org.easyssf.receiver.jdbc.JdbcSsfStoreCleanup;
import org.easyssf.receiver.jdbc.SsfJdbcOperations;
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
 */
@Singleton
public class JdbcSsfJtiDedupStoreProducer {

    private static final Logger LOG = Logger.getLogger(JdbcSsfJtiDedupStoreProducer.class);

    private static final String SCHEMA_HINT = "set quarkus.openid-ssf.receiver.jdbc.initialize-schema=true to have "
            + "it created on startup, or quarkus.openid-ssf.receiver.jdbc.enabled=false to keep the state of the "
            + "receiver in memory";

    @Inject
    SsfReceiverConfig config;

    @Inject
    InjectableInstance<DataSource> dataSource;

    @Produces
    @Singleton
    @DefaultBean
    public SsfJtiDedupStore dedupStore() {
        SsfReceiverConfig.Jdbc jdbc = config.jdbc();
        if (!jdbc.enabled()) {
            return inMemory("quarkus.openid-ssf.receiver.jdbc.enabled=false");
        }
        if (!dataSource.isResolvable() || !dataSource.getHandle().getBean().isActive()) {
            return inMemory("no default datasource is configured");
        }
        String tablePrefix = jdbc.tablePrefix();
        String table = JdbcSsfSchema.processedSetTable(tablePrefix);
        SsfJdbcOperations operations = new DataSourceSsfJdbcOperations(dataSource.get());
        JdbcSsfSchema.prepareTable(operations, table, JdbcSsfSchema.createProcessedSetTable(tablePrefix),
                jdbc.initializeSchema(), SCHEMA_HINT);
        JdbcSsfJtiDedupStore store = new JdbcSsfJtiDedupStore(operations, tablePrefix);
        store.setRetention(config.dedup().retention());
        LOG.infof("Processed SETs are remembered in the table %s", table);
        return store;
    }

    /**
     * Purges the expired rows of the JDBC store every {@code jdbc.cleanup-interval}; the
     * {@code SsfReceiverLifecycle} starts and stops it.
     */
    @Produces
    @Singleton
    @DefaultBean
    public JdbcSsfStoreCleanup storeCleanup(SsfJtiDedupStore dedupStore) {
        List<org.easyssf.receiver.jdbc.JdbcSsfExpiringStore> stores = (dedupStore instanceof JdbcSsfJtiDedupStore jdbcStore)
                ? List.of(jdbcStore)
                : List.of();
        return new JdbcSsfStoreCleanup(stores, config.jdbc().cleanupInterval());
    }

    private SsfJtiDedupStore inMemory(String reason) {
        LOG.debugf("Processed SETs are remembered in memory, %s", reason);
        return new InMemorySsfJtiDedupStore(config.dedup().capacity());
    }
}
