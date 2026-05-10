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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfAliases;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventHandler;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventToken;

@ApplicationScoped
public class CapturingSsfEventHandler implements SsfEventHandler {

    private static final Logger LOG = Logger.getLogger(CapturingSsfEventHandler.class);

    private static final int CAPACITY = 50;

    private final ConcurrentLinkedDeque<CapturedEvent> events = new ConcurrentLinkedDeque<>();

    @Inject
    SsfAliases aliases;

    @Override
    public void handle(SsfEventToken event) {
        CapturedEvent captured = CapturedEvent.of(event, aliases);
        LOG.infof("Captured SSF event jti=%s iss=%s iat=%s aud=%s txn=%s subjectId=%s events=%s",
                captured.jti(),
                captured.issAlias(),
                captured.iat(),
                captured.aud(),
                captured.txn(),
                captured.subjectId(),
                captured.events());
        events.addFirst(captured);
        while (events.size() > CAPACITY) {
            events.pollLast();
        }
    }

    public Optional<CapturedEvent> latestEvent() {
        return events.stream().findFirst();
    }

    public List<CapturedEvent> recentEvents() {
        return List.copyOf(events);
    }
}
