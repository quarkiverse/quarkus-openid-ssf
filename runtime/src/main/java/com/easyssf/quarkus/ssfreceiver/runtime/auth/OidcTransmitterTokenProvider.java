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
package com.easyssf.quarkus.ssfreceiver.runtime.auth;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.easyssf.quarkus.ssfreceiver.runtime.SsfReceiverConfig;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;

/**
 * Resolves transmitter access tokens via Quarkus {@link OidcClient}. Only registered
 * by {@code SsfReceiverProcessor} when {@code quarkus-oidc-client} is on the consumer's
 * classpath, so this class is never loaded otherwise.
 *
 * <p>
 * The OidcClient itself is configured by the consumer via {@code quarkus.oidc-client.*}
 * properties (auth-server-url, client-id, credentials, grant type, …). We simply consume
 * whatever access token it produces.
 */
@ApplicationScoped
public class OidcTransmitterTokenProvider implements TransmitterTokenProvider {

    private static final Logger LOG = Logger.getLogger(OidcTransmitterTokenProvider.class);

    @Inject
    OidcClient oidcClient;

    @Inject
    SsfReceiverConfig config;

    @Override
    public Optional<String> accessToken() {
        try {
            Tokens tokens = oidcClient.getTokens().await().atMost(config.oidcClientTokenTimeout());
            if (tokens == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(tokens.getAccessToken());
        } catch (RuntimeException e) {
            LOG.warnf("Failed to obtain transmitter access token via OidcClient: %s", summarize(e));
            LOG.debugf(e, "OidcClient token fetch — full stack");
            return Optional.empty();
        }
    }

    /**
     * One-line summary of an exception. Some auth-server failure modes (e.g.
     * proxy / load-balancer returning an HTML error page) put the entire body
     * in {@code getMessage()}; this trims to the first line and caps length.
     */
    private static String summarize(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        int newline = msg.indexOf('\n');
        if (newline >= 0) {
            msg = msg.substring(0, newline);
        }
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "…";
        }
        return t.getClass().getSimpleName() + ": " + msg.trim();
    }
}
