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
 * <li><b>Event types</b> — built-in defaults for SSF, CAEP, and RISC spec
 * event types, overridable via {@code ssf.receiver.event-aliases.<alias>=<uri>}.</li>
 * <li><b>Issuers</b> — no defaults; configured via
 * {@code ssf.receiver.issuer-aliases.<alias>=<url>}.</li>
 * <li><b>This receiver</b> — single string via {@code ssf.receiver.alias},
 * falling back to {@code expected-audience}, then {@code "unknown"}.</li>
 * </ul>
 *
 * <p>
 * Built-in event-type aliases cover the OpenID SSF, CAEP-1.0, and RISC-1.0
 * event types — see the constants below. Consumers can override any of them
 * by registering their own alias for the same URI.
 */
@ApplicationScoped
public class SsfAliases {

    private static final String UNKNOWN = "unknown";

    private static final String SSF_BASE = "https://schemas.openid.net/secevent/ssf/event-type/";
    private static final String CAEP_BASE = "https://schemas.openid.net/secevent/caep/event-type/";
    private static final String RISC_BASE = "https://schemas.openid.net/secevent/risc/event-type/";

    /**
     * Built-in aliases for SSF, CAEP, and RISC spec event types; always
     * registered. Consumer entries in {@code ssf.receiver.event-aliases.*}
     * overlay these — a user mapping for a built-in URI replaces the alias.
     */
    private static final Map<String, String> BUILT_IN_EVENT_ALIASES;
    static {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        // OpenID SSF — stream lifecycle events.
        m.put(SSF_BASE + "verification", "SsfStreamVerification");
        m.put(SSF_BASE + "stream-updated", "SsfStreamUpdated");
        // OpenID CAEP 1.0 — see https://openid.net/specs/openid-caep-1_0-final.html
        m.put(CAEP_BASE + "session-revoked", "CaepSessionRevoked");
        m.put(CAEP_BASE + "token-claims-change", "CaepTokenClaimsChange");
        m.put(CAEP_BASE + "credential-change", "CaepCredentialChange");
        m.put(CAEP_BASE + "assurance-level-change", "CaepAssuranceLevelChange");
        m.put(CAEP_BASE + "device-compliance-change", "CaepDeviceComplianceChange");
        m.put(CAEP_BASE + "session-established", "CaepSessionEstablished");
        m.put(CAEP_BASE + "session-presented", "CaepSessionPresented");
        m.put(CAEP_BASE + "risk-level-change", "CaepRiskLevelChange");
        // OpenID RISC 1.0 — see https://openid.net/specs/openid-risc-1_0-final.html
        m.put(RISC_BASE + "account-credential-change-required", "RiscAccountCredentialChangeRequired");
        m.put(RISC_BASE + "account-purged", "RiscAccountPurged");
        m.put(RISC_BASE + "account-disabled", "RiscAccountDisabled");
        m.put(RISC_BASE + "account-enabled", "RiscAccountEnabled");
        m.put(RISC_BASE + "identifier-changed", "RiscIdentifierChanged");
        m.put(RISC_BASE + "identifier-recycled", "RiscIdentifierRecycled");
        m.put(RISC_BASE + "credential-compromise", "RiscCredentialCompromise");
        m.put(RISC_BASE + "opt-in", "RiscOptIn");
        m.put(RISC_BASE + "opt-out-initiated", "RiscOptOutInitiated");
        m.put(RISC_BASE + "opt-out-cancelled", "RiscOptOutCancelled");
        m.put(RISC_BASE + "opt-out-effective", "RiscOptOutEffective");
        m.put(RISC_BASE + "recovery-activated", "RiscRecoveryActivated");
        m.put(RISC_BASE + "recovery-information-changed", "RiscRecoveryInformationChanged");
        BUILT_IN_EVENT_ALIASES = Collections.unmodifiableMap(m);
    }

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
