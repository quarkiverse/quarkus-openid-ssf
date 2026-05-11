package io.quarkiverse.ssf.receiver.deployment;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.quarkiverse.ssf.receiver.runtime.auth.Oauth2TransmitterTokenProvider;
import io.quarkiverse.ssf.receiver.runtime.auth.TransmitterTokenProvider;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Layer-2 test: same provider as {@link Oauth2TransmitterTokenProviderTest},
 * but with {@code client_secret_basic} (RFC 6749 §2.3.1) — credentials in the
 * {@code Authorization} header, NOT in the form body. Verifies the header is
 * the expected base64'd value and that {@code client_id} / {@code client_secret}
 * don't leak into the body (which a few strict servers reject as a duplicated
 * credential).
 */
public class Oauth2TransmitterTokenProviderBasicTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(TokenEndpointWireMock.class))
            .setBeforeAllCustomizer(TokenEndpointWireMock::start)
            .setAfterAllCustomizer(TokenEndpointWireMock::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.enabled", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer", "https://disabled.example")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.token-endpoint",
                    "${" + TokenEndpointWireMock.PROP_TOKEN_URL + "}")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-id", "basic-client")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-secret", "basic-secret")
            // Default is BASIC; explicit for readability.
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-auth-method", "basic");

    @Inject
    TransmitterTokenProvider provider;

    @BeforeEach
    void reset() {
        wm().resetRequests();
        ((Oauth2TransmitterTokenProvider) provider).clearCacheForTest();
    }

    @Test
    @DisplayName("Authorization: Basic header is set and credentials are NOT duplicated into the form body")
    void basicHeaderUsedAndBodyOmitsCredentials() {
        Optional<String> token = provider.accessToken();
        assertTrue(token.isPresent());

        // base64("basic-client:basic-secret") — neither value contains chars
        // that change under URL encoding, so the encoded userinfo is the
        // same as the raw form.
        wm().verifyThat(postRequestedFor(urlEqualTo(TokenEndpointWireMock.TOKEN_PATH))
                .withHeader("Authorization",
                        equalTo("Basic YmFzaWMtY2xpZW50OmJhc2ljLXNlY3JldA==")));

        List<LoggedRequest> reqs = wm().find(postRequestedFor(urlEqualTo(TokenEndpointWireMock.TOKEN_PATH)));
        assertEquals(1, reqs.size());
        Map<String, String> form = parseForm(reqs.get(0).getBodyAsString());
        assertEquals("client_credentials", form.get("grant_type"));
        assertThat("client_id must not duplicate into the body when using BASIC",
                form.get("client_id"), is(nullValue()));
        assertThat("client_secret must not duplicate into the body when using BASIC",
                form.get("client_secret"), is(nullValue()));
    }

    private static WireMock wm() {
        int port = Integer.parseInt(System.getProperty(TokenEndpointWireMock.PROP_TOKEN_PORT));
        return new WireMock("localhost", port);
    }

    private static Map<String, String> parseForm(String body) {
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
