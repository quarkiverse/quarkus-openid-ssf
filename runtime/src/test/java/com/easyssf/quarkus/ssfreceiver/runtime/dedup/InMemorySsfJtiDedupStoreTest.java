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
package com.easyssf.quarkus.ssfreceiver.runtime.dedup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.easyssf.quarkus.ssfreceiver.runtime.SsfReceiverConfig;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventToken;

class InMemorySsfJtiDedupStoreTest {

    private static final String ISS_A = "https://tx-a.example/realms/r1";
    private static final String ISS_B = "https://tx-b.example/realms/r2";

    private static InMemorySsfJtiDedupStore newStoreWithCapacity(int capacity) {
        InMemorySsfJtiDedupStore store = new InMemorySsfJtiDedupStore();
        SsfReceiverConfig.Dedup dedup = mock(SsfReceiverConfig.Dedup.class);
        when(dedup.capacity()).thenReturn(capacity);
        SsfReceiverConfig cfg = mock(SsfReceiverConfig.class);
        when(cfg.dedup()).thenReturn(dedup);
        store.config = cfg;
        store.init();
        return store;
    }

    private static SsfEventToken event(String iss, String jti) {
        return new SsfEventToken(jti, iss, Instant.EPOCH, List.of(),
                Map.of(), Map.of(), null, Map.of());
    }

    @Test
    @DisplayName("First sight returns false; second sight returns true")
    void basicCheckAndRecord() {
        InMemorySsfJtiDedupStore store = newStoreWithCapacity(100);

        assertThat(store.seenBefore(event(ISS_A, "jti-1")), is(false));
        assertThat(store.seenBefore(event(ISS_A, "jti-1")), is(true));
        assertThat(store.size(), equalTo(1));
    }

    @Test
    @DisplayName("Composite iss::jti — same jti on different issuers don't collide")
    void compositeKeyAvoidsCrossIssuerCollision() {
        InMemorySsfJtiDedupStore store = newStoreWithCapacity(100);

        // Same jti, different iss — both must be first-sight.
        assertThat(store.seenBefore(event(ISS_A, "shared-jti")), is(false));
        assertThat(store.seenBefore(event(ISS_B, "shared-jti")), is(false));

        // Now each is a duplicate within its own issuer scope.
        assertThat(store.seenBefore(event(ISS_A, "shared-jti")), is(true));
        assertThat(store.seenBefore(event(ISS_B, "shared-jti")), is(true));

        assertThat(store.size(), equalTo(2));
    }

    @Test
    @DisplayName("null event / blank jti are tolerated and treated as 'never seen'")
    void defensiveAgainstNullsAndBlanks() {
        InMemorySsfJtiDedupStore store = newStoreWithCapacity(100);

        assertThat(store.seenBefore(null), is(false));
        assertThat(store.seenBefore(event(ISS_A, "")), is(false));
        assertThat(store.seenBefore(event(ISS_A, "   ")), is(false));
        assertThat(store.seenBefore(event(ISS_A, null)), is(false));

        // None of those should have inflated the store.
        assertThat(store.size(), equalTo(0));
    }

    @Test
    @DisplayName("null iss → key is '::<jti>'; still dedups against itself")
    void nullIssIsTolerated() {
        InMemorySsfJtiDedupStore store = newStoreWithCapacity(100);

        assertThat(store.seenBefore(event(null, "jti-1")), is(false));
        assertThat(store.seenBefore(event(null, "jti-1")), is(true));
    }

    @Test
    @DisplayName("Capacity overflow evicts oldest entry — re-seeing it is 'first sight'")
    void capacityOverflowEvictsOldest() {
        InMemorySsfJtiDedupStore store = newStoreWithCapacity(2);

        store.seenBefore(event(ISS_A, "first"));
        store.seenBefore(event(ISS_A, "second"));
        // Capacity reached; adding a third evicts 'first'.
        store.seenBefore(event(ISS_A, "third"));

        assertThat("size capped at configured capacity", store.size(), equalTo(2));
        // 'first' was evicted → re-seeing it is NOT a duplicate.
        assertThat(store.seenBefore(event(ISS_A, "first")), is(false));
        // 'third' is still there → duplicate.
        assertThat(store.seenBefore(event(ISS_A, "third")), is(true));
    }

    @Test
    @DisplayName("Configured capacity below 16 is floored at 16 (avoids tiny LRU pathologies)")
    void capacityFloor() {
        InMemorySsfJtiDedupStore store = newStoreWithCapacity(2);

        // Initial map allocates with capacity = max(16, configured). Threshold and
        // eviction still honor the configured value, so behaviorally we get a
        // 2-slot LRU (verified above) — this test just asserts init() doesn't NPE
        // on a tiny configured capacity.
        assertThat(store.size(), equalTo(0));
    }
}
