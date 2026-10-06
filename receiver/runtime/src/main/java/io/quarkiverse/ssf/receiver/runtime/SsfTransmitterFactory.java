package io.quarkiverse.ssf.receiver.runtime;

import java.net.URI;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.poll.SsfPollAckStore;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.stream.SsfStreamVerification;
import org.easyssf.receiver.transmitter.ClientCredentialsSsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterUriPolicy;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.auth.NoopTransmitterTokenProvider;
import io.quarkiverse.ssf.receiver.runtime.auth.OidcTransmitterTokenProviders;
import io.quarkiverse.ssf.receiver.runtime.auth.StaticTransmitterTokenProvider;
import io.quarkus.runtime.configuration.ConfigurationException;

/**
 * Builds the parts of a transmitter from its {@link SsfTransmitterConfig}: the easyssf
 * metadata resolver, SET verifier, token provider, stream client, stream registrar and
 * poller. Configuration errors surface as {@link ConfigurationException} naming the
 * property.
 */
public final class SsfTransmitterFactory {

    private static final Logger LOG = Logger.getLogger(SsfTransmitterFactory.class);

    private static final String PREFIX = "quarkus.openid-ssf.receiver.";

    private SsfTransmitterFactory() {
    }

    /** The prefix of the properties of the transmitter, for messages. */
    public static String prefix(String name) {
        return SsfTransmitter.DEFAULT_NAME.equals(name) ? PREFIX : PREFIX + name + ".";
    }

    public static String issuer(String name, SsfTransmitterConfig config) {
        return config.transmitterIssuer().filter(issuer -> !issuer.isBlank())
                .orElseThrow(() -> new ConfigurationException(
                        prefix(name) + "transmitter-issuer: the issuer of the SSF transmitter must be configured"));
    }

    public static SsfTransmitterUriPolicy uriPolicy(SsfTransmitterConfig config) {
        return config.allowInsecureHttp() ? SsfTransmitterUriPolicy.INSECURE : SsfTransmitterUriPolicy.DEFAULT;
    }

    public static SsfTransmitterMetadataResolver metadataResolver(String name, SsfTransmitterConfig config,
            SsfHttpClient httpClient) {
        String issuer = issuer(name, config);
        SsfTransmitterMetadataResolver resolver;
        try {
            resolver = new SsfTransmitterMetadataResolver(issuer, config.transmitterMetadataUrl().orElse(null),
                    httpClient, uriPolicy(config));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(prefix(name) + "transmitter-issuer: " + e.getMessage(), e);
        }
        LOG.infof("SSF transmitter %s: metadata is resolved from %s", describe(name, issuer),
                resolver.getMetadataUris());
        if (config.allowInsecureHttp()) {
            List<URI> insecure = Stream
                    .concat(Stream.of(URI.create(issuer), config.transmitterJwksUrl().orElse(null)),
                            resolver.getMetadataUris().stream())
                    .filter(uri -> uri != null && SsfTransmitterUriPolicy.isInsecure(uri)
                            && !SsfTransmitterUriPolicy.isLoopback(uri))
                    .toList();
            LOG.warnf("%sallow-insecure-http is on, the SSF transmitter %s is used without TLS. For development only%s",
                    prefix(name), describe(name, issuer), insecure.isEmpty() ? "." : ": " + insecure);
        }
        return resolver;
    }

