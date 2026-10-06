package io.quarkiverse.ssf.receiver.example;

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

import org.easyssf.core.stream.SsfStreamStatus;
import org.easyssf.receiver.stream.SsfStreamException;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;

/**
 * The stream configured with {@code stream-id}, through {@link SsfReceiverStreamClient}.
 * Calls to the transmitter are authenticated by the token provider the extension
 * selected, here the OIDC client configured in {@code application.properties}.
 */
@Path("/streams")
public class SsfStreamsResource {

    /** Alias for the single stream this app is configured to receive. */
    private static final String DEFAULT_ALIAS = "default";

    @Inject
    SsfReceiverStreamClient streamClient;

    /** GET /streams/default: the configuration of the stream, as the transmitter returns it (SSF 8.1.1). */
    @GET
    @Path("/{alias}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> configuration(@PathParam("alias") String alias) {
        requireDefaultAlias(alias);
        try {
            return streamClient.configuration().claims();
        } catch (SsfStreamException e) {
            throw asResponse(e);
        }
    }

    /** GET /streams/default/status: the live status (SSF 8.1.2). */
    @GET
    @Path("/{alias}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public SsfStreamStatus status(@PathParam("alias") String alias) {
        requireDefaultAlias(alias);
        try {
            return streamClient.status();
        } catch (SsfStreamException e) {
            throw asResponse(e);
        }
    }

    /** {@code POST /streams/default/status?status=paused&reason=...} */
    @POST
    @Path("/{alias}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public SsfStreamStatus updateStatus(
            @PathParam("alias") String alias,
            @QueryParam("status") String status,
            @QueryParam("reason") String reason) {
        requireDefaultAlias(alias);
        if (status == null || status.isBlank()) {
            throw new BadRequestException("status query parameter is required (one of: enabled, paused, disabled)");
        }
        try {
            return streamClient.updateStatus(status, reason);
        } catch (SsfStreamException e) {
            throw asResponse(e);
        }
    }

    /**
     * POST /streams/default/verify: asks the transmitter for a verification event (SSF
     * 8.1.4). The returned state is the one the verification SET that arrives at
     * {@code /ssf/push} has to echo; the extension checks it.
     */
    @POST
    @Path("/{alias}/verify")
    @Produces(MediaType.APPLICATION_JSON)
    public VerificationRequested verify(@PathParam("alias") String alias) {
        requireDefaultAlias(alias);
        try {
            return new VerificationRequested(streamClient.requestVerification());
        } catch (SsfStreamException e) {
            throw asResponse(e);
        }
    }

    /**
     * POST /streams/default/subjects/add with a body like
     * {@code { "subject": { "format": "email", "email": "..." }, "verified": true }} (SSF
     * 8.1.3.2).
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
            streamClient.addSubject(body.subject(), Boolean.TRUE.equals(body.verified()));
            return Response.ok().build();
        } catch (SsfStreamException e) {
            throw asResponse(e);
        }
    }

    /**
     * POST /streams/default/subjects/remove with a body like
     * {@code { "subject": { "format": "email", "email": "..." } }} (SSF 8.1.3.3).
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
            throw asResponse(e);
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

    /** A validation error of the client is a 400, an answer of the transmitter a 502. */
    private static WebApplicationException asResponse(SsfStreamException e) {
        Response.Status status = (e.getStatusCode() == 0 && e.getCause() == null) ? Response.Status.BAD_REQUEST
                : Response.Status.BAD_GATEWAY;
        return new WebApplicationException(
                Response.status(status).type(MediaType.TEXT_PLAIN).entity(e.getMessage()).build());
    }
}
