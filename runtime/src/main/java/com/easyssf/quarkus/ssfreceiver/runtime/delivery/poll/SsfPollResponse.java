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
package com.easyssf.quarkus.ssfreceiver.runtime.delivery.poll;

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
