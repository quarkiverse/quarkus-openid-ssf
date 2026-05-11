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
 * Configuration for the {@code quarkus-ssf-receiver} extension. All properties
 * are prefixed with {@code ssf.receiver.}.
 *
 * <p>
 * Top-level properties cover the cross-cutting receiver identity (issuer,
 * audience, stream management mode, delivery method) and outbound auth
 * (static token vs OAuth2 vs OIDC). Per-feature settings live on nested
 * interfaces:
 * <ul>
 * <li>{@link Push} — inbound PUSH endpoint</li>
 * <li>{@link Poll} — outbound POLL loop</li>
 * <li>{@link ReceiverManaged} — discover-or-create stream registration</li>
 * <li>{@link TransmitterManaged} — startup stream probe</li>
 * <li>{@link Dedup} — jti deduplication layer</li>
 * <li>{@link Oauth2} — self-contained {@code client_credentials} provider</li>
 * <li>{@link Oidc} — call-site behavior for the {@code quarkus-oidc-client}-backed provider</li>
 * </ul>
 *
 * <p>
 * Outbound token-provider selection is made at build time, in this precedence:
 * Static ({@code transmitter-access-token}) → Oauth2 ({@code oauth2.token-endpoint})
 * → OIDC ({@code quarkus-oidc-client} on classpath) → no-op.
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

    /**
     * Who owns the stream lifecycle. {@link StreamManagement#RECEIVER} (the
     * default) means the extension performs a discover-or-create on startup
     * — see {@link ReceiverManaged} — and is the most common shape for
     * SSF receivers in practice. {@link StreamManagement#TRANSMITTER} means
     * the operator pre-creates the stream in the transmitter's admin console
     * and pins the assigned {@link #streamId()} here.
     */
    @WithDefault("RECEIVER")
    StreamManagement streamManagement();

    /**
     * Stream id pinned by the operator. Required when
     * {@link #streamManagement()} is {@link StreamManagement#TRANSMITTER};
     * optional in {@link StreamManagement#RECEIVER} mode (if set, the
     * registrar skips its discover-or-create step and uses this id directly).
     */
    Optional<String> streamId();

    /**
     * How SETs are delivered. {@link DeliveryMethod#PUSH} (the default,
     * RFC 8935) — the transmitter POSTs each SET to {@link Push#endpointPath()}.
     * {@link DeliveryMethod#POLL} (RFC 8936) — the extension pulls SETs from
     * the transmitter's poll endpoint; see {@link Poll}.
     */
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
     * Validation policy for inbound SETs — accepted JWS signature
     * algorithms, minimum key sizes, and (future) claim-level checks.
     * See {@link SetValidation}. Distinct from SSF Stream Verification
     * (which is an event-flow concept — see
     * {@code SsfStreamClient.requestVerification}).
     */
    SetValidation setValidation();

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
     * Short aliases for event-type URIs — used as the {@code event} tag value
     * on the {@code ssf.receiver.events.processed} meter and as accepted
     * input to {@link #eventsRequested()}. Keyed by alias, valued by URI:
     *
     * <pre>
     *   ssf.receiver.event-aliases.VendorWidgetReplaced=https://schemas.example.org/vendor/event-type/widget-replaced
     * </pre>
     *
     * <p>
     * Built-in aliases for the OpenID SSF, CAEP 1.0, and RISC 1.0 spec event
     * types are always registered out of the box (e.g.
     * {@code SsfStreamVerification}, {@code CaepSessionRevoked},
     * {@code RiscAccountDisabled}, …); user entries with the same URI
     * override the built-in alias name. Unknown URIs fall back to the URI
     * itself as the tag value.
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

    /**
     * Self-contained OAuth2 {@code client_credentials} grant settings for
     * outbound calls to the transmitter — used when the consumer doesn't want
     * to pull in {@code quarkus-oidc-client}. See {@link Oauth2}.
     */
    Oauth2 oauth2();

    /**
     * Fine-grained settings for the {@code quarkus-oidc-client}-backed
     * outbound token provider — orthogonal to {@code quarkus.oidc-client.*},
     * which governs the OIDC client itself. Only consulted when
     * {@code quarkus-oidc-client} is on the classpath AND neither
     * {@link #transmitterAccessToken()} nor {@link Oauth2#tokenEndpoint()}
     * is set (otherwise the higher-precedence provider wins). See {@link Oidc}.
     */
    Oidc oidc();

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

    /**
     * Validation policy for inbound SETs. Defaults match the CAEP 1.0
     * Interoperability Profile, which mandates RS256 with at least 2048-bit
     * keys. Broaden the allowlist if the transmitter signs with other
     * algorithms (ES256, EdDSA, …) and you've accepted that you fall
     * outside strict CAEP-Interop conformance.
     */
    interface SetValidation {
        /**
         * Allowed JWS {@code alg} values on inbound SETs. Any SET whose
         * header advertises an algorithm outside this list is rejected
         * before signature verification — defense against alg-substitution
         * attacks. Default is {@code [RS256]} (CAEP Interop §3.1).
         */
        @WithDefault("RS256")
        List<String> acceptedAlgorithms();

        /**
         * Minimum RSA key size, in bits, accepted for SET signature
         * verification. SETs signed with an RSA JWK whose modulus is shorter
         * than this are rejected. Default is {@code 2048} (CAEP Interop §3.1).
         * Set to {@code 0} to disable the check (not recommended).
         */
        @WithDefault("2048")
        int minRsaKeySize();
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

    /**
     * Self-contained OAuth2 grant settings — used when the consumer doesn't
     * want to pull in {@code quarkus-oidc-client} for outbound transmitter
     * auth. Activated whenever {@link Oauth2#tokenEndpoint()} is set; the
     * deployment processor then registers {@code Oauth2TransmitterTokenProvider}
     * (in preference to the OIDC-backed provider) and tokens are fetched and
     * cached in-process with an {@link Oauth2#expirySafetyWindow()}-aware
     * refresh policy.
     *
     * <p>
     * Default {@link Oauth2#grantType()} is {@code client_credentials}
     * (RFC 6749 §4.4) — the only grant the receiver typically needs for
     * outbound transmitter management calls. Other values let consumers
     * point at non-standard or extension grants if their IdP requires them.
     */
    interface Oauth2 {
        /**
         * Token endpoint URL where the OAuth2 grant is exchanged. Setting
         * this activates the OAuth2 provider; leaving it empty falls through
         * to the OIDC / no-op providers.
         */
        Optional<URI> tokenEndpoint();

        /**
         * OAuth2 grant type sent in the {@code grant_type} form parameter.
         * Default is {@code client_credentials} (RFC 6749 §4.4) — the only
         * grant the receiver needs for outbound transmitter calls. Override
         * for non-standard or extension grants (e.g.
         * {@code urn:ietf:params:oauth:grant-type:jwt-bearer}).
         */
        @WithDefault("client_credentials")
        String grantType();

        /**
         * Which client-authentication method to use when sending the
         * {@link #clientId()} / {@link #clientSecret()} pair. RFC 6749 §2.3.1
         * defines two:
         * <ul>
         * <li>{@code basic} — HTTP Basic header
         * ({@code Authorization: Basic base64(client_id:client_secret)}).
         * RECOMMENDED by the spec and the default here.</li>
         * <li>{@code post} — credentials in the form body
         * ({@code client_id=…&client_secret=…}). Some servers (notably caep.dev
         * and various older IdPs) only accept this form.</li>
         * </ul>
         */
        @WithDefault("basic")
        ClientAuthMethod clientAuthMethod();

        /**
         * OAuth2 client identifier. Sent in the {@code client_id} form
         * parameter when {@link #clientAuthMethod()} is {@code post}, or as
         * the basic-auth username when {@code basic}.
         */
        Optional<String> clientId();

        /**
         * OAuth2 client secret. Sent in the {@code client_secret} form
         * parameter when {@link #clientAuthMethod()} is {@code post}, or as
         * the basic-auth password when {@code basic}.
         */
        Optional<String> clientSecret();

        /**
         * Optional space-separated {@code scope} request parameter. List form
         * in config (e.g. {@code ssf.receiver.oauth2.scopes=ssf.read,ssf.manage});
         * the values are joined with single spaces on the wire.
         */
        Optional<List<String>> scopes();

        /**
         * Extra form parameters appended verbatim to the token-endpoint POST.
         * Escape hatch for server-specific extensions (e.g. {@code resource=}
         * on Microsoft Entra, vendor-specific tenant identifiers, etc.).
         */
        Map<String, String> additionalParams();

        /**
         * Subtracted from the token endpoint's reported {@code expires_in}
         * before the provider treats a cached token as expired. Default is 30s,
         * which covers typical clock skew + the duration of the outbound call
         * the token is about to authenticate.
         */
        @WithDefault("30s")
        Duration expirySafetyWindow();

        /** Connect / read timeout for the token endpoint POST. Default is 5s. */
        @WithDefault("5s")
        Duration timeout();
    }

    /**
     * Settings for the {@code quarkus-oidc-client}-backed outbound token
     * provider. These are receiver-extension wrappers around the OIDC client
     * — {@code quarkus.oidc-client.*} is the underlying client config; this
     * interface holds the call-site behavior the extension layers on top
     * (e.g. how long to wait for a token, retry policy, …) and isn't
     * something the OIDC client itself exposes.
     */
    interface Oidc {
        /**
         * Maximum time to wait when fetching an access token from
         * {@code OidcClient.getTokens()} for outbound calls to the
         * transmitter. Bounds the {@code Uni.await().atMost(...)} on the
         * call site so a slow / unresponsive OIDC token endpoint doesn't
         * stall a Vert.x event loop indefinitely. Defaults to 2s.
         */
        @WithDefault("2s")
        Duration tokenTimeout();
    }

    enum StreamManagement {
        TRANSMITTER,
        RECEIVER
    }

    enum DeliveryMethod {
        PUSH,
        POLL
    }

    /** RFC 6749 §2.3.1 client-authentication methods supported on the OAuth2 token endpoint. */
    enum ClientAuthMethod {
        /** HTTP Basic header — RFC 6749 §2.3.1 RECOMMENDED. */
        BASIC,
        /** {@code client_id} + {@code client_secret} in the form body. */
        POST
    }
}
