package io.quarkiverse.ssf.receiver.runtime;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.easyssf.core.event.SubjectCompatibilityMode;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigGroup;
import io.smallrye.config.WithDefault;

/**
 * The settings of one SSF transmitter. {@link SsfReceiverConfig} binds them at
 * {@code quarkus.openid-ssf.receiver.*} for the {@code default} transmitter and at
 * {@code quarkus.openid-ssf.receiver.<name>.*} for every other one.
 */
@ConfigGroup
public interface SsfTransmitterConfig {

    /**
     * Issuer of the SSF transmitter, for example a Keycloak realm URL. Every inbound SET
     * must carry this value as its {@code iss} claim. It must use {@code https}, or
     * {@code http} on a loopback address, see {@link #allowInsecureHttp()}.
     */
    Optional<String> transmitterIssuer();

    /**
     * Location of the transmitter's SSF configuration metadata.
     *
     * <p>
     * If unset, the URL is derived from {@link #transmitterIssuer()} per SSF 1.0
     * section 7.2: {@code /.well-known/ssf-configuration} is inserted between the host
     * and the path of the issuer, so {@code https://tr.example.com/realms/r1} is looked
     * up at {@code https://tr.example.com/.well-known/ssf-configuration/realms/r1}. As
     * transmitters that grew out of OpenID providers tend to append it instead
     * ({@code https://tr.example.com/realms/r1/.well-known/ssf-configuration}), that
     * location is tried as a fallback.
     */
    Optional<URI> transmitterMetadataUrl();

    /**
     * Location of the JWK Set used to verify SET signatures. Discovered from the
     * transmitter metadata ({@code jwks_uri}) when not set.
     */
    Optional<URI> transmitterJwksUrl();

    /**
     * Whether the transmitter issuer and the endpoints it publishes may use plain
     * {@code http} on hosts other than loopback addresses. For test setups only, SSF
     * requires {@code https}: the metadata of the transmitter decides where keys come
     * from, and without TLS anyone on the path can change it.
     */
    @WithDefault("false")
    boolean allowInsecureHttp();

    /**
     * Audience this receiver is known as at the transmitter. When set, every inbound SET
     * must contain it in its {@code aud} claim. When not set and the stream is looked up
     * or registered at startup, the audience of the stream is expected instead.
     */
    Optional<String> expectedAudience();

    /**
     * Who manages the stream. {@code RECEIVER} (the default): the extension looks the
     * stream of this receiver up on startup and creates or updates it if necessary.
     * {@code TRANSMITTER}: the stream is created at the transmitter and its identifier
     * is pinned with {@link #streamId()}; without a stream id the receiver does not call
     * the stream management API on startup at all.
     */
    @WithDefault("RECEIVER")
    StreamManagement streamManagement();

    /**
     * Identifier of a stream that was created at the transmitter. When set, the stream
     * is looked up on startup to learn its audience and poll endpoint.
     */
    Optional<String> streamId();

    /**
     * How SETs are delivered. {@code PUSH} (the default, RFC 8935): the transmitter
     * posts each SET to the push endpoint of this application. {@code POLL} (RFC 8936):
     * the extension fetches SETs from the poll endpoint of the transmitter, see
     * {@link #poll()}.
     */
    @WithDefault("PUSH")
    DeliveryMethod deliveryMethod();

    /**
     * Event types this receiver wants to subscribe to, as a full URI or an alias (the
     * built-in aliases of the SSF, CAEP, RISC and SCIM event types,
     * {@code CaepSessionRevoked}, {@code RiscAccountDisabled}, {@code ScimProvCreateFull},
     * ... or one of {@code event-aliases}). Required for a stream managed by the
     * receiver, where it becomes {@code events_requested}; informational for a
     * transmitter-managed one.
     */
    Optional<List<String>> eventsRequested();

    /**
     * Static bearer access token to send on calls to the transmitter, for transmitters
     * such as <a href="https://ssf.caep.dev">caep.dev</a> that hand out long-lived
     * tokens out-of-band. Takes precedence over {@link #oauth2()} and the OIDC client.
     */
    Optional<String> transmitterAccessToken();

    /** PUSH delivery (RFC 8935) from this transmitter. */
    Push push();

    /** POLL delivery (RFC 8936) from this transmitter. */
    Poll poll();

    /** A stream managed by the receiver ({@code stream-management=RECEIVER}). */
    ReceiverManaged receiverManaged();

    /** Validation of inbound SETs. */
    SetValidation setValidation();

    /**
     * OAuth2 client credentials to authenticate with at the stream management and poll
     * endpoints of the transmitter, without {@code quarkus-oidc-client}. Active when
     * {@code oauth2.token-endpoint} is set.
     */
    Oauth2 oauth2();

    /**
     * The {@code quarkus-oidc-client} backed token provider, used when neither
     * {@link #transmitterAccessToken()} nor {@code oauth2.token-endpoint} is set and
     * {@code quarkus-oidc-client} is on the classpath. The client itself is configured
     * with {@code quarkus.oidc-client.*}.
     */
    Oidc oidc();

    interface Push {
        /**
         * Exact value the transmitter must send in the {@code Authorization} header, for
         * example {@code Bearer s3cr3t}. No header check is performed when not set.
         */
        Optional<String> expectedAuthHeader();

        /**
         * URL under which the transmitter reaches the push endpoint of this application,
         * advertised as {@code delivery.endpoint_url} of a stream managed by the
         * receiver. Required with {@code stream-management=RECEIVER} and PUSH delivery.
         */
        Optional<URI> deliveryEndpointUrl();
    }