    public static NimbusSsfSetVerifier verifier(String name, SsfTransmitterConfig config, SsfHttpClient httpClient,
            SsfTransmitterMetadataResolver metadataResolver, SsfReceiverStream receiverStream) {
        SsfTransmitterConfig.SetValidation validation = config.setValidation();
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(issuer(name, config),
                jwkSetUri(name, config, metadataResolver), httpClient);
        if (config.expectedAudience().filter(aud -> !aud.isBlank()).isPresent()) {
            verifier.setExpectedAudience(config.expectedAudience().get());
        } else if (registersStream(config)) {
            // the transmitter tells the audience of the stream
            verifier.setExpectedAudiences(receiverStream::getAudience);
        } else {
            LOG.warnf("%sexpected-audience is not set, SETs that the SSF transmitter %s issued for other receivers "
                    + "are accepted as well", prefix(name), describe(name, issuer(name, config)));
        }
        try {
            verifier.setAcceptedAlgorithms(validation.acceptedAlgorithms());
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(
                    prefix(name) + "set-validation.accepted-algorithms: " + e.getMessage(), e);
        }
        verifier.setMinRsaKeySize(validation.minRsaKeySize());
        verifier.setRequireTypeHeader(validation.requireTypeHeader());
        verifier.setSubjectCompatibilityMode(validation.subjectCompatibility());
        verifier.setClockSkew(validation.clockSkew());
        return verifier;
    }

    /**
     * Chooses how the transmitter is called: a static token, then OAuth2 client
     * credentials, then {@code quarkus-oidc-client} if present, else without a token.
     *
     * @param oidcProviders creates OIDC client backed providers, {@code null} without
     *        {@code quarkus-oidc-client}
     */
    public static SsfTransmitterTokenProvider tokenProvider(String name, SsfTransmitterConfig config,
            SsfHttpClient httpClient, OidcTransmitterTokenProviders oidcProviders) {
        SsfTransmitterTokenProvider provider = chooseTokenProvider(name, config, httpClient, oidcProviders);
        LOG.infof("SSF transmitter %s: calls are authenticated with %s", describe(name, issuer(name, config)),
                provider);
        return provider;
    }

    private static SsfTransmitterTokenProvider chooseTokenProvider(String name, SsfTransmitterConfig config,
            SsfHttpClient httpClient, OidcTransmitterTokenProviders oidcProviders) {
        if (config.transmitterAccessToken().filter(token -> !token.isBlank()).isPresent()) {
            return new StaticTransmitterTokenProvider(config.transmitterAccessToken().get());
        }
        SsfTransmitterConfig.Oauth2 oauth2 = config.oauth2();
        if (oauth2.tokenEndpoint().isPresent()) {
            String clientId = oauth2.clientId().filter(s -> !s.isBlank()).orElse(null);
            String clientSecret = oauth2.clientSecret().filter(s -> !s.isBlank()).orElse(null);
            if (clientId == null || clientSecret == null) {
                throw new ConfigurationException(prefix(name) + "oauth2.client-id and " + prefix(name)
                        + "oauth2.client-secret are required to obtain access tokens from " + prefix(name)
                        + "oauth2.token-endpoint");
            }
            ClientCredentialsSsfTransmitterTokenProvider provider = new ClientCredentialsSsfTransmitterTokenProvider(
                    httpClient, oauth2.tokenEndpoint().get(), clientId, clientSecret);
            provider.setScopes(oauth2.scopes().orElse(List.of()));
            provider.setAuthenticateWithRequestBody(
                    oauth2.clientAuthMethod() == SsfTransmitterConfig.ClientAuthMethod.POST);
            provider.setExpirySafetyWindow(oauth2.expirySafetyWindow());
            provider.setAdditionalParameters(oauth2.additionalParams());
            return provider;
        }
        if (oidcProviders != null) {
            return oidcProviders.create(name, config.oidc());
        }
        return NoopTransmitterTokenProvider.INSTANCE;
    }

    public static SsfStreamClient streamClient(SsfHttpClient httpClient, SsfTransmitterTokenProvider tokenProvider,
            SsfTransmitterMetadataResolver metadataResolver) {
        return new SsfStreamClient(httpClient, tokenProvider, metadataResolver);
    }

    public static SsfStreamVerification streamVerification(SsfReceiverStream receiverStream) {
        SsfStreamVerification verification = new SsfStreamVerification();
        verification.setStreamId(receiverStream::getStreamId);
        return verification;
    }

