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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.easyssf.quarkus.ssfreceiver.runtime.SsfReceiverConfig;

/**
 * Resolves SSF URIs to short, monitoring-friendly aliases. Used as Micrometer
 * tag values on {@code ssf.receiver.events.processed}; consumers can also call
 * the lookup methods directly (log lines, REST responses, …) to keep noisy
 * URIs out of human-facing output.
 *
 * <p>
 * Three independent alias domains:
 * <ul>
 * <li><b>Event types</b> — built-in defaults for the SSF spec event types,
 * overridable via {@code ssf.receiver.event-aliases.<alias>=<uri>}.</li>
 * <li><b>Issuers</b> — no defaults; configured via
 * {@code ssf.receiver.issuer-aliases.<alias>=<url>}.</li>
 * <li><b>This receiver</b> — single string via {@code ssf.receiver.alias},
 * falling back to {@code expected-audience}, then {@code "unknown"}.</li>
 * </ul>
 *
 * <p>
 * Built-in event-type aliases:
 * <ul>
 * <li>{@code https://schemas.openid.net/secevent/ssf/event-type/verification}
 * → {@code SsfStreamVerification}</li>
 * <li>{@code https://schemas.openid.net/secevent/ssf/event-type/stream-updated}
 * → {@code SsfStreamUpdated}</li>
 * </ul>
 */
@ApplicationScoped
public class SsfAliases {

    private static final String UNKNOWN = "unknown";

    /** Built-in aliases for SSF spec event types; always registered. */
    private static final Map<String, String> BUILT_IN_EVENT_ALIASES = Map.of(
            "https://schemas.openid.net/secevent/ssf/event-type/verification", "SsfStreamVerification",
            "https://schemas.openid.net/secevent/ssf/event-type/stream-updated", "SsfStreamUpdated");

    @Inject
    SsfReceiverConfig config;

    private final Map<String, String> uriToEventAlias = new ConcurrentHashMap<>();
    private final Map<String, String> uriToIssuerAlias = new ConcurrentHashMap<>();
    private volatile String receiverAlias;

    @PostConstruct
    void init() {
        uriToEventAlias.putAll(BUILT_IN_EVENT_ALIASES);
        // Overlay consumer event-type aliases (alias -> URI in config; we store URI -> alias).
        // A user mapping for a built-in URI replaces the built-in name.
        config.eventAliases().forEach((alias, uri) -> {
            if (alias == null || alias.isBlank() || uri == null)
                return;
            uriToEventAlias.put(uri.toString(), alias);
        });
        config.issuerAliases().forEach((alias, uri) -> {
            if (alias == null || alias.isBlank() || uri == null)
                return;
            uriToIssuerAlias.put(uri.toString(), alias);
        });
        // Receiver self-alias: explicit config wins; otherwise fall back to expected-audience,
        // otherwise the literal "unknown" so the tag is never empty.
        receiverAlias = config.alias()
                .filter(s -> !s.isBlank())
                .or(config::expectedAudience)
                .filter(s -> !s.isBlank())
                .orElse(UNKNOWN);
    }

    /** Returns the alias for an event-type URI, or the URI itself if none is registered. */
    public String eventTypeAlias(String uri) {
        return resolve(uri, uriToEventAlias);
    }

    /** Returns the alias for an issuer URL, or the URL itself if none is registered. */
    public String issuerAlias(String issuer) {
        return resolve(issuer, uriToIssuerAlias);
    }

    /** This receiver's stable identity for monitoring. Never empty. */
    public String receiverAlias() {
        return receiverAlias;
    }

    /**
     * Read-only view of the URI → alias mapping for event types. Includes the
     * built-in SSF spec aliases plus any consumer overrides. Keyed by URI for
     * stable iteration; values are the short alias names.
     */
    public Map<String, String> eventTypeAliasesByUri() {
        return Collections.unmodifiableMap(uriToEventAlias);
    }

    /** Read-only view of the URL → alias mapping for transmitter issuers. */
    public Map<String, String> issuerAliasesByUri() {
        return Collections.unmodifiableMap(uriToIssuerAlias);
    }

    private static String resolve(String uri, Map<String, String> table) {
        if (uri == null || uri.isBlank()) {
            return UNKNOWN;
        }
        String alias = table.get(uri);
        return alias != null ? alias : uri;
    }
}