    interface Poll {
        /**
         * Poll endpoint of the transmitter. Taken from the configuration of the stream
         * when not set, which needs the stream to be looked up or managed on startup.
         */
        Optional<URI> endpointUrl();

        /**
         * Whether the transmitter is polled by the extension, on a virtual thread of its
         * own per transmitter: every {@code interval}, or with one request outstanding
         * when {@code long-polling} is on. Set to {@code false} to drive polling from
         * application code with {@code SsfPollScheduler.pollNow()}.
         */
        @WithDefault("true")
        boolean autoStart();

        /** Time to wait before the first poll after startup. */
        @WithDefault("0s")
        Duration startDelay();

        /**
         * Time between two polls. With {@code long-polling}, the pause after a failed
         * request, or after a transmitter that does not hold requests answered an empty
         * one at once. Also the pause before the next attempt when the poll endpoint of
         * the stream was not known yet at the first one, as the stream is looked up in
         * the background; set {@code endpoint-url} to poll right at startup.
         */
        @WithDefault("30s")
        Duration interval();

        /**
         * Long polling (RFC 8936, section 2.5): the poller keeps one request outstanding
         * that the transmitter holds until SETs are available or
         * {@code long-polling-hold} elapses, instead of asking every {@code interval}
         * whether there is something. A SET is then fetched as soon as the transmitter
         * has it. The transmitter has to support long polling; one that answers at once
         * is polled every {@code interval} as with {@code false}.
         */
        @WithDefault("false")
        boolean longPolling();

        /**
         * How long the transmitter holds a long poll request, part of the agreement with
         * it (RFC 8936, section 2.2). The request waits that long plus a margin for the
         * response, {@code http.read-timeout} does not apply to it.
         */
        @WithDefault("30s")
        Duration longPollingHold();

        /** Maximum number of SETs to fetch with one request ({@code maxEvents}, RFC 8936). */
        @WithDefault("100")
        int maxEvents();

        /**
         * How the poller reacts when the transmitter rate-limits the poll endpoint
         * ({@code 429 Too Many Requests}, or {@code 503 Service Unavailable} with a
         * {@code Retry-After} header).
         */
        RateLimit rateLimit();
    }

    interface RateLimit {
        /**
         * Pause after a {@code 429} without a {@code Retry-After} header. Not set: poll
         * again at the regular interval.
         */
        Optional<Duration> fallbackBackoff();

        /**
         * Longest pause a {@code Retry-After} header or {@code fallback-backoff} can
         * cause. Protects the receiver from a transmitter that asks it to stay away for
         * hours.
         */
        @WithDefault("5m")
        Duration maxBackoff();
    }

    interface ReceiverManaged {
        /**
         * Whether the stream managed by the receiver is deleted at the transmitter when
         * the application stops, so that short-lived receivers (dev mode, tests) do not
         * leave streams behind. {@code false} keeps the stream across restarts.
         */
        @WithDefault("false")
        boolean deleteOnShutdown();

        /** Description of the stream managed by the receiver. */
        Optional<String> description();
    }

    /**
     * Defaults match the CAEP Interoperability Profile, which mandates RS256 with at
     * least 2048-bit keys.
     */
    interface SetValidation {
        /**
         * JWS algorithms accepted for SET signatures. Only asymmetric signature
         * algorithms are allowed. A SET signed with another algorithm is rejected.
         */
        @WithDefault("RS256")
        List<String> acceptedAlgorithms();

        /**
         * Minimum RSA key size in bits for SET signing keys. {@code 0} disables the
         * check.
         */
        @WithDefault("2048")
        int minRsaKeySize();

        /** Whether a SET must be explicitly typed with a {@code typ} header of {@code secevent+jwt}. */
        @WithDefault("true")
        boolean requireTypeHeader();

        /**
         * How strictly the subject of a SET is validated: {@code strict-ssf-1-0} requires
         * the top-level {@code sub_id} claim of SSF 1.0, {@code legacy} accepts SETs of
         * transmitters following earlier drafts, which put the subject into the event
         * payload.
         */
        @WithDefault("STRICT_SSF_1_0")
        SubjectCompatibilityMode subjectCompatibility();

        /** Tolerated clock skew when checking that a SET was not issued in the future. */
        @WithDefault("60s")
        Duration clockSkew();
    }

    interface Oauth2 {
        /**
         * Token endpoint to obtain access tokens from with the client credentials grant.
         * Setting it activates this provider.
         */
        Optional<URI> tokenEndpoint();

        /** Client id of this receiver. */
        Optional<String> clientId();

        /** Client secret of this receiver. */
        Optional<String> clientSecret();

        /** Scopes to request, sent space-separated in the {@code scope} parameter. */
        Optional<List<String>> scopes();

        /**
         * How the client authenticates at the token endpoint: {@code basic}
         * ({@code client_secret_basic}, the default) or {@code post}
         * ({@code client_secret_post}, credentials in the form body).
         */
        @WithDefault("basic")
        ClientAuthMethod clientAuthMethod();

        /**
         * Form parameters to send with the token request in addition to the grant, for
         * extensions of the token endpoint.
         */
        @ConfigDocMapKey("parameter")
        Map<String, String> additionalParams();

        /**
         * How long before its expiry an access token is renewed; never more than a
         * quarter of the token's lifetime.
         */
        @WithDefault("30s")
        Duration expirySafetyWindow();
    }

    interface Oidc {
        /**
         * Name of the OIDC client ({@code quarkus.oidc-client.<name>.*}) to obtain access
         * tokens from. The default client ({@code quarkus.oidc-client.*}) when not set.
         */
        Optional<String> clientName();

        /** Maximum time to wait for an access token from the OIDC client. */
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

    enum ClientAuthMethod {
        BASIC,
        POST
    }
}
