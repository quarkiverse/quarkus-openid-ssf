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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemorySsfPollAckStoreTest {

    @Test
    @DisplayName("Empty store drains to an empty list")
    void emptyDrain() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();
        assertThat(store.drainAcks(), is(empty()));
        assertThat(store.size(), equalTo(0));
    }

    @Test
    @DisplayName("Drain returns acks in FIFO order — the order they were enqueued")
    void drainPreservesFifoOrder() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();
        store.enqueueAck("first");
        store.enqueueAck("second");
        store.enqueueAck("third");

        assertThat(store.drainAcks(), contains("first", "second", "third"));
        assertThat(store.size(), equalTo(0));
    }

    @Test
    @DisplayName("Drain is destructive — second drain returns empty")
    void drainIsDestructive() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();
        store.enqueueAck("only");
        store.drainAcks();

        assertThat(store.drainAcks(), is(empty()));
        assertThat(store.size(), equalTo(0));
    }

    @Test
    @DisplayName("Re-queued acks land at the front, ahead of newly enqueued ones")
    void requeuePushesToFront() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();

        // Simulate: drain returned [a, b, c]; the poll request failed; new
        // event 'd' arrived in the meantime; now we requeue [a, b, c].
        // Next drain must send the previously-failed acks first to keep
        // the transmitter's cursor advancing in order.
        store.enqueueAck("d");
        store.requeueAcks(List.of("a", "b", "c"));

        assertThat(store.drainAcks(), contains("a", "b", "c", "d"));
    }

    @Test
    @DisplayName("Requeue preserves the input list's relative order")
    void requeuePreservesRelativeOrder() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();
        store.requeueAcks(List.of("a", "b", "c"));

        // Should drain in the same order they were originally drained.
        assertThat(store.drainAcks(), contains("a", "b", "c"));
    }

    @Test
    @DisplayName("Null / blank jti's are silently dropped on enqueue")
    void enqueueRejectsNullAndBlank() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();
        store.enqueueAck(null);
        store.enqueueAck("");
        store.enqueueAck("   ");
        store.enqueueAck("real");

        assertThat(store.drainAcks(), contains("real"));
    }

    @Test
    @DisplayName("Null / empty requeue is a no-op")
    void requeueNoOpOnEmpty() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();
        store.enqueueAck("real");

        store.requeueAcks(null);
        store.requeueAcks(List.of());

        assertThat(store.drainAcks(), contains("real"));
    }

    @Test
    @DisplayName("Requeue filters out null / blank entries (defensive)")
    void requeueFiltersInvalid() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();
        store.requeueAcks(java.util.Arrays.asList("a", null, "", "   ", "b"));

        assertThat(store.drainAcks(), contains("a", "b"));
    }

    @Test
    @DisplayName("size() reflects pending entries between enqueue and drain")
    void sizeTracksPending() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();
        assertThat(store.size(), equalTo(0));

        store.enqueueAck("a");
        store.enqueueAck("b");
        assertThat(store.size(), equalTo(2));

        store.drainAcks();
        assertThat(store.size(), equalTo(0));
    }
}
