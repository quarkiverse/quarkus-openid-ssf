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
package com.easyssf.quarkus.ssfreceiver.example.receivermanaged;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfAliases;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventToken;

/**
 * Demo-friendly snapshot of an inbound SET. Wraps the verified
 * {@link SsfEventToken} with:
 * <ul>
 * <li>{@code capturedAt} — local wall-clock time when the receiver accepted
 * the SET, so the demo can show end-to-end latency vs the SET's {@code iat}.</li>
 * <li>{@code issAlias} — pre-resolved alias for the issuer URL.</li>
 * <li>{@code events} — the per-event-type payload map, keyed by the alias
 * (or the URI when no alias is configured). This is the same data the
 * SSF transmitter put in the SET's {@code events} claim, just keyed for
 * readability instead of by full URI.</li>
 * </ul>
 *
 * <p>
 * Returned by {@code GET /events/recent-events} and {@code /events/latest}.
 */
public record CapturedEvent(
        Instant capturedAt,
        String jti,
        String issAlias,
        String iss,
        Instant iat,
        List<String> aud,
        String txn,
        Map<String, Object> subjectId,
        Map<String, Object> events,
        SsfEventToken raw) {

    public static CapturedEvent of(SsfEventToken token, SsfAliases aliases) {
        Map<String, Object> events = new LinkedHashMap<>();
        if (token.events() != null) {
            // Preserve insertion order so the JSON keys match what the transmitter sent.
            token.events().forEach((uri, payload) -> events.put(aliases.eventTypeAlias(uri), payload));
        }
        return new CapturedEvent(
                Instant.now(),
                token.jti(),
                aliases.issuerAlias(token.iss()),
                token.iss(),
                token.iat(),
                token.aud(),
                token.txn(),
                token.subjectId(),
                Map.copyOf(events),
                token);
    }
}
