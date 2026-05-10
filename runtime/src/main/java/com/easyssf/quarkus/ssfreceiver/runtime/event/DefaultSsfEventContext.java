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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link SsfEventContext} — pre-resolves {@code issAlias} and
 * {@code eventsByAlias} eagerly at construction so the handler-side accessors
 * are O(1) lookups. Built once per delivery in {@code SsfPushRoute} /
 * {@code SsfPoller}; not a CDI bean.
 */
final class DefaultSsfEventContext implements SsfEventContext {

    private final SsfEventToken eventToken;
    private final SsfAliases aliases;
    private final String issAlias;
    private final Map<String, Object> eventsByAlias;

    DefaultSsfEventContext(SsfEventToken eventToken, SsfAliases aliases) {
        this.eventToken = eventToken;
        this.aliases = aliases;
        this.issAlias = aliases.issuerAlias(eventToken.iss());

        Map<String, Object> raw = eventToken.events();
        if (raw == null || raw.isEmpty()) {
            this.eventsByAlias = Collections.emptyMap();
        } else {
            Map<String, Object> byAlias = new LinkedHashMap<>(raw.size());
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                byAlias.put(aliases.eventTypeAlias(e.getKey()), e.getValue());
            }
            this.eventsByAlias = Collections.unmodifiableMap(byAlias);
        }
    }

    @Override
    public SsfEventToken eventToken() {
        return eventToken;
    }

    @Override
    public String issAlias() {
        return issAlias;
    }

    @Override
    public String eventTypeAlias(String eventTypeUri) {
        return aliases.eventTypeAlias(eventTypeUri);
    }

    @Override
    public Map<String, Object> eventsByAlias() {
        return eventsByAlias;
    }

    @Override
    public boolean hasEvent(String aliasOrUri) {
        if (aliasOrUri == null || aliasOrUri.isEmpty()) {
            return false;
        }
        Map<String, Object> raw = eventToken.events();
        if (raw != null && raw.containsKey(aliasOrUri)) {
            return true;
        }
        return eventsByAlias.containsKey(aliasOrUri);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> eventFor(String aliasOrUri) {
        if (aliasOrUri == null || aliasOrUri.isEmpty()) {
            return null;
        }
        Map<String, Object> raw = eventToken.events();
        if (raw != null) {
            Object byUri = raw.get(aliasOrUri);
            if (byUri instanceof Map) {
                return (Map<String, Object>) byUri;
            }
        }
        Object byAlias = eventsByAlias.get(aliasOrUri);
        if (byAlias instanceof Map) {
            return (Map<String, Object>) byAlias;
        }
        // RFC 8417 §2.2 says the payload is an object. Anything else is non-
        // compliant; surface as null rather than throwing on cast.
        return null;
    }
}
