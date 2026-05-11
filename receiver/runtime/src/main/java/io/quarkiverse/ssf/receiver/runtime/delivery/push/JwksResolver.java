package io.quarkiverse.ssf.receiver.runtime.delivery.push;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.metadata.SsfConfigurationResolver;
import io.quarkiverse.ssf.receiver.runtime.metadata.SsfMetadataException;

/**
 * Thin adapter over Nimbus's {@link JWKSource} for the SSF transmitter's JWKS.
 * Delegates caching (5-minute TTL) and refresh-on-{@code kid}-miss to
 * {@code nimbus-jose-jwt}; this class only resolves the JWKS URL — explicit
 * {@code transmitter-jwks-url} override or the {@code jwks_uri} discovered
 * from the SSF metadata document.
 *
 * <p>
 * JWKS endpoints are unauthenticated by SSF/OIDC convention, so we use
 * Nimbus's default HTTP retriever (with explicit timeouts) — no
 * {@code Authorization} header is sent.
 */
@ApplicationScoped
public class JwksResolver {

    private static final Logger LOG = Logger.getLogger(JwksResolver.class);

    private static final long CACHE_TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final long CACHE_REFRESH_TIMEOUT_MS = Duration.ofSeconds(15).toMillis();
    private static final int HTTP_CONNECT_TIMEOUT_MS = (int) Duration.ofSeconds(10).toMillis();
    private static final int HTTP_READ_TIMEOUT_MS = (int) Duration.ofSeconds(10).toMillis();
    /** 256 KB upper bound on a JWKS document — generous; real-world JWKS are < 10 KB. */
    private static final int HTTP_SIZE_LIMIT = 256 * 1024;

    @Inject
    SsfReceiverConfig config;

    @Inject
    SsfConfigurationResolver metadata;

    private volatile JWKSource<SecurityContext> jwkSource;

    /**
     * Returns the JWK matching {@code kid}, or {@code null} if the transmitter's
     * JWKS doesn't carry one. Nimbus refreshes the cache once on a miss before
     * giving up, so a freshly-rotated key is picked up without restart.
     */
    public JWK find(String kid) throws SsfVerificationException {
        JWKSource<SecurityContext> source = source();
        JWKSelector selector = new JWKSelector(new JWKMatcher.Builder().keyID(kid).build());
        try {
            List<JWK> matches = source.get(selector, null);
            return matches.isEmpty() ? null : matches.getFirst();
        } catch (KeySourceException e) {
            throw new SsfVerificationException("JWKS lookup failed for kid " + kid, e);
        }
    }

    private synchronized JWKSource<SecurityContext> source() throws SsfVerificationException {
        if (jwkSource == null) {
            URI uri = jwksUri();
            try {
                DefaultResourceRetriever retriever = new DefaultResourceRetriever(
                        HTTP_CONNECT_TIMEOUT_MS, HTTP_READ_TIMEOUT_MS, HTTP_SIZE_LIMIT);
                jwkSource = JWKSourceBuilder
                        .create(uri.toURL(), retriever)
                        .cache(CACHE_TTL_MS, CACHE_REFRESH_TIMEOUT_MS)
                        .retrying(true)
                        .build();
                LOG.debugf("Initialised JWKS source for %s", uri);
            } catch (MalformedURLException e) {
                throw new SsfVerificationException("Invalid jwks_uri: " + uri, e);
            }
        }
        return jwkSource;
    }

    private URI jwksUri() throws SsfVerificationException {
        if (config.transmitterJwksUrl().isPresent()) {
            return config.transmitterJwksUrl().get();
        }
        try {
            URI uri = metadata.get().jwksUri();
            if (uri == null) {
                throw new SsfVerificationException("SSF transmitter metadata is missing jwks_uri");
            }
            return uri;
        } catch (SsfMetadataException e) {
            throw new SsfVerificationException("Could not resolve jwks_uri from transmitter metadata", e);
        }
    }
}
