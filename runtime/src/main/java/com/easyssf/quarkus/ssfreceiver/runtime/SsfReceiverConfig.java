/*
 * Copyright 2026 Thomas Darimont and the easyssf.com contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.easyssf.quarkus.ssfreceiver.runtime;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for the SSF receiver extension.
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "ssf.receiver")
public interface SsfReceiverConfig {

    /**
     * Master kill switch for the SSF receiver. When {@code false}, all startup
     * observers (validator, push route registration, transmitter probe,
     * receiver-managed registrar, POLL scheduler) become no-ops and no inbound
     * SETs are accepted or pulled. Other beans ({@code SsfStreamClient},
     * metrics, alias resolver) stay wired in CDI so application code that
     * touches them directly still works — but nothing happens automatically.
     *
     * <p>
     * Useful for {@code %test.ssf.receiver.enabled=false} or runtime
     * kill-switch via env var. Defaults to {@code true} so existing apps
     * upgrade without behavior change.
     *
     * <p>
     * Note: {@link #transmitterIssuer()} is still resolved by the config
     * layer at startup, so even when disabled, set it to any non-empty URI
     * (the value is never read in disabled mode).
     */
    @WithDefault("true")
    boolean enabled();

    /** Issuer URL of the SSF transmitter (e.g. a Keycloak realm URL). */
    URI transmitterIssuer();

    /**
     * Expected {@code aud} value on inbound SETs. If set, the SET must contain a matching
     * audience entry — otherwise it is rejected with 400. If absent, no audience check is
     * performed.
     */
    Optional<String> expectedAudience();

    /** Who owns the stream lifecycle. v1 only supports {@link StreamManagement#TRANSMITTER}. */
    @WithDefault("TRANSMITTER")
    StreamManagement streamManagement();

    /** Stream id assigned by the transmitter. Required when stream-management = TRANSMITTER. */
    Optional<String> streamId();

    /** Delivery method. v1 only supports {@link DeliveryMethod#PUSH}. */
    @WithDefault("PUSH")
    DeliveryMethod deliveryMethod();

    /** Configuration for the inbound PUSH endpoint. */
    Push push();

    /**
     * Configuration for the outbound POLL loop. Only consulted when {@link #deliveryMethod()} is {@link DeliveryMethod#POLL}.
     */
    Poll poll();

    /**
     * Configuration for receiver-managed stream lifecycle. Only consulted when {@link #streamManagement()} is
     * {@link StreamManagement#RECEIVER}.
     */
    ReceiverManaged receiverManaged();

    /** Configuration for the {@code jti} deduplication layer. */
    Dedup dedup();

    /**
     * Configuration for transmitter-managed stream startup probing. Only consulted when {@link #streamManagement()} is
     * {@link StreamManagement#TRANSMITTER}.
     */
    TransmitterManaged transmitterManaged();

    /**
     * Explicit URL of the transmitter's SSF metadata document.
     *
     * <p>
     * If unset, the URL is derived from {@link #transmitterIssuer()} per
     * SSF spec §7.2 — {@code /.well-known/ssf-configuration} is inserted
     * <em>between the host and the path</em> of the issuer (NOT appended to
     * the end). For example:
     * <ul>
     * <li>Issuer {@code https://tr.example.com} → metadata at
     * {@code https://tr.example.com/.well-known/ssf-configuration}</li>
     * <li>Issuer {@code https://tr.example.com/realms/r1} → metadata at
     * {@code https://tr.example.com/.well-known/ssf-configuration/realms/r1}</li>
     * </ul>
     *
     * <p>
     * Set this property explicitly when the transmitter doesn't follow the
     * SSF rule — for example, OIDC-derived transmitters that serve the
     * document at the OIDC-style appended path
     * ({@code <issuer>/.well-known/ssf-configuration}) instead.
     */
    Optional<URI> transmitterMetadataUrl();

    /** JWKS URL of the transmitter. Defaults to the {@code jwks_uri} advertised in transmitter metadata. */
    Optional<URI> transmitterJwksUrl();

    /**
     * Static bearer access token to send on outbound calls to the transmitter. If
     * set, the deployment processor registers a {@code StaticTransmitterTokenProvider}
     * <em>instead of</em> the OIDC-backed one — useful for transmitters such as
     * <a href="https://ssf.caep.dev">caep.dev</a> that issue long-lived bearer
     * tokens out-of-band rather than via an OAuth grant. Mutually exclusive with
     * {@code quarkus-oidc-client}; when both are configured this token wins.
     */
    Optional<String> transmitterAccessToken();

    /**
     * Event types this receiver wants to subscribe to. Required when
     * {@link #streamManagement()} is {@link StreamManagement#RECEIVER} (sent in the
     * {@code events_requested} field of the create-stream request). Informational
     * for transmitter-managed streams — the transmitter (e.g. Keycloak admin)
     * already controls the actual subscription set.
     *
     * <p>
     * Each entry can be a full URI (e.g.
     * {@code https://schemas.openid.net/secevent/caep/event-type/session-revoked})
     * or a short alias registered with {@link #eventAliases() event-aliases}
     * — including the SSF / CAEP / RISC built-ins (e.g. {@code CaepSessionRevoked},
     * {@code RiscAccountDisabled}). Resolution is performed by
     * {@code com.easyssf.quarkus.ssfreceiver.runtime.event.SsfAliases#resolveEventTypeRef(String)}
     * at startup; an unregistered name fails fast with a list of available aliases.
     *
     * <p>
     * Example:
     *
     * <pre>
     * ssf.receiver.events-requested=CaepSessionRevoked,CaepCredentialChange,\
     *     https://schemas.example.org/vendor/event-type/x
     * </pre>
     */
    Optional<List<String>> eventsRequested();

    /**
     * Short aliases for event-type URIs — used as the {@code event} tag value on
     * the {@code ssf.receiver.events.processed} meter. Keyed by alias, valued by URI:
     *
     * <pre>
     *   ssf.receiver.event-aliases.CaepSessionRevoked=https://schemas.openid.net/secevent/caep/event-type/session-revoked
     * </pre>
     *
     * <p>
     * Built-in aliases for the SSF spec event types (verification,
     * stream-updated) are always registered; user entries with the same URI
     * override them. Unknown URIs fall back to the URI itself as the tag value.
     */
    Map<String, URI> eventAliases();

    /**
     * Short aliases for transmitter issuer URLs — used as the {@code iss} tag
     * value on the {@code ssf.receiver.events.processed} meter. Keyed by alias,
     * valued by issuer URL:
     *
     * <pre>
     *   ssf.receiver.issuer-aliases.KeycloakSsfPoc=https://id.localhost/realms/ssf-poc
     *   ssf.receiver.issuer-aliases.CaepDev=https://ssf.caep.dev
     * </pre>
     *
     * <p>
     * No built-in defaults — issuer URLs are deployment-specific. Unknown
     * URLs fall back to the URL itself as the tag value.
     */
    Map<String, URI> issuerAliases();

    /**
     * Short, stable name for <em>this</em> receiver — surfaced as the
     * {@code receiver} tag on the {@code ssf.receiver.events.processed} meter
     * so multiple receiver instances scraping into the same monitoring store
     * stay distinguishable.
     *
     * <p>
     * Falls back to {@link #expectedAudience()} if unset, then to
     * {@code "unknown"}.
     */
    Optional<String> alias();

    /** Reserved; OIDC client config drives outbound auth via {@code quarkus.oidc-client.*}. */
    Optional<String> clientId();

    /** Reserved; OIDC client config drives outbound auth via {@code quarkus.oidc-client.*}. */
    Optional<String> clientSecret();

    /**
     * Maximum time to wait when fetching an access token from {@code OidcClient} for
     * outbound calls to the transmitter. Only consulted when {@code quarkus-oidc-client}
     * is on the classpath. Defaults to 2 seconds.
     */
    @WithDefault("2s")
    Duration oidcClientTokenTimeout();

    interface Push {
        /** Path of the push endpoint, relative to {@code quarkus.http.root-path}. */
        @WithDefault("/ssf/push")
        String endpointPath();

        /** If set, the push endpoint requires this exact value in the inbound {@code Authorization} header. */
        Optional<String> expectedAuthHeader();

        /**
         * Externally-reachable URL of the push endpoint, advertised to the transmitter
         * as {@code delivery.endpoint_url} in the create-stream request. Required when
         * {@code stream-management=RECEIVER}; ignored otherwise.
         */
        Optional<URI> deliveryEndpointUrl();
    }

    interface Poll {
        /**
         * Poll endpoint URL. Optional override; if absent, the extension reads it
         * from the stream's {@code delivery.endpoint_url} via the
         * {@code configuration_endpoint} on startup. Per RFC 8936, this URL is
         * advertised by the transmitter — receivers normally don't pin it.
         */
        Optional<URI> endpointUrl();

        /**
         * If {@code true} (the default), the poller schedules a periodic Vert.x
         * timer on startup. Set to {@code false} to keep the poller idle —
         * application code drives polling explicitly via
         * {@code SsfPoller.pollNow()} (e.g. behind a REST endpoint, a scheduled
         * job, or a Kafka consumer trigger).
         */
        @WithDefault("true")
        boolean autoStart();

        /**
         * Delay before the first poll fires after startup. Useful when other
         * components need to warm up first (DB pool, config server, …).
         * Defaults to {@code 0s} — poll immediately.
         */
        @WithDefault("0s")
        Duration startDelay();

        /** How often to poll the transmitter. Defaults to 30s. */
        @WithDefault("30s")
        Duration interval();

        /** {@code maxEvents} parameter sent on each poll request (RFC 8936 §2.1). Defaults to 100. */
        @WithDefault("100")
        int maxEvents();

        /**
         * {@code returnImmediately} parameter (RFC 8936 §2.1). When {@code false},
         * the transmitter holds the request open until events are available
         * (long-poll). Defaults to {@code true}.
         */
        @WithDefault("true")
        boolean returnImmediately();

        /**
         * If {@code true} and the transmitter sets {@code moreAvailable=true},
         * keep polling synchronously until the queue drains rather than waiting
         * for the next periodic tick. Defaults to {@code true}.
         */
        @WithDefault("true")
        boolean drainOnPoll();

        /** Connect/read timeout for outbound poll requests. Defaults to 30s. */
        @WithDefault("30s")
        Duration timeout();
    }

    interface ReceiverManaged {
        /**
         * If {@code true}, the extension performs a discover-or-create dance on app
         * startup: it lists the receiver's existing streams on the transmitter
         * (§7.1.1.2 with no {@code stream_id}), reuses one whose
         * {@code delivery.endpoint_url} (or audience) matches this receiver, and
         * creates a new stream if none matches. Defaults to {@code true}.
         *
         * <p>
         * Set to {@code false} to manage stream lifecycle entirely from
         * application code (e.g. via {@code SsfStreamClient.createStream}).
         */
        @WithDefault("true")
        boolean registerStream();

        /**
         * If {@code true}, the extension issues a stream-delete call against the
         * transmitter on app shutdown so the transmitter doesn't accumulate stale
         * stream registrations from short-lived receivers (dev mode, tests, …).
         * Defaults to {@code false} so a normal restart keeps the existing stream.
         */
        @WithDefault("false")
        boolean deleteOnShutdown();

        /** Optional human-readable description sent in the create-stream request. */
        Optional<String> description();
    }

    interface Dedup {
        /**
         * Master switch for the {@code jti} dedup layer. When {@code true} (the
         * default), the extension consults {@code SsfJtiDedupStore.seenBefore(jti)}
         * before invoking the application's {@code SsfEventHandler} on both PUSH
         * and POLL paths; duplicates are silently dropped (still {@code 202} on
         * PUSH, still acked on POLL — the SET was successfully received).
         *
         * <p>
         * Set to {@code false} when the application's handler is naturally
         * idempotent (e.g. natural-key upsert into a database) and the extra
         * lookup is wasted work.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Capacity of the default in-memory dedup store. Overflow evicts the
         * oldest jti. Tune to roughly cover the longest expected redelivery
         * window — defaults to {@code 10_000}, which on a typical SSF stream
         * (a few events per second peak) is several minutes of memory.
         *
         * <p>
         * Ignored by custom {@code SsfJtiDedupStore} implementations.
         */
        @WithDefault("10000")
        int capacity();
    }

    interface TransmitterManaged {
        /**
         * If {@code true} (the default), on startup the extension fetches the
         * configured stream's configuration and status from the transmitter and
         * logs a one-line summary — useful for confirming the stream exists and
         * is enabled. Probe failures are warnings, never fatal.
         *
         * <p>
         * Set to {@code false} when the receiver doesn't have outbound
         * credentials configured (push-only with public JWKS), or to keep boot
         * silent.
         */
        @WithDefault("true")
        boolean probeOnStartup();
    }

    enum StreamManagement {
        TRANSMITTER,
        RECEIVER
    }

    enum DeliveryMethod {
        PUSH,
        POLL
    }
}
