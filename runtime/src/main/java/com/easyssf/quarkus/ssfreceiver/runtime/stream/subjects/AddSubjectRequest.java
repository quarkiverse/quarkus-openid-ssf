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
package com.easyssf.quarkus.ssfreceiver.runtime.stream.subjects;

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
