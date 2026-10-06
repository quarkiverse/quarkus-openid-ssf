package io.quarkiverse.ssf.receiver.example.receivermanaged;

import java.util.List;
import java.util.Map;

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

import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.core.stream.SsfStreamStatus;
import org.easyssf.receiver.stream.SsfStreamException;

import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPollScheduler;
import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;

/**
 * The stream of this receiver at the transmitter, through {@link SsfReceiverStreamClient}:
 * the stream the registrar discovered or created on startup.
 */
@Path("/streams")
public class SsfStreamsResource {

    private static final String DEFAULT_ALIAS = "default";

    @Inject
    SsfReceiverStreamClient streamClient;

    @Inject
    SsfPollScheduler pollScheduler;

    /** GET /streams: the streams this receiver owns at the transmitter. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> list() {
        try {
            return streamClient.listStreams().stream().map(SsfStreamConfiguration::claims).toList();
        } catch (SsfStreamException e) {
            throw asResponse(e);
        }
    }

    /** GET /streams/default: the configuration of the auto-registered stream, as the transmitter returns it. */
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

    /** GET /streams/default/status */
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

    /** POST /streams/default/verify: asks the transmitter for a verification event. */
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
     * POST /streams/default/poll: polls the transmitter once (RFC 8936), for demos and for
     * driving polling from application code with {@code poll.auto-start=false}. 409 when the
     * receiver does not deliver by POLL.
     */
    @POST
    @Path("/{alias}/poll")
    @Produces(MediaType.APPLICATION_JSON)
    public PollTriggered triggerPoll(@PathParam("alias") String alias) {
        requireDefaultAlias(alias);
        try {
            return new PollTriggered("ok", pollScheduler.pollNow());
        } catch (IllegalStateException e) {
            throw new WebApplicationException(
                    Response.status(409).type(MediaType.TEXT_PLAIN).entity(e.getMessage()).build());
        }
    }

    /** DELETE /streams/default: deletes the stream at the transmitter. */
    @DELETE
    @Path("/{alias}")
    public Response delete(@PathParam("alias") String alias) {
        requireDefaultAlias(alias);
        try {
            streamClient.deleteStream();
            return Response.noContent().build();
        } catch (SsfStreamException e) {
            throw asResponse(e);
        }
    }

    public record VerificationRequested(String state) {
    }

    public record PollTriggered(String result, int fetched) {
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
