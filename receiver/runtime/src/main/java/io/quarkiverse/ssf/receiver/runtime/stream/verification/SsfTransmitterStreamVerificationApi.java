package io.quarkiverse.ssf.receiver.runtime.stream.verification;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * MicroProfile REST Client view of the SSF transmitter's {@code verification_endpoint}
 * (spec §8.1.4.2 — trigger a Verification Event).
 *
 * <p>
 * Bound to a runtime {@code baseUri} via {@code RestClientBuilder} because the
 * endpoint URL is discovered from the transmitter metadata document.
 */
@Path("/")
public interface SsfTransmitterStreamVerificationApi {

    /**
     * POSTs a verification request and expects a {@code 204 No Content} success.
     * Non-2xx responses surface as {@code jakarta.ws.rs.WebApplicationException}.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    void requestVerification(StreamVerificationRequest request);
}
