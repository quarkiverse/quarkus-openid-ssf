package io.quarkiverse.ssf.receiver.runtime.stream.subjects;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * MicroProfile REST Client view of the SSF transmitter's {@code add_subject_endpoint}
 * (spec §8.1.3.2). Bound to a runtime {@code baseUri} via {@code RestClientBuilder}.
 */
@Path("/")
public interface SsfTransmitterStreamAddSubjectApi {

    /**
     * Adds the subject to the stream. Spec response is {@code 200 OK} with empty body.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    void addSubject(AddSubjectRequest request);
}
