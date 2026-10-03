package io.quarkiverse.ssf.receiver.example;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;

/**
 * Transmitter-side information that is not bound to a single stream: the parsed
 * {@code ssf-configuration} metadata document.
 */
@Path("/transmitter")
public class SsfTransmitterResource {

    @Inject
    SsfReceiverStreamClient streamClient;

    @GET
    @Path("/metadata")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> metadata() {
        return streamClient.transmitter().getMetadataResolver().resolve().claims();
    }
}
