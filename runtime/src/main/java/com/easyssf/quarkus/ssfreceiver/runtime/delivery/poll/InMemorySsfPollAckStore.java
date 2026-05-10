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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;

/**
 * Default {@link SsfPollAckStore} — a concurrent in-memory deque. Pending acks
 * are lost on restart, which means the transmitter will redeliver any SET we
 * processed but didn't get to ack before going down. Per RFC 8936 §2.3 that's
 * the expected at-least-once behavior; consumers that need exactly-once should
 * back the SPI with durable storage.
 */
@ApplicationScoped
@DefaultBean
public class InMemorySsfPollAckStore implements SsfPollAckStore {

    private final ConcurrentLinkedDeque<String> pending = new ConcurrentLinkedDeque<>();

    @Override
    public void enqueueAck(String jti) {
        if (jti != null && !jti.isBlank()) {
            pending.offer(jti);
        }
    }

    @Override
    public List<String> drainAcks() {
        List<String> drained = new ArrayList<>();
        for (String jti; (jti = pending.poll()) != null;) {
            drained.add(jti);
        }
        return drained;
    }

    @Override
    public void requeueAcks(Collection<String> jtis) {
        if (jtis == null || jtis.isEmpty()) {
            return;
        }
        // Push to the front so re-queued items are sent ahead of newly enqueued ones —
        // failed sends should be retried before fresh acks are batched in.
        List<String> reversed = new ArrayList<>(jtis);
        for (int i = reversed.size() - 1; i >= 0; i--) {
            String jti = reversed.get(i);
            if (jti != null && !jti.isBlank()) {
                pending.offerFirst(jti);
            }
        }
    }

    @Override
    public int size() {
        return pending.size();
    }
}