    /** @return the registrar, {@code null} if the stream is neither looked up nor managed */
    public static SsfStreamRegistrar streamRegistrar(String name, SsfTransmitterConfig config,
            SsfStreamClient streamClient, SsfReceiverStream receiverStream) {
        if (!registersStream(config)) {
            return null;
        }
        if (config.streamManagement() != SsfTransmitterConfig.StreamManagement.RECEIVER) {
            return SsfStreamRegistrar.forExistingStream(streamClient, receiverStream, config.streamId().get());
        }
        if (config.streamId().filter(id -> !id.isBlank()).isPresent()) {
            // the operator pinned the stream, there is nothing to create or update
            return SsfStreamRegistrar.forExistingStream(streamClient, receiverStream, config.streamId().get());
        }
        SsfStreamRegistrar registrar = SsfStreamRegistrar.forManagedStream(streamClient, receiverStream,
                desiredStream(name, config));
        registrar.setDeleteOnShutdown(config.receiverManaged().deleteOnShutdown());
        return registrar;
    }

    /**
     * The poller of a transmitter with POLL delivery, configured from {@code poll.*}:
     * interval, initial delay, batch size, rate limit handling, long polling, the
     * acknowledgement store and a virtual thread named {@code ssf-poller-<name>} for
     * {@link SsfPoller#start()}.
     *
     * @param ackStore where the acknowledgements wait for the next request, {@code null}
     *        for the in-memory store of the poller
     * @return the poller, {@code null} with PUSH delivery
     */
    public static SsfPoller poller(String name, SsfTransmitterConfig config, SsfHttpClient httpClient,
            SsfTransmitterTokenProvider tokenProvider, SsfSetProcessor processor, SsfReceiverStream receiverStream,
            SsfReceiverMetrics metrics, SsfPollAckStore ackStore) {
        if (config.deliveryMethod() != SsfTransmitterConfig.DeliveryMethod.POLL) {
            return null;
        }
        SsfTransmitterConfig.Poll poll = config.poll();
        SsfPoller poller = new SsfPoller(httpClient, tokenProvider, pollEndpoint(name, config, receiverStream),
                processor);
        poller.setTransmitter(issuer(name, config));
        poller.setMetrics((metrics != null) ? metrics : SsfReceiverMetrics.NOOP);
        if (ackStore != null) {
            poller.setAckStore(ackStore);
        }
        poller.setInterval(poll.interval());
        poller.setInitialDelay(poll.startDelay());
        poller.setMaxEvents(poll.maxEvents());
        poller.setRateLimitFallback(poll.rateLimit().fallbackBackoff().orElse(null));
        poller.setMaxPause(poll.rateLimit().maxBackoff());
        if (poll.longPolling()) {
            poller.setLongPolling(poll.longPollingHold());
        }
        poller.setThreadFactory(Thread.ofVirtual().name("ssf-poller-" + name).factory());
        return poller;
    }

    /**
     * A builder with every part of the transmitter built from its configuration.
     *
     * @param ackStore where the acknowledgements of the poller wait, {@code null} for the
     *        in-memory store of the poller
     */
    public static SsfTransmitter.Builder builder(String name, SsfTransmitterConfig config, SsfHttpClient httpClient,
            SsfSetProcessor processor, SsfReceiverMetrics metrics, OidcTransmitterTokenProviders oidcProviders,
            SsfPollAckStore ackStore) {
        SsfReceiverStream receiverStream = new SsfReceiverStream();
        SsfTransmitterMetadataResolver metadataResolver = metadataResolver(name, config, httpClient);
        SsfTransmitterTokenProvider tokenProvider = tokenProvider(name, config, httpClient, oidcProviders);
        SsfStreamClient streamClient = streamClient(httpClient, tokenProvider, metadataResolver);
        return SsfTransmitter.builder(name, issuer(name, config))
                .metadataResolver(metadataResolver)
                .verifier(verifier(name, config, httpClient, metadataResolver, receiverStream))
                .tokenProvider(tokenProvider)
                .streamClient(streamClient)
                .receiverStream(receiverStream)
                .streamVerification(streamVerification(receiverStream))
                .streamRegistrar(streamRegistrar(name, config, streamClient, receiverStream))
                // the SsfPollScheduler starts and stops the poller with the application, after the
                // registrars, so that poll.auto-start is honoured in one place
                .poller(poller(name, config, httpClient, tokenProvider, processor, receiverStream, metrics, ackStore),
                        false)
                .pushAuthorizationHeader(config.push().expectedAuthHeader().orElse(null));
    }

