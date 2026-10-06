package io.quarkiverse.ssf.receiver.runtime.jdbc;

import javax.sql.DataSource;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.receiver.jdbc.JdbcSsfPollAckStore;
import org.easyssf.receiver.jdbc.JdbcSsfSchema;
import org.easyssf.receiver.jdbc.SsfJdbcOperations;
import org.easyssf.receiver.poll.InMemorySsfPollAckStore;
import org.easyssf.receiver.poll.SsfPollAckStore;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.InjectableInstance;

/**
 * The {@link SsfPollAckStore} with {@code quarkus-agroal}: the acknowledgements and
 * error reports a poller owes its transmitter wait in the default datasource of the
 * application until a poll request has carried them, so that a SET handled right before
 * a restart is acknowledged afterwards instead of being delivered again. Falls back to
 * the in-memory store when {@code jdbc.enabled=false} or no default datasource is
 * configured. Registered by the deployment processor instead of the in-memory producer
 * when Agroal is present.
 */
@Singleton
public class JdbcSsfPollAckStoreProducer {

    private static final Logger LOG = Logger.getLogger(JdbcSsfPollAckStoreProducer.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    InjectableInstance<DataSource> dataSource;

    @Produces
    @Singleton
    @DefaultBean
    public SsfPollAckStore ackStore() {
        SsfReceiverConfig.Jdbc jdbc = config.jdbc();
        String inMemoryReason = JdbcSsfStores.inMemoryReason(jdbc, dataSource);
        if (inMemoryReason != null) {
            LOG.debugf("Pending poll acknowledgements are kept in memory, %s", inMemoryReason);
            return new InMemorySsfPollAckStore();
        }
        String tablePrefix = jdbc.tablePrefix();
        String table = JdbcSsfSchema.pollAckTable(tablePrefix);
        SsfJdbcOperations operations = JdbcSsfStores.operations(dataSource);
        JdbcSsfSchema.prepareTable(operations, table, JdbcSsfSchema.createPollAckTable(tablePrefix),
                jdbc.initializeSchema(), JdbcSsfStores.SCHEMA_HINT);
        JdbcSsfPollAckStore store = new JdbcSsfPollAckStore(operations, tablePrefix);
        store.setRetention(jdbc.ackRetention());
        store.setDeleteBatchSize(jdbc.ackDeleteBatchSize());
        LOG.infof("Pending poll acknowledgements are kept in the table %s", table);
        return store;
    }
}
