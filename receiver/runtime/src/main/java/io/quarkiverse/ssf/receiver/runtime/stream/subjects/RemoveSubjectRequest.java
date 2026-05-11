package io.quarkiverse.ssf.receiver.runtime.stream.subjects;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for removing a subject from a stream — SSF spec §8.1.3.3.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemoveSubjectRequest(
        @JsonProperty("stream_id") String streamId,
        @JsonProperty("subject") Map<String, Object> subject) {
}
