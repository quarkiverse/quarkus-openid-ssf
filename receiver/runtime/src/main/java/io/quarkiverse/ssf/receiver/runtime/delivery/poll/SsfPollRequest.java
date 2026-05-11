package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body sent to the transmitter's poll endpoint — RFC 8936 §2.1.
 * {@code ack} carries the {@code jti}s of SETs the receiver successfully
 * processed since the last poll; the transmitter uses that as the cursor for
 * what to omit from the next response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SsfPollRequest(
        @JsonProperty("maxEvents") Integer maxEvents,
        @JsonProperty("returnImmediately") Boolean returnImmediately,
        @JsonProperty("ack") List<String> ack) {
}
