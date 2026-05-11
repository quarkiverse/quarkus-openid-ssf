package io.quarkiverse.ssf.receiver.runtime.metadata;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkus.runtime.StartupEvent;

/**
 * Lazy, cached fetcher for the SSF transmitter's {@code .well-known/ssf-configuration}
 * document (SSF spec §7.1). Resolves to a typed {@link SsfTransmitterMetadata} so
 * consumers don't have to navigate raw JSON. Used for {@code jwks_uri} discovery and
 * for resolving the various SSF management endpoints
 * ({@code status_endpoint}, {@code add_subject_endpoint}, …).
 *
 * <p>
 * The metadata document is unauthenticated by SSF/OIDC convention, so the
 * outbound {@link SsfTransmitterMetadataApi} call does NOT attach an {@code Authorization}
 * header — sending one would surface CORS / proxy issues on transmitters that
 * reject unexpected auth on public endpoints.
 */
@ApplicationScoped
public class SsfConfigurationResolver {

    private static final Logger LOG = Logger.getLogger(SsfConfigurationResolver.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final long FETCH_TIMEOUT_MS = Duration.ofSeconds(10).toMillis();

    @Inject
    SsfReceiverConfig config;

    private volatile SsfTransmitterMetadata cached;
    private volatile Instant cachedAt;

    /**
     * Logs the resolved SSF metadata URL at INFO once at boot — useful for
     * confirming whether auto-derivation produced the URL the operator
     * expected — and then warms the metadata cache so the first PUSH /
     * POLL doesn't trip a blocking REST call on the event-loop thread.
     *
     * <p>
     * Runs at priority 50 so it appears in the log before the validator
     * (100) / registrar / probe / poller. Warm-up failures are best-effort:
     * we log WARN and let the lazy fetch retry on the first real request.
     * Failing startup here would conflate "transmitter is down right now"
     * with "we can't run at all", which would defeat the receiver's own
     * resilience story.
     */
    void onStart(@Observes @Priority(50) StartupEvent event) {
        if (!config.enabled()) {
            return;
        }
        boolean explicit = config.transmitterMetadataUrl().isPresent();
        URI uri = config.transmitterMetadataUrl().orElseGet(this::defaultMetadataUri);
        LOG.infof("SSF transmitter metadata URL: %s (%s)",
                uri,
                explicit
                        ? "from quarkus.openid-ssf.receiver.transmitter-metadata-url"
                        : "derived from issuer");
        try {
            get();
            LOG.debugf("SSF transmitter metadata cache warmed from %s", uri);
        } catch (SsfMetadataException e) {
            LOG.warnf("Could not warm SSF transmitter metadata cache from %s — "
                    + "first PUSH/POLL will retry the fetch. Cause: %s",
                    uri, e.getMessage());
        }
    }

    public synchronized SsfTransmitterMetadata get() {
        if (cached == null || cachedAt == null
                || Duration.between(cachedAt, Instant.now()).compareTo(TTL) > 0) {
            cached = fetch();
            cachedAt = Instant.now();
        }
        return cached;
    }

    private SsfTransmitterMetadata fetch() {
        URI metadataUri = config.transmitterMetadataUrl().orElseGet(this::defaultMetadataUri);
        LOG.debugf("Fetching SSF transmitter metadata from %s", metadataUri);

        SsfTransmitterMetadataApi api = RestClientBuilder.newBuilder()
                .baseUri(metadataUri)
                .connectTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build(SsfTransmitterMetadataApi.class);

        try {
            Map<String, Object> raw = api.get();
            if (raw == null) {
                throw new SsfMetadataException("SSF metadata response was empty from " + metadataUri);
            }
            return parse(raw);
        } catch (SsfMetadataException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SsfMetadataException("SSF metadata fetch failed from " + metadataUri, e);
        }
    }

    private static final Set<String> KNOWN_KEYS = Set.of(
            "spec_version",
            "issuer",
            "configuration_endpoint",
            "jwks_uri",
            "status_endpoint",
            "add_subject_endpoint",
            "remove_subject_endpoint",
            "verification_endpoint",
            "delivery_methods_supported",
            "authorization_schemes",
            "critical_subject_members",
            "default_subjects");

    private static SsfTransmitterMetadata parse(Map<String, Object> raw) {
        Map<String, Object> additional = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (!KNOWN_KEYS.contains(e.getKey())) {
                additional.put(e.getKey(), e.getValue());
            }
        }
        return new SsfTransmitterMetadata(
                stringOf(raw, "spec_version"),
                uriOf(raw, "issuer"),
                uriOf(raw, "configuration_endpoint"),
                uriOf(raw, "jwks_uri"),
                uriOf(raw, "status_endpoint"),
                uriOf(raw, "add_subject_endpoint"),
                uriOf(raw, "remove_subject_endpoint"),
                uriOf(raw, "verification_endpoint"),
                stringListOf(raw, "delivery_methods_supported"),
                stringListOf(raw, "authorization_schemes"),
                stringListOf(raw, "critical_subject_members"),
                objectListOf(raw, "default_subjects"),
                Collections.unmodifiableMap(additional));
    }

