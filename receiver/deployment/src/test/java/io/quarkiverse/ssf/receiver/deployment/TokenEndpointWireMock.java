package io.quarkiverse.ssf.receiver.deployment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Minimal WireMock that stands in for an OAuth2 authorization server's token
 * endpoint — used by {@code Oauth2TransmitterTokenProviderTest}. Bridges the
 * dynamic port + endpoint URL via system properties so the test runs in the
 * Quarkus classloader can reach back via the WireMock HTTP admin API.
 */
final class TokenEndpointWireMock {

    static final String PROP_TOKEN_PORT = "test.ssf.token-port";
    static final String PROP_TOKEN_URL = "test.ssf.token-url";

    static final String TOKEN_PATH = "/oauth/token";

    private static WireMockServer server;

    private TokenEndpointWireMock() {
    }

    /**
     * Starts the wiremock with one default {@code POST /oauth/token} stub that
     * returns a valid {@code access_token} + {@code expires_in}. Tests can
     * register additional / overriding stubs via the WireMock admin API.
     */
    static void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        server.stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"initial-token-AAA\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        System.setProperty(PROP_TOKEN_PORT, Integer.toString(server.port()));
        System.setProperty(PROP_TOKEN_URL, server.baseUrl() + TOKEN_PATH);
    }

    static void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        System.clearProperty(PROP_TOKEN_PORT);
        System.clearProperty(PROP_TOKEN_URL);
    }
}
