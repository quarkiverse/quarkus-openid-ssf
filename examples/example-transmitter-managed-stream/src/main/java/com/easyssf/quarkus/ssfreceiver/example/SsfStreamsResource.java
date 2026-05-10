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
package com.easyssf.quarkus.ssfreceiver.example;

import java.util.Locale;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfStreamClient;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfStreamException;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.StreamConfiguration;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.status.StreamStatus;

@Path("/streams")
public class SsfStreamsResource {

    /** Alias for the single stream this app is configured to receive. */
    private static final String DEFAULT_ALIAS = "default";

    @Inject
    SsfStreamClient streamClient;

    /**
     * Reads the live stream configuration from the transmitter via
     * {@link SsfStreamClient#configuration()} (SSF spec §7.1.1.2).
     */
    @GET
    @Path("/{alias}")
    @Produces(MediaType.APPLICATION_JSON)
    public StreamConfiguration configuration(@PathParam("alias") String alias) {
        requireDefaultAlias(alias);
        try {
            return streamClient.configuration();
        } catch (SsfStreamException e) {
            throw badGateway(e);
        }
    }

    /**
     * Reads the live stream status from the transmitter via {@link SsfStreamClient}.
     * Outbound auth (Bearer token via client_credentials) is provided automatically by
     * the OIDC-backed {@code TransmitterTokenProvider} when {@code quarkus-oidc-client}
     * is configured — see {@code application.properties}.
     */
    @GET
    @Path("/{alias}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public StreamStatus status(@PathParam("alias") String alias) {
        requireDefaultAlias(alias);
        try {
            return streamClient.status();
        } catch (SsfStreamException e) {
            throw badGateway(e);
        }
    }

    /**
     * Toggles the stream status — POST {@code /streams/default/status?status=paused&reason=...}.
     * Convenience over the {@link SsfStreamClient#updateStatus} API.
     */
    @POST
    @Path("/{alias}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public StreamStatus updateStatus(
            @PathParam("alias") String alias,
            @QueryParam("status") String statusParam,
            @QueryParam("reason") String reason) {
        requireDefaultAlias(alias);
        if (statusParam == null || statusParam.isBlank()) {
            throw new BadRequestException("status query parameter is required (one of: enabled, paused, disabled)");
        }
        StreamStatus.Status target;
        try {
            target = StreamStatus.Status.valueOf(statusParam.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("status must be one of: enabled, paused, disabled (got: " + statusParam + ")");
        }
        if (target == StreamStatus.Status.UNKNOWN) {
            throw new BadRequestException("status must be one of: enabled, paused, disabled");
        }
        try {
            return streamClient.updateStatus(target, reason);
        } catch (SsfStreamException e) {
            throw badGateway(e);
        }
    }

    /**
     * Triggers a Verification Event on the transmitter (§8.1.4.2). If {@code state}
     * is omitted, a fresh random value is generated. Returns the state used so the
     * caller can correlate the inbound Verification SET that arrives at /ssf/push.
     */
    @POST
    @Path("/{alias}/verify")
    @Produces(MediaType.APPLICATION_JSON)
    public VerificationRequested verify(
            @PathParam("alias") String alias,
            @QueryParam("state") String state) {
        requireDefaultAlias(alias);
        try {
            String used;
            if (state == null || state.isBlank()) {
                used = streamClient.requestVerification();
            } else {
                streamClient.requestVerification(state);
                used = state;
            }
            return new VerificationRequested(used);
        } catch (SsfStreamException e) {
            throw badGateway(e);
        }
    }

    /**
     * Adds a subject to the configured stream — POST {@code /streams/default/subjects/add}
     * with a body like {@code { "subject": { "format": "email", "email": "..." }, "verified": true }}
     * (SSF spec §8.1.3.2). Spec response is {@code 200 OK}.
     */
    @POST
    @Path("/{alias}/subjects/add")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addSubject(@PathParam("alias") String alias, AddSubjectRequest body) {
        requireDefaultAlias(alias);
        if (body == null || body.subject() == null) {
            throw new BadRequestException("body.subject is required");
        }
        try {
            streamClient.addSubject(body.subject(), body.verified());
            return Response.ok().build();
        } catch (SsfStreamException e) {
            throw badGateway(e);
        }
    }

    /**
     * Removes a subject from the configured stream — POST {@code /streams/default/subjects/remove}
     * with a body like {@code { "subject": { "format": "email", "email": "..." } }}
     * (SSF spec §8.1.3.3). Spec response is {@code 204 No Content}.
     */
    @POST
    @Path("/{alias}/subjects/remove")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response removeSubject(@PathParam("alias") String alias, RemoveSubjectRequest body) {
        requireDefaultAlias(alias);
        if (body == null || body.subject() == null) {
            throw new BadRequestException("body.subject is required");
        }
        try {
            streamClient.removeSubject(body.subject());
            return Response.noContent().build();
        } catch (SsfStreamException e) {
            throw badGateway(e);
        }
    }

    public record VerificationRequested(String state) {
    }

    public record AddSubjectRequest(Map<String, Object> subject, Boolean verified) {
    }

    public record RemoveSubjectRequest(Map<String, Object> subject) {
    }

    private static void requireDefaultAlias(String alias) {
        if (!DEFAULT_ALIAS.equals(alias)) {
            throw new NotFoundException("Unknown stream alias: " + alias
                    + " (this app exposes only '" + DEFAULT_ALIAS + "')");
        }
    }

    private static WebApplicationException badGateway(SsfStreamException e) {
        return new WebApplicationException(
                Response.status(Response.Status.BAD_GATEWAY)
                        .type(MediaType.TEXT_PLAIN)
                        .entity(e.getMessage())
                        .build());
    }
}