    /** Whether the stream is looked up or managed on startup. */
    public static boolean registersStream(SsfTransmitterConfig config) {
        return config.streamManagement() == SsfTransmitterConfig.StreamManagement.RECEIVER
                || config.streamId().filter(id -> !id.isBlank()).isPresent();
    }

    private static SsfStreamConfiguration desiredStream(String name, SsfTransmitterConfig config) {
        List<String> eventsRequested = config.eventsRequested().orElse(List.of());
        if (eventsRequested.isEmpty()) {
            throw new ConfigurationException(prefix(name)
                    + "events-requested: a stream managed by the receiver has to request at least one event type");
        }
        String description = config.receiverManaged().description().orElse(null);
        if (config.deliveryMethod() == SsfTransmitterConfig.DeliveryMethod.POLL) {
            return SsfStreamConfiguration.poll(eventsRequested, description);
        }
        URI deliveryEndpointUrl = config.push().deliveryEndpointUrl()
                .orElseThrow(() -> new ConfigurationException(prefix(name)
                        + "push.delivery-endpoint-url: a stream managed by the receiver needs the URL under which "
                        + "the transmitter reaches the push endpoint of this application"));
        return SsfStreamConfiguration.push(deliveryEndpointUrl,
                config.push().expectedAuthHeader().filter(header -> !header.isBlank()).orElse(null),
                eventsRequested, description);
    }

    private static Supplier<URI> pollEndpoint(String name, SsfTransmitterConfig config,
            SsfReceiverStream receiverStream) {
        URI configured = config.poll().endpointUrl().orElse(null);
        if (configured != null) {
            return () -> configured;
        }
        if (!registersStream(config)) {
            throw new ConfigurationException(prefix(name) + "poll.endpoint-url: POLL delivery needs the poll "
                    + "endpoint of the stream; set " + prefix(name) + "poll.endpoint-url, " + prefix(name)
                    + "stream-id or " + prefix(name) + "stream-management=RECEIVER");
        }
        return () -> receiverStream.getConfiguration()
                .filter(stream -> SsfDeliveryMethod.POLL.uri().equals(stream.deliveryMethod()))
                .map(SsfStreamConfiguration::deliveryEndpointUrl)
                .orElse(null);
    }

    private static Supplier<String> jwkSetUri(String name, SsfTransmitterConfig config,
            SsfTransmitterMetadataResolver metadataResolver) {
        URI configured = config.transmitterJwksUrl().orElse(null);
        if (configured != null) {
            try {
                uriPolicy(config).checkEndpoint(configured, "The JWK Set URL");
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException(prefix(name) + "transmitter-jwks-url: " + e.getMessage(), e);
            }
            return configured::toString;
        }
        return () -> {
            URI discovered = metadataResolver.resolve().jwksUri();
            if (discovered == null) {
                throw new IllegalStateException("The SSF transmitter metadata has no jwks_uri, configure "
                        + prefix(name) + "transmitter-jwks-url");
            }
            return discovered.toString();
        };
    }

    static String describe(String name, String issuer) {
        return SsfTransmitter.DEFAULT_NAME.equals(name) ? issuer : "'" + name + "' (" + issuer + ")";
    }
}
