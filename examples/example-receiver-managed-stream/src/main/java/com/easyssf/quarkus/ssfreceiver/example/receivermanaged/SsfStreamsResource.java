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
package com.easyssf.quarkus.ssfreceiver.example.receivermanaged;

import java.util.List;
import java.util.Locale;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
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

import com.easyssf.quarkus.ssfreceiver.runtime.delivery.poll.SsfPoller;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.ReceiverManagedStreamState;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfStreamClient;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfStreamException;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.StreamConfiguration;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.status.StreamStatus;

/**
 * Receiver-managed companion of the transmitter-managed example's resource: instead
 * of pinning a stream id at config time, we look it up from
 * {@link ReceiverManagedStreamState} (populated by the registrar on startup).
 */
@Path("/streams")
public class SsfStreamsResource {

    private static final String DEFAULT_ALIAS = "default";

    @Inject
    SsfStreamClient streamClient;

    @Inject
    ReceiverManagedStreamState state;

    @Inject
    SsfPoller poller;

    /** GET /streams — list streams the receiver owns on the transmitter (§7.1.1.2 with no stream_id). */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<StreamConfiguration> list() {
        try {
            return streamClient.listStreams();
        } catch (SsfStreamException e) {
            throw badGateway(e);
        }
    }

    /** GET /streams/default — the configuration of the auto-registered stream. */
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

    /** GET /streams/default/status — read the live status from the transmitter. */
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

    /** POST /streams/default/status?status=paused&reason=... */
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

    /** POST /streams/default/verify[?state=...] — trigger a Verification SET. */
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
     * POST /streams/default/poll — manually trigger one poll cycle (RFC 8936).
     * Useful for demonstrating POLL delivery, smoke-testing without waiting
     * for the next periodic tick, or driving polling from app code when
     * {@code ssf.receiver.poll.auto-start=false}.
     *
     * <p>
     * Errors out with 409 if the receiver isn't configured for POLL
     * delivery; with 503 if the poller isn't initialised yet.
     */
    @POST
    @Path("/{alias}/poll")
    @Produces(MediaType.APPLICATION_JSON)
    public PollTriggered triggerPoll(@PathParam("alias") String alias) {
        requireDefaultAlias(alias);
        try {
            poller.pollNow();
            return new PollTriggered("ok");
        } catch (IllegalStateException e) {
            // pollNow() throws when delivery-method != POLL, or pre-startup.
            int status = e.getMessage() != null && e.getMessage().contains("delivery-method") ? 409 : 503;
            throw new WebApplicationException(
                    Response.status(status)
                            .type(MediaType.TEXT_PLAIN)
                            .entity(e.getMessage())
                            .build());
        }
    }

    /**
     * DELETE /streams/default — manual delete of the receiver-owned stream (§7.1.1.4).
     * Mirrors what the registrar does on shutdown when delete-on-shutdown=true.
     */
    @DELETE
    @Path("/{alias}")
    public Response delete(@PathParam("alias") String alias) {
        requireDefaultAlias(alias);
        String streamId = state.streamId()
                .orElseThrow(() -> new NotFoundException("No receiver-managed stream is currently registered"));
        try {
            streamClient.deleteStream(streamId);
            state.clear();
            return Response.noContent().build();
        } catch (SsfStreamException e) {
            throw badGateway(e);
        }
    }

    public record VerificationRequested(String state) {
    }

    public record PollTriggered(String result) {
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
