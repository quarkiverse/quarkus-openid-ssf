package io.quarkiverse.ssf.receiver.deployment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.quarkiverse.ssf.receiver.runtime.auth.Oauth2TransmitterTokenProvider;
import io.quarkiverse.ssf.receiver.runtime.auth.TransmitterTokenProvider;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Layer-2 test for {@link Oauth2TransmitterTokenProvider} with the
 * {@code client_secret_post} auth method (credentials in the form body).
 * Drives the provider against a WireMock OAuth2 token endpoint and asserts:
 * the build-time selection picks the OAuth2 provider, the form body has the
 * right shape, the cache reuses tokens within the validity window, expiry +
 * safety window forces a refresh, and 4xx responses surface as
 * {@link IllegalStateException} naming the HTTP code.
 *
 * <p>
 * BASIC auth lives in a sibling test ({@code Oauth2TransmitterTokenProviderBasicTest})
 * — each {@link QuarkusExtensionTest} only runs one Quarkus boot, so the two
 * auth modes need separate test classes.
 */
public class Oauth2TransmitterTokenProviderTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(TokenEndpointWireMock.class))
            .setBeforeAllCustomizer(TokenEndpointWireMock::start)
            .setAfterAllCustomizer(TokenEndpointWireMock::stop)
            // Disable the receiver itself — we only want the auth wiring.
            .overrideConfigKey("quarkus.openid-ssf.receiver.enabled", "false")
            // Required by the config mapping even when disabled.
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer", "https://disabled.example")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.token-endpoint",
                    "${" + TokenEndpointWireMock.PROP_TOKEN_URL + "}")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-id", "post-client")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-secret", "post-secret/with+special&chars")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-auth-method", "post")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.scopes", "ssf.read,ssf.manage")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.expiry-safety-window", "5s");

    @Inject
    TransmitterTokenProvider provider;

    @BeforeEach
    void resetWireMockHistoryAndCache() {
        wm().resetRequests();
        // Drop the provider's cache so each test starts from a known
        // "needs a fresh fetch" state.
        ((Oauth2TransmitterTokenProvider) provider).clearCacheForTest();
    }

    @Test
    @DisplayName("Build-time selection wires Oauth2TransmitterTokenProvider when oauth2.token-endpoint is set")
    void providerIsOauth2Backed() {
        // CDI proxies wrap the bean; check the underlying class name via toString.
        assertThat(provider.toString(), containsString("Oauth2TransmitterTokenProvider"));
    }

    @Test
    @DisplayName("Form body carries grant_type, client_id, client_secret, scope; no audience leaks in")
    void formBodyShape() throws Exception {
        Optional<String> token = provider.accessToken();
        assertTrue(token.isPresent());
        assertEquals("initial-token-AAA", token.get());

        List<LoggedRequest> reqs = wm().find(postRequestedFor(urlEqualTo(TokenEndpointWireMock.TOKEN_PATH)));
        assertEquals(1, reqs.size(), "exactly one token-endpoint POST");
        LoggedRequest req = reqs.get(0);

        assertThat(req.getHeader("Content-Type"),
                equalToIgnoringCase("application/x-www-form-urlencoded"));
        assertThat("BASIC auth header must NOT be present in POST mode",
                req.getHeader("Authorization"), is(nullValue()));

        Map<String, String> form = parseForm(req.getBodyAsString());
        assertEquals("client_credentials", form.get("grant_type"));
        assertEquals("post-client", form.get("client_id"));
        // The secret round-trips through URL encoding correctly.
        assertEquals("post-secret/with+special&chars", form.get("client_secret"));
        assertEquals("ssf.read ssf.manage", form.get("scope"));
        // Audience is intentionally NOT a request parameter — reserved for
        // future token-validation logic.
        assertThat("audience must not be sent on the token request",
                form.get("audience"), is(nullValue()));
    }

    @Test
    @DisplayName("Subsequent calls within the validity window reuse the cached token (no second POST)")
    void cacheHit() {
        provider.accessToken();
        provider.accessToken();
        provider.accessToken();
        int posts = wm().find(postRequestedFor(urlEqualTo(TokenEndpointWireMock.TOKEN_PATH))).size();
        assertEquals(1, posts, "cached token should be reused");
    }

    @Test
    @DisplayName("Refresh after expiry: short-lived token forces a second POST")
    void refreshAfterExpiry() {
        // Override default stub: token expires_in=1, safety-window=5s →
        // every call should be considered "stale" and re-fetch.
        StubMapping shortLived = wm().register(post(urlEqualTo(TokenEndpointWireMock.TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"short-lived\",\"expires_in\":1}")));
        try {
            provider.accessToken();
            provider.accessToken();
            int posts = wm().find(postRequestedFor(urlEqualTo(TokenEndpointWireMock.TOKEN_PATH))).size();
            assertEquals(2, posts, "expiry-safety-window dwarfs expires_in → both calls fetch");
        } finally {
            wm().removeStubMapping(shortLived);
        }
    }

    @Test
    @DisplayName("Token endpoint 401 → IllegalStateException naming the HTTP code")
    void tokenEndpointError() {
        StubMapping unauthorised = wm().register(post(urlEqualTo(TokenEndpointWireMock.TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("{\"error\":\"invalid_client\"}")));
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> provider.accessToken());
            assertThat(ex.getMessage(), containsString("401"));
            assertThat(ex.getMessage(), containsString("invalid_client"));
        } finally {
            wm().removeStubMapping(unauthorised);
        }
    }

    private static WireMock wm() {
        int port = Integer.parseInt(System.getProperty(TokenEndpointWireMock.PROP_TOKEN_PORT));
        return new WireMock("localhost", port);
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return map;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String val = eq < 0 ? "" : pair.substring(eq + 1);
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(val, StandardCharsets.UTF_8));
        }
        return map;
    }
}
