package io.quarkiverse.ssf.receiver.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
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
 * endpoint, the HTTP client, de-duplication and the event type aliases.
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
     * They add to the built-in aliases of the SSF, CAEP and RISC event types (for
     * example {@code CaepSessionRevoked}, {@code RiscAccountDisabled}) and cannot
     * redefine one; a conflicting alias fails the start.
     */
    @ConfigDocMapKey("alias")
    Map<String, String> eventAliases();

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
    }

    interface Jdbc {
        /**
         * Whether processed SETs are remembered in the default datasource of the
         * application, if it has one ({@code quarkus-agroal}), so that all instances of
         * the application share them and they survive a restart. Otherwise they are kept
         * in memory.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Whether the table is created at startup if it does not exist. With {@code false}
         * the table has to be created with the statements in
         * {@code classpath:org/easyssf/receiver/jdbc/schema.sql}, for example by a schema
         * migration, and the start fails if it is missing.
         */
        @WithDefault("true")
        boolean initializeSchema();

        /** Prefix of the names of the tables. */
        @WithDefault(JdbcSsfSchema.DEFAULT_TABLE_PREFIX)
        String tablePrefix();

        /**
         * How often expired rows (processed SETs past {@code dedup.retention}) are
         * purged. The store also purges when it is written to; {@code 0} turns the
         * periodic cleanup off.
         */
        @WithDefault("15m")
        Duration cleanupInterval();
    }
}
