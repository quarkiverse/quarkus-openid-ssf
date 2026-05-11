package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body returned by the transmitter's poll endpoint — RFC 8936 §2.1.
 * {@code sets} maps each delivered SET's {@code jti} to its compact-serialized
 * JWT string. {@code moreAvailable} signals that the transmitter has more
 * events queued than the receiver asked for in {@code maxEvents}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SsfPollResponse(
        @JsonProperty("sets") Map<String, String> sets,
        @JsonProperty("moreAvailable") Boolean moreAvailable) {
}