    private static String stringOf(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        return v instanceof String s ? s : null;
    }

    private static URI uriOf(Map<String, Object> raw, String key) {
        String s = stringOf(raw, key);
        return s == null || s.isBlank() ? null : URI.create(s);
    }

    private static List<String> stringListOf(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object e : list) {
                if (e instanceof String s) {
                    out.add(s);
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectListOf(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    out.add(Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) m)));
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    /**
     * Derives the {@code .well-known/ssf-configuration} URL from the configured
     * issuer, per SSF spec §7.2:
     * <blockquote>
     * Transmitters supporting Discovery MUST make a JSON document available at
     * the path formed by inserting the string {@code /.well-known/ssf-configuration}
     * into the Issuer between the host component and the path component, if any.
     * </blockquote>
     *
     * <p>
     * This is <em>different</em> from the OIDC convention, which appends to the
     * end of the issuer. Examples (verbatim from the spec):
     * <ul>
     * <li>{@code https://tr.example.com} → {@code https://tr.example.com/.well-known/ssf-configuration}</li>
     * <li>{@code https://tr.example.com/issuer1} → {@code https://tr.example.com/.well-known/ssf-configuration/issuer1}</li>
     * </ul>
     *
     * <p>
     * Some transmitters (notably ones that originated as OIDC providers) may
     * serve the document at the OIDC-style path instead — for those, set
     * {@code quarkus.openid-ssf.receiver.transmitter-metadata-url} explicitly.
     */
    private URI defaultMetadataUri() {
        return deriveMetadataUri(config.transmitterIssuer());
    }

    /**
     * Pure URI math — extracted from {@link #defaultMetadataUri()} so the
     * SSF §7.2 splice rule can be unit-tested without a Quarkus boot.
     * Package-private deliberately.
     *
     * @throws SsfMetadataException if {@code issuer} isn't an absolute URI with
     *         both a scheme and an authority.
     */
    static URI deriveMetadataUri(URI issuer) {
        if (issuer == null) {
            throw new SsfMetadataException("quarkus.openid-ssf.receiver.transmitter-issuer must not be null");
        }
        String scheme = issuer.getScheme();
        String authority = issuer.getRawAuthority();
        String path = issuer.getRawPath();

        if (scheme == null || authority == null) {
            throw new SsfMetadataException(
                    "quarkus.openid-ssf.receiver.transmitter-issuer must be an absolute https URI: " + issuer);
        }

        StringBuilder sb = new StringBuilder()
                .append(scheme).append("://").append(authority)
                .append("/.well-known/ssf-configuration");

        // Per spec: any terminating "/" on the issuer path MUST be removed before
        // splicing — and an empty / "/" path means we splice nothing.
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            // Issuer paths always start with "/" so this concatenates cleanly.
            sb.append(trimmed);
        }
        return URI.create(sb.toString());
    }

    /** Convenience accessor for the optional {@code status_endpoint}. */
    public Optional<URI> statusEndpoint() {
        return Optional.ofNullable(get().statusEndpoint());
    }
}
