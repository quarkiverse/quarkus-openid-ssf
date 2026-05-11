package io.quarkiverse.ssf.receiver.runtime.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;

/**
 * Self-contained OAuth2 {@code client_credentials} grant — fetches a token
 * from {@code quarkus.openid-ssf.receiver.oauth2.token-endpoint} with HTTP Basic /
 * form-encoded credentials, caches it, and refreshes before expiry. Used
 * when the consumer doesn't want to pull in {@code quarkus-oidc-client} for
 * outbound transmitter auth.
 *
 * <p>
 * Registered by {@code SsfReceiverProcessor} only when {@code oauth2.token-endpoint}
 * is set <em>and</em> a {@code transmitter-access-token} isn't (which would
 * win and select {@code StaticTransmitterTokenProvider} instead). When the OIDC
 * client is also on the classpath, this provider takes precedence — consumers
 * who want OIDC simply leave {@code oauth2.token-endpoint} unset.
 *
 * <p>
 * Thread safety: the cached snapshot is held in an {@link AtomicReference};
 * a {@code synchronized} guard around the refresh path coalesces concurrent
 * misses so a token-refresh storm reduces to one POST.
 */
@ApplicationScoped
public class Oauth2TransmitterTokenProvider implements TransmitterTokenProvider {

    private static final Logger LOG = Logger.getLogger(Oauth2TransmitterTokenProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    SsfReceiverConfig config;

    private HttpClient httpClient;
    private URI tokenEndpoint;
    private String grantType;
    private SsfReceiverConfig.ClientAuthMethod clientAuthMethod;
    private String clientId;
    private String clientSecret;
    private String scope;
    private Map<String, String> additionalParams;
    private Duration safetyWindow;
    private Duration timeout;

    private final AtomicReference<TokenSnapshot> cached = new AtomicReference<>();

    @PostConstruct
    void init() {
        SsfReceiverConfig.Oauth2 oauth2 = config.oauth2();
        // tokenEndpoint presence is checked at build time before this bean
        // is registered; rely on that and fail fast if it's somehow missing.
        this.tokenEndpoint = oauth2.tokenEndpoint().orElseThrow(() -> new IllegalStateException(
                "quarkus.openid-ssf.receiver.oauth2.token-endpoint is required when Oauth2TransmitterTokenProvider is active"));
        this.grantType = oauth2.grantType();
        this.clientAuthMethod = oauth2.clientAuthMethod();
        this.clientId = oauth2.clientId().filter(s -> !s.isBlank()).orElse(null);
        this.clientSecret = oauth2.clientSecret().filter(s -> !s.isBlank()).orElse(null);

        if (clientId == null) {
            throw new IllegalStateException(
                    "quarkus.openid-ssf.receiver.oauth2.client-id is required when quarkus.openid-ssf.receiver.oauth2.token-endpoint is set");
        }
        if (clientSecret == null) {
            throw new IllegalStateException(
                    "quarkus.openid-ssf.receiver.oauth2.client-secret is required when quarkus.openid-ssf.receiver.oauth2.token-endpoint is set");
        }

        this.scope = oauth2.scopes()
                .filter(list -> !list.isEmpty())
                .map(list -> String.join(" ", list))
                .orElse(null);
        this.additionalParams = oauth2.additionalParams() == null
                ? Map.of()
                : Map.copyOf(oauth2.additionalParams());
        this.safetyWindow = oauth2.expirySafetyWindow();
        this.timeout = oauth2.timeout();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        LOG.infof(
                "Oauth2TransmitterTokenProvider configured: token-endpoint=%s grant_type=%s client_auth_method=%s client_id=%s scope=%s expirySafetyWindow=%s",
                tokenEndpoint,
                grantType,
                clientAuthMethod.name().toLowerCase(),
                clientId,
                scope != null ? scope : "(none)",
                safetyWindow);
    }

    @Override
    public Optional<String> accessToken() {
        TokenSnapshot snap = cached.get();
        if (snap != null && snap.isStillValid(Instant.now(), safetyWindow)) {
            return Optional.of(snap.token());
        }
        return Optional.of(refresh());
    }

    /**
     * Synchronized so a burst of "stale token, refresh!" callers coalesces
     * into a single token-endpoint round trip. The double-check inside
     * lets the second-and-later waiters see the freshly cached snapshot
     * without making their own request.
     */
    private synchronized String refresh() {
        TokenSnapshot snap = cached.get();
        Instant now = Instant.now();
        if (snap != null && snap.isStillValid(now, safetyWindow)) {
            return snap.token();
        }

        TokenSnapshot fresh;
        try {
            fresh = fetch(now);
        } catch (RuntimeException e) {
            // Don't poison the cache on transient failure — leave whatever's
            // there (even an expired snapshot) and let the caller decide.
            // Re-throw so the outbound REST client sees the failure.
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(
                    "OAuth2 token fetch from " + tokenEndpoint + " failed: " + e.getMessage(), e);
        }
        cached.set(fresh);
        return fresh.token();
    }

    private TokenSnapshot fetch(Instant requestStart) throws IOException, InterruptedException {
        String body = formEncodedBody();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (clientAuthMethod == SsfReceiverConfig.ClientAuthMethod.BASIC) {
            // RFC 6749 §2.3.1: client_id and client_secret are form-urlencoded
            // BEFORE being joined with ':' and base64'd. Some servers reject
            // the alternative.
            String userInfo = URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + ":"
                    + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
            String basicAuthHeader = "Basic " + Base64.getEncoder()
                    .encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
            requestBuilder.header("Authorization", basicAuthHeader);
        }
        HttpRequest request = requestBuilder.build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "OAuth2 token endpoint " + tokenEndpoint + " returned HTTP " + response.statusCode()
                            + ": " + truncate(response.body(), 500));
        }

