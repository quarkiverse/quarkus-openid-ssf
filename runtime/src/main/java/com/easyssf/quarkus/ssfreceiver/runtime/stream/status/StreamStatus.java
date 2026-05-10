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

import java.util.Locale;

/**
 * Result of an SSF stream-status request (spec §8.1.2.1). {@code status} is the
 * verbatim string the transmitter returned — use {@link #known()} to map it onto
 * the spec-defined enum, which falls back to {@link Status#UNKNOWN} for forward
 * compatibility.
 */
public record StreamStatus(String streamId, String status, String reason) {

    public Status known() {
        if (status == null)
            return Status.UNKNOWN;
        try {
            return Status.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Status.UNKNOWN;
        }
    }

    public enum Status {
        ENABLED,
        PAUSED,
        DISABLED,
        UNKNOWN
    }
}
