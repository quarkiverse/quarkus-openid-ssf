package io.quarkiverse.ssf.receiver.example.receivermanaged;

import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.transmitter.SsfTransmitter;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;

/**
 * Transmitter-side information plus the receiver-specific state: the {@code stream_id}
 * the registrar discovered or created.
 */
@Path("/transmitter")
public class SsfTransmitterResource {

    @Inject
    SsfReceiverStreamClient streamClient;

    /** GET /transmitter/metadata: the transmitter's {@code .well-known/ssf-configuration}. */
    @GET
    @Path("/metadata")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> metadata() {
        return streamClient.transmitter().getMetadataResolver().resolve().claims();
    }

    /**
     * GET /transmitter/registration: the stream this receiver is bound to. The stream id
     * is empty (rather than 404) while the registrar is still looking the stream up or
     * creating it.
     */
    @GET
    @Path("/registration")
    @Produces(MediaType.APPLICATION_JSON)
    public Registration registration() {
        SsfTransmitter transmitter = streamClient.transmitter();
        SsfStreamRegistrar registrar = transmitter.getStreamRegistrar();
        return new Registration(
                transmitter.getName(),
                transmitter.getIssuer(),
                streamClient.stream().map(stream -> stream.streamId()),
                (registrar != null) ? registrar.getState().name() : "NONE",
                Optional.ofNullable((registrar != null) ? registrar.getLastError() : null));
    }

    public record Registration(String transmitter, String issuer, Optional<String> streamId, String registration,
            Optional<String> lastError) {
    }
}
