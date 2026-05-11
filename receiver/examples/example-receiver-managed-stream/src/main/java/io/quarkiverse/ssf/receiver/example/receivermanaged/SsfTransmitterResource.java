package io.quarkiverse.ssf.receiver.example.receivermanaged;

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.ssf.receiver.runtime.metadata.SsfConfigurationResolver;
import io.quarkiverse.ssf.receiver.runtime.metadata.SsfTransmitterMetadata;
import io.quarkiverse.ssf.receiver.runtime.stream.ReceiverManagedStreamState;

/**
 * Endpoints exposing transmitter-side info plus the receiver-specific state —
 * notably the {@code stream_id} that the registrar discovered or created.
 */
@Path("/transmitter")
public class SsfTransmitterResource {

    @Inject
    SsfConfigurationResolver metadataResolver;

    @Inject
    ReceiverManagedStreamState state;

    @GET
    @Path("/metadata")
    @Produces(MediaType.APPLICATION_JSON)
    public SsfTransmitterMetadata metadata() {
        return metadataResolver.get();
    }

    /**
     * GET /transmitter/registration — surfaces the stream_id this receiver is
     * currently bound to. Returns an empty payload (rather than 404) before the
     * registrar has finished its discover-or-create dance.
     */
    @GET
    @Path("/registration")
    @Produces(MediaType.APPLICATION_JSON)
    public Registration registration() {
        return new Registration(state.streamId());
    }

    public record Registration(Optional<String> streamId) {
    }
}
