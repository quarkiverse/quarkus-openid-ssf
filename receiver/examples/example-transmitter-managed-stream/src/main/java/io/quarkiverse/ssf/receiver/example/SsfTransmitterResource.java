package io.quarkiverse.ssf.receiver.example;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.ssf.receiver.runtime.metadata.SsfConfigurationResolver;
import io.quarkiverse.ssf.receiver.runtime.metadata.SsfTransmitterMetadata;

/**
 * Endpoints exposing transmitter-side information that isn't bound to a single
 * stream — currently just the parsed {@code ssf-configuration} metadata document.
 */
@Path("/transmitter")
public class SsfTransmitterResource {

    @Inject
    SsfConfigurationResolver metadataResolver;

    @GET
    @Path("/metadata")
    @Produces(MediaType.APPLICATION_JSON)
    public SsfTransmitterMetadata metadata() {
        return metadataResolver.get();
    }
}
