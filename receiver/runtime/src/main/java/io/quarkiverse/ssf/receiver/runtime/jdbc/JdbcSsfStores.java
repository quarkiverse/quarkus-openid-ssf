package io.quarkiverse.ssf.receiver.runtime.jdbc;

import javax.sql.DataSource;

import org.easyssf.receiver.jdbc.DataSourceSsfJdbcOperations;
import org.easyssf.receiver.jdbc.SsfJdbcOperations;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkus.arc.InjectableInstance;

/**
 * What the JDBC store producers share: whether the state of the receiver goes to the
 * default datasource, and the hint of the schema errors.
 */
final class JdbcSsfStores {

    static final String SCHEMA_HINT = "set quarkus.openid-ssf.receiver.jdbc.initialize-schema=true to have "
            + "it created or upgraded on startup, or quarkus.openid-ssf.receiver.jdbc.enabled=false to keep the state "
            + "of the receiver in memory";

    private JdbcSsfStores() {
    }

    /**
     * @return why the state is kept in memory, {@code null} if the default datasource is
     *         to be used
     */
    static String inMemoryReason(SsfReceiverConfig.Jdbc jdbc, InjectableInstance<DataSource> dataSource) {
        if (!jdbc.enabled()) {
            return "quarkus.openid-ssf.receiver.jdbc.enabled=false";
        }
        if (!dataSource.isResolvable() || !dataSource.getHandle().getBean().isActive()) {
            return "no default datasource is configured";
        }
        return null;
    }

    static SsfJdbcOperations operations(InjectableInstance<DataSource> dataSource) {
        return new DataSourceSsfJdbcOperations(dataSource.get());
    }
}
