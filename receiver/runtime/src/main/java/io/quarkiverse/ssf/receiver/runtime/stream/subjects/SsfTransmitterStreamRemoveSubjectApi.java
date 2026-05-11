package io.quarkiverse.ssf.receiver.runtime.stream.subjects;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * MicroProfile REST Client view of the SSF transmitter's {@code remove_subject_endpoint}
 * (spec §8.1.3.3). Bound to a runtime {@code baseUri} via {@code RestClientBuilder}.
 */
@Path("/")
public interface SsfTransmitterStreamRemoveSubjectApi {

    /**
     * Removes the subject from the stream. Spec response is {@code 204 No Content}.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    void removeSubject(RemoveSubjectRequest request);
}
