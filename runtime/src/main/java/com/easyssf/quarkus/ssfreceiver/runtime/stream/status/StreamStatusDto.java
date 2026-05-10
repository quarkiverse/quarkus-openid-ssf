/*
 * Copyright 2026 Thomas Darimont and the easyssf.com contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.easyssf.quarkus.ssfreceiver.runtime.stream.status;

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
