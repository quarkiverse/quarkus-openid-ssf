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
package com.easyssf.quarkus.ssfreceiver.runtime.stream.subjects;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * MicroProfile REST Client view of the SSF transmitter's {@code remove_subject_endpoint}
 * (spec §8.1.3.3). Bound to a runtime {@code baseUri} via {@code RestClientBuilder}.
 */
@Path("/")
public interface SsfTransmitterStreamRemoveSubjectApi {

    /**
     * Removes the subject from the stream. Spec response is {@code 204 No Content}.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    void removeSubject(RemoveSubjectRequest request);
}
