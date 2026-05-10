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

import java.util.Collection;
import java.util.List;

/**
 * SPI for tracking SET {@code jti}s the receiver has processed and intends to
 * acknowledge on its next poll request — RFC 8936 §2.1.
 *
 * <p>
 * The default implementation, {@link InMemorySsfPollAckStore}, is a simple
 * concurrent queue that's lost on restart. Consumers that need durability
 * (so unacked SETs aren't redelivered after a restart) can provide their own
 * {@code @ApplicationScoped} bean — backed by a database, Redis, the
 * filesystem, etc. Any non-{@code @DefaultBean} implementation displaces the
 * built-in one without further wiring.
 */
public interface SsfPollAckStore {

    /** Marks a {@code jti} as eligible for inclusion in the next poll's ack list. */
    void enqueueAck(String jti);

    /**
     * Removes and returns all currently-pending {@code jti}s. The poller calls
     * this immediately before sending a poll request and is responsible for
     * calling {@link #requeueAcks(Collection)} if the request fails so the
     * acks aren't lost.
     */
    List<String> drainAcks();

    /**
     * Puts {@code jti}s back into the store — typically after a failed poll
     * request — so they're included on the next attempt. Order vs. newly
     * enqueued items is implementation-defined.
     */
    void requeueAcks(Collection<String> jtis);

    /**
     * Number of acks currently waiting to be sent. Used by the metrics gauge
     * {@code ssf.receiver.poll.ack.queue.depth}; a sustained backlog usually
     * means the transmitter is unreachable or rejecting poll requests.
     *
     * <p>
     * Implementations that can't cheaply size their backing store may
     * return an approximation or {@code -1} to mean "unknown".
     */
    int size();
}
