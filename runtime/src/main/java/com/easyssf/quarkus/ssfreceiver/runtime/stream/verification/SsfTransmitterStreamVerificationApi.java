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
package com.easyssf.quarkus.ssfreceiver.runtime.stream.verification;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * MicroProfile REST Client view of the SSF transmitter's {@code verification_endpoint}
 * (spec §8.1.4.2 — trigger a Verification Event).
 *
 * <p>
 * Bound to a runtime {@code baseUri} via {@code RestClientBuilder} because the
 * endpoint URL is discovered from the transmitter metadata document.
 */
@Path("/")
public interface SsfTransmitterStreamVerificationApi {

    /**
     * POSTs a verification request and expects a {@code 204 No Content} success.
     * Non-2xx responses surface as {@code jakarta.ws.rs.WebApplicationException}.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    void requestVerification(StreamVerificationRequest request);
}
