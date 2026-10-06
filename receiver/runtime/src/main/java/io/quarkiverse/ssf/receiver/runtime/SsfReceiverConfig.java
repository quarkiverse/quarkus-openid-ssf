package io.quarkiverse.ssf.receiver.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.easyssf.receiver.jdbc.JdbcSsfSchema;
import org.easyssf.receiver.transmitter.SsfTransmitter;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigDocSection;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithDefaults;
import io.smallrye.config.WithParentName;
import io.smallrye.config.WithUnnamedKey;

/**
 * Configuration of the {@code quarkus-openid-ssf-receiver} extension, prefixed with
 * {@code quarkus.openid-ssf.receiver.}.
 *
 * <p>
 * The settings of a transmitter ({@link SsfTransmitterConfig}) at
 * {@code quarkus.openid-ssf.receiver.*} configure the {@code default} transmitter;
 * {@code quarkus.openid-ssf.receiver.<name>.*} configures a further, named transmitter,
 * each complete on its own. Everything else is shared by all transmitters: the push
 * endpoint, the HTTP client, de-duplication, the database stores and the event type
 * aliases.
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "quarkus.openid-ssf.receiver")
public interface SsfReceiverConfig {

    /**
     * Master kill switch for the SSF receiver. When {@code false}, no push endpoint is
     * registered, no stream is looked up or registered and no transmitter is polled.
     * The beans of the extension stay injectable, but need a configured transmitter to
     * do anything.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The transmitters this receiver gets events from. The settings directly under
     * {@code quarkus.openid-ssf.receiver.} configure the transmitter named
     * {@code default}; {@code quarkus.openid-ssf.receiver.<name>.} configures another
     * transmitter with the same settings. The name is the {@code transmitter} tag of the
     * metrics.
     */
    @WithParentName
    @WithDefaults
    @WithUnnamedKey(SsfTransmitter.DEFAULT_NAME)
    @ConfigDocMapKey("transmitter-name")
    @ConfigDocSection
    Map<String, SsfTransmitterConfig> transmitters();

    /**
     * Aliases for event type URIs, usable wherever an event type is named: in
     * {@code events-requested}, in handlers ({@code eventContext.hasEvent("AcmeLogin")})
     * and as the {@code event} tag of the metrics. Keyed by alias, valued by URI:
     *
     * <pre>
     *   quarkus.openid-ssf.receiver.event-aliases.AcmeLogin=https://events.acme.example/login
     * </pre>
     *
     * <p>
     * They add to the built-in aliases of the SSF, CAEP, RISC and SCIM event types
     * (for example {@code CaepSessionRevoked}, {@code RiscAccountDisabled},
     * {@code ScimProvCreateFull}) and cannot redefine one; a conflicting alias fails
     * the start.
     */
    @ConfigDocMapKey("alias")
    Map<String, String> eventAliases();

    /**
     * The members of a complex subject the application interprets. A transmitter can
     * declare members critical ({@code critical_subject_members} in its metadata); a SET
     * whose subject has such a member that is not listed here is rejected as
     * {@code invalid_request}, as the SSF specification requires critical members to be
     * interpreted by the receiver. Defaults to the members {@code SsfSubject} gives access
     * to.
     */
    @WithDefault("user,session,device,application,tenant,org_unit,group")
    List<String> understoodSubjectMembers();

    /** The push endpoint of this application (RFC 8935), shared by all transmitters. */
    PushEndpoint push();

    /** Outbound HTTP calls to the transmitters. */
    Http http();

    /** De-duplication of SETs by {@code jti}. */
    Dedup dedup();

    /** Stores that keep the state of the receiver in the database of the application. */
    Jdbc jdbc();

    /**
     * The transmitters that are configured, by name: the default one if its issuer is
     * set, and the named ones.
     */
    default Map<String, SsfTransmitterConfig> configuredTransmitters() {
        Map<String, SsfTransmitterConfig> configured = new LinkedHashMap<>();
        transmitters().forEach((name, transmitter) -> {
            boolean isDefault = SsfTransmitter.DEFAULT_NAME.equals(name);
            if (!isDefault || transmitter.transmitterIssuer().filter(issuer -> !issuer.isBlank()).isPresent()) {
                configured.put(name, transmitter);
            }
        });
        return configured;
    }

    interface PushEndpoint {
        /**
         * Path of the push endpoint, relative to {@code quarkus.http.root-path}. The
         * transmitters with {@code delivery-method=PUSH} post their SETs there; which
         * transmitter sent a SET is told by its issuer.
         */
        @WithDefault("/ssf/push")
        String endpointPath();
    }

    interface Http {
        /** Connect timeout for calls to the transmitters. */
        @WithDefault("5s")
        Duration connectTimeout();

        /**
         * Read timeout for calls to the transmitters: how long to wait for the response
         * to a metadata, JWK Set, stream management, token or poll request.
         */
        @WithDefault("5s")
        Duration readTimeout();

        /**
         * {@code User-Agent} header for calls to the transmitters. The default of the JDK
         * HTTP client ({@code Java-http-client/<version>}) is sent when not set.
         */
        Optional<String> userAgent();
    }

    interface Dedup {
        /**
         * Whether SETs that were already processed are skipped. A SET that was processed
         * before is acknowledged ({@code 202} on PUSH, acknowledged on POLL) without
         * invoking the handlers again. Set to {@code false} when the handlers are
         * idempotent anyway.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Number of SET identifiers the in-memory store remembers. Overflow evicts the
         * oldest. Ignored by a JDBC store or a custom {@code SsfJtiDedupStore} bean.
         */
        @WithDefault("10000")
        int capacity();

        /**
         * How long the JDBC store remembers a processed SET. Has to cover the time the
         * transmitter keeps trying to deliver a SET.
         */
        @WithDefault("7d")
        Duration retention();

        /**
         * How long a SET stays claimed while its handlers run. A SET is claimed in the
         * store before the handlers run and marked processed afterwards; another
         * instance that receives the same SET meanwhile neither handles nor acknowledges
         * it, the transmitter delivers it again. A claim older than the lease counts as
         * abandoned by an instance that crashed, and the SET is handled again. Set it
         * longer than the longest handler.
         */
        @WithDefault("60s")
        Duration lease();
    }

    interface Jdbc {
        /**
         * Whether the state of the receiver is kept in the default datasource of the
         * application, if it has one ({@code quarkus-agroal}), so that all instances of
         * the application share it and it survives a restart: the processed SETs
         * ({@code <prefix>PROCESSED_SET}) and, with POLL delivery, the acknowledgements
         * a poller owes its transmitter ({@code <prefix>POLL_ACK}). Otherwise both are
         * kept in memory.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Whether the tables are created at startup if they do not exist, and columns a
         * release added are added to an existing table. With {@code false} the tables
         * have to be created with the statements in
         * {@code classpath:org/easyssf/receiver/jdbc/schema.sql} (a fresh installation)
         * or the versioned scripts in
         * {@code classpath:org/easyssf/receiver/jdbc/migration/} (Flyway naming, plain
         * SQL), for example by a schema migration, and the start fails naming the
         * statements to run if a table or a column is missing.
         */
        @WithDefault("true")
        boolean initializeSchema();

        /** Prefix of the names of the tables. */
        @WithDefault(JdbcSsfSchema.DEFAULT_TABLE_PREFIX)
        String tablePrefix();

        /**
         * How often expired rows (processed SETs past {@code dedup.retention},
         * acknowledgements past {@code jdbc.ack-retention}) are purged. The stores also
         * purge when they are written to; {@code 0} turns the periodic cleanup off.
         */
        @WithDefault("15m")
        Duration cleanupInterval();

        /**
         * How long an acknowledgement or error report the transmitter never accepted is
         * kept in the {@code <prefix>POLL_ACK} table.
         */
        @WithDefault("7d")
        Duration ackRetention();

        /**
         * How many acknowledgements one {@code DELETE} statement removes from the
         * {@code <prefix>POLL_ACK} table ({@code JTI IN (...)}). Databases limit the
         * number of parameters of a statement (SQL Server to 2100, Oracle to 1000 list
         * members); a smaller batch means more statements per poll.
         */
        @WithDefault("100")
        int ackDeleteBatchSize();
    }
}
