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

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * REST Client filter that attaches {@code Authorization: Bearer <token>} from the
 * configured {@link TransmitterTokenProvider}. When OIDC is wired up via
 * {@code quarkus-oidc-client}, this is a fresh access token from the
 * client_credentials grant; otherwise the no-op provider yields no header.
 */
public final class TransmitterAuthClientRequestFilter implements ClientRequestFilter {

    private final TransmitterTokenProvider tokenProvider;

    public TransmitterAuthClientRequestFilter(TransmitterTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        tokenProvider.accessToken()
                .ifPresent(token -> requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