        JsonNode root = JSON.readTree(response.body());
        JsonNode accessNode = root.get("access_token");
        if (accessNode == null || accessNode.asText().isEmpty()) {
            throw new IllegalStateException(
                    "OAuth2 token endpoint " + tokenEndpoint + " returned no access_token: "
                            + truncate(response.body(), 500));
        }

        // expires_in is in seconds; default to 60s if the transmitter omits it
        // — at-worst we re-fetch every minute, no correctness harm.
        long expiresInSec = root.has("expires_in") ? root.get("expires_in").asLong(60L) : 60L;
        if (expiresInSec <= 0) {
            expiresInSec = 60L;
        }
        Instant expiresAt = requestStart.plusSeconds(expiresInSec);
        LOG.debugf("OAuth2 token refreshed; expires_in=%ds (cached until %s)", expiresInSec, expiresAt);
        return new TokenSnapshot(accessNode.asText(), expiresAt);
    }

    private String formEncodedBody() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", grantType);
        // For client_secret_post, both go in the body. For client_secret_basic
        // they're in the Authorization header — skip here so we don't send
        // them twice (which a few strict servers reject).
        if (clientAuthMethod == SsfReceiverConfig.ClientAuthMethod.POST) {
            params.put("client_id", clientId);
            params.put("client_secret", clientSecret);
        }
        if (scope != null) {
            params.put("scope", scope);
        }
        // additionalParams come last so they can override (rare but a deliberate
        // escape hatch for non-standard servers).
        params.putAll(additionalParams);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "…";
    }

    /** Test-only hook for unit tests that exercise the cache/refresh path. */
    public void clearCacheForTest() {
        cached.set(null);
    }

    /**
     * Snapshot of a fetched token + its expiry. {@code List<String>} of fields,
     * not lifecycle — this is a value record.
     */
    record TokenSnapshot(String token, Instant expiresAt) {
        boolean isStillValid(Instant now, Duration safetyWindow) {
            return now.plus(safetyWindow).isBefore(expiresAt);
        }
    }

    /** Test-only utility for asserting on cache contents in unit tests. */
    public Optional<TokenSnapshot> snapshotForTest() {
        return Optional.ofNullable(cached.get());
    }
}
