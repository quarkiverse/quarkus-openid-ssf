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
package com.easyssf.quarkus.ssfreceiver.runtime.stream;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * MicroProfile REST Client view of the SSF transmitter's {@code configuration_endpoint}
 * (spec §7.1.1).
 *
 * <p>
 * Bound to a runtime {@code baseUri} via {@code RestClientBuilder} because the
 * endpoint URL is discovered from the transmitter's metadata document; this is why
 * the interface isn't annotated with {@code @RegisterRestClient}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public interface SsfTransmitterStreamConfigurationApi {

    /** Read a stream's configuration — §7.1.1.2. */
    @GET
    StreamConfigurationDto configuration(@QueryParam("stream_id") String streamId);

    /**
     * List all streams owned by the calling Receiver — §7.1.1.2 (the same endpoint,
     * called without {@code stream_id}). Receiver-managed mode uses this to
     * discover whether a stream already exists for this receiver before creating
     * a new one.
     */
    @GET
    List<StreamConfigurationDto> listStreams();

    /**
     * Create a new stream — §7.1.1.1. The receiver POSTs the desired configuration
     * (without {@code stream_id}); the transmitter assigns the id and echoes the
     * full document back.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    StreamConfigurationDto create(StreamConfigurationDto request);

    /**
     * Replace an existing stream's configuration — §8.1.1.4 (PUT). The body must
     * include {@code stream_id}; per the spec, "missing Receiver-Supplied
     * properties MUST be interpreted as requested to be deleted."
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    StreamConfigurationDto update(StreamConfigurationDto request);

    /**
     * Partially update an existing stream's configuration — §8.1.1.3 (PATCH).
     * The body is a "JSON representation of the stream configuration properties
     * to change", plus {@code stream_id}. Per the spec, "any Receiver-Supplied
     * property present in the request MUST be updated by the Transmitter. Any
     * properties missing in the request MUST NOT be changed by the Transmitter."
     *
     * <p>
     * Note: the SSF spec does not prescribe a content type or invoke RFC 7396
     * JSON Merge Patch by name, so we send {@code application/json}. The DTO's
     * {@code @JsonInclude(NON_NULL)} ensures fields the caller leaves blank are
     * omitted from the wire body — matching the "missing → unchanged" rule.
     */
    @PATCH
    @Consumes(MediaType.APPLICATION_JSON)
    StreamConfigurationDto patch(StreamConfigurationDto request);

    /** Delete a stream — §7.1.1.4. */
    @DELETE
    void delete(@QueryParam("stream_id") String streamId);
}
