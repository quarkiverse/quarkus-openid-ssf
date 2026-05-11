package io.quarkiverse.ssf.receiver.runtime.stream.verification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * On-the-wire request body for triggering a Verification Event — SSF spec §8.1.4.2.
 * {@code state} is dropped on serialization when null so an unsolicited-style request
 * (transmitter does NOT echo state back) is also possible from the receiver side.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamVerificationRequest(
        @JsonProperty("stream_id") String streamId,
        @JsonProperty("state") String state) {
}
