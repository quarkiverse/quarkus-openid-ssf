package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * MicroProfile REST Client view of the transmitter's poll endpoint — RFC 8936 §2.
 * Bound to a runtime {@code baseUri} via {@code RestClientBuilder} because the
 * URL is discovered from the stream's {@code delivery.endpoint_url}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface SsfTransmitterPollApi {

    @POST
    SsfPollResponse poll(SsfPollRequest request);
}
