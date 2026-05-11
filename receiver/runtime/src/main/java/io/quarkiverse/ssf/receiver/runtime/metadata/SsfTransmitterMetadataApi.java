package io.quarkiverse.ssf.receiver.runtime.metadata;

import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * MicroProfile REST Client view of the transmitter's
 * {@code .well-known/ssf-configuration} document (SSF spec §7.1).
 *
 * <p>
 * Bound to a runtime {@code baseUri} via {@code RestClientBuilder} because
 * the URL is configurable (and varies by transmitter); the interface isn't
 * annotated with {@code @RegisterRestClient}.
 *
 * <p>
 * Returns the document as a {@code Map<String, Object>} so unknown / future
 * fields survive the round-trip — {@code SsfConfigurationResolver.parse()} maps the
 * known keys onto a typed record and stashes the rest in
 * {@code additionalProperties}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public interface SsfTransmitterMetadataApi {

    @GET
    Map<String, Object> get();
}
