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
package com.easyssf.quarkus.ssfreceiver.deployment;

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
