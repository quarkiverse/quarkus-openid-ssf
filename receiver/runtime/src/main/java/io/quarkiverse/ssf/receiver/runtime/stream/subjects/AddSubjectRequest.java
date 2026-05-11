package io.quarkiverse.ssf.receiver.runtime.stream.subjects;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for adding a subject to a stream — SSF spec §8.1.3.2.
 * {@code verified} is dropped when null so the transmitter falls back to its default
 * (treats the subject as verified, per spec).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddSubjectRequest(
        @JsonProperty("stream_id") String streamId,
        @JsonProperty("subject") Map<String, Object> subject,
        @JsonProperty("verified") Boolean verified) {
}
