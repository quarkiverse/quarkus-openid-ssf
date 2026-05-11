package io.quarkiverse.ssf.receiver.runtime.stream.status;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * On-the-wire shape of a stream-status request/response with an SSF transmitter
 * (spec §8.1.2.1, §8.1.2.2). Internal type — the public {@link StreamStatus} record
 * is what consumers see. {@code reason} is dropped on serialization when null so
 * outbound update requests don't carry a stray {@code "reason": null}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamStatusDto(
        @JsonProperty("stream_id") String streamId,
        @JsonProperty("status") String status,
        @JsonProperty("reason") String reason) {
    public StreamStatus toStreamStatus() {
        return new StreamStatus(streamId, status, reason);
    }
}
