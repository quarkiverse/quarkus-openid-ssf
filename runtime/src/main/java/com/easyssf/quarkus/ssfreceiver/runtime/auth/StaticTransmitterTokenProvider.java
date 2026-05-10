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

import com.easyssf.quarkus.ssfreceiver.runtime.SsfReceiverConfig;

/**
 * Returns a fixed bearer token configured via
 * {@code ssf.receiver.transmitter-access-token}. Registered by
 * {@code SsfReceiverProcessor} only when that property is set; in that case it
 * also displaces {@link OidcTransmitterTokenProvider} so there's no CDI
 * ambiguity over which provider supplies the outbound token.
 *
 * <p>
 * Intended for transmitters that issue long-lived bearer tokens out-of-band
 * (e.g. <a href="https://ssf.caep.dev">caep.dev</a>) rather than expecting an
 * OAuth grant.
 */
@ApplicationScoped
public class StaticTransmitterTokenProvider implements TransmitterTokenProvider {

    @Inject
    SsfReceiverConfig config;

    @Override
    public Optional<String> accessToken() {
        return config.transmitterAccessToken().filter(t -> !t.isBlank());
    }
}
