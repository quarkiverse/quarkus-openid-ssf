package io.quarkiverse.ssf.receiver.example;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;

/**
 * Demo endpoint, not something a real receiver exposes: it makes the receiver's view of
 * the transmitter visible, the {@code ssf-configuration} metadata it resolved, so that
 * it can be inspected while trying the example. It is unauthenticated.
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
