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
package com.easyssf.quarkus.ssfreceiver.runtime.event;

import java.util.Map;

/**
 * Dispatch-time wrapper around a verified {@link SsfEventToken} that adds
 * {@link SsfAliases}-resolved convenience accessors. Passed to
 * {@link SsfEventHandler#handle(SsfEventContext)}.
 *
 * <p>
 * The context is built once per delivery (PUSH or POLL); the alias-resolved
 * views are computed eagerly so handler code stays cheap regardless of how
 * many lookups it makes.
 *
 * <p>
 * Handlers that don't care about aliases just call {@link #eventToken()} and
 * stick to the raw record's accessors. Handlers that compare against
 * configured aliases write {@code ctx.hasEvent("CaepSessionRevoked")} —
 * which also accepts the full URI, so handlers stay correct even if the
 * alias mapping is dropped from configuration.
 */
public interface SsfEventContext {

    /** The verified {@link SsfEventToken} this context wraps. Never {@code null}. */
    SsfEventToken eventToken();

    /**
     * Resolved alias for {@code eventToken().iss()}, or the issuer URL itself
     * if no alias is registered. Equivalent to
     * {@code aliases.issuerAlias(eventToken().iss())} but pre-computed.
     */
    String issAlias();

    /**
     * Resolved alias for the given event-type URI, or the URI itself if no
     * alias is registered. Equivalent to {@code aliases.eventTypeAlias(uri)}.
     */
    String eventTypeAlias(String eventTypeUri);

    /**
     * Alias-keyed view of {@link SsfEventToken#events()}. Each URI key in the
     * raw events map is replaced by its resolved alias (or stays as the URI
     * if no alias matches). Useful for {@code switch}-style dispatch on
     * configured short names.
     *
     * <p>
     * Values are {@code Object} for parity with {@link SsfEventToken#events()}
     * — payloads are typically {@code Map<String, Object>} but the SSF spec
     * doesn't constrain the shape, so the loose typing matches the record's.
     */
    Map<String, Object> eventsByAlias();

    /**
     * Returns {@code true} if {@code aliasOrUri} matches an entry in
     * {@code eventToken().events()} either by URI directly or via the
     * configured alias mapping. Tolerant to either form so handlers stay
     * correct whether or not aliases are configured.
     */
    boolean hasEvent(String aliasOrUri);

    /**
     * Returns the per-event-type payload for {@code aliasOrUri}, or
     * {@code null} if not present. Lookup is alias-aware: an alias resolves
     * to its URI, and a URI is returned directly. Equivalent to
     * {@code eventToken().events().get(<resolved-uri>)} with the alias step
     * folded in.
     *
     * <p>
     * RFC 8417 §2.2 specifies the payload as a JSON object, which Jackson
     * deserializes to {@code Map<String, Object>}. If the transmitter sends
     * a non-object value (spec-non-compliant), this method returns
     * {@code null} rather than throwing.
     */
    Map<String, Object> eventFor(String aliasOrUri);

    /**
     * Builds a context from a verified token and the application's alias
     * registry. Called by the PUSH route and POLL loop right before
     * dispatching to the handler; consumers writing their own dispatch
     * paths (e.g. a manual replay tool) can use it too.
     */
    static SsfEventContext of(SsfEventToken eventToken, SsfAliases aliases) {
        return new DefaultSsfEventContext(eventToken, aliases);
    }
}
