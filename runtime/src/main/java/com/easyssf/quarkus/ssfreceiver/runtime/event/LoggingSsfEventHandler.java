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

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.arc.DefaultBean;

@ApplicationScoped
@DefaultBean
public class LoggingSsfEventHandler implements SsfEventHandler {

    private static final Logger LOG = Logger.getLogger(LoggingSsfEventHandler.class);

    @Inject
    SsfAliases aliases;

    @Override
    public void handle(SsfEventToken eventToken) {
        LOG.infof("SSF event received jti=%s iss=%s iat=%s events=%s",
                eventToken.jti(),
                aliases.issuerAlias(eventToken.iss()),
                eventToken.iat(),
                eventTypeAliases(eventToken.events()));
    }

    private List<String> eventTypeAliases(Map<String, Object> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.keySet().stream().map(aliases::eventTypeAlias).toList();
    }
}
