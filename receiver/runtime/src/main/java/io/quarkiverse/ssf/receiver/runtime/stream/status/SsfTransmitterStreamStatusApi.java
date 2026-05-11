package io.quarkiverse.ssf.receiver.runtime.stream.status;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * MicroProfile REST Client view of the SSF transmitter's stream endpoints.
 * The base URI is bound at runtime via {@link org.eclipse.microprofile.rest.client.RestClientBuilder}
 * because it's discovered from the transmitter metadata document, so this interface
 * isn't annotated with {@code @RegisterRestClient}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public interface SsfTransmitterStreamStatusApi {

    /** Reads the status of a single stream — SSF spec §8.1.2.1. */
    @GET
    StreamStatusDto status(@QueryParam("stream_id") String streamId);

    /** Updates the status of a single stream — SSF spec §8.1.2.2. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    StreamStatusDto updateStatus(StreamStatusDto request);
}
