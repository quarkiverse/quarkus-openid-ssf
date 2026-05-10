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

import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventToken;

/**
 * SPI for tracking SETs the receiver has already dispatched, so a redelivery
 * (POLL retry, transmitter retry on transient failure, …) doesn't trigger the
 * application's {@code SsfEventHandler} a second time — RFC 8417 doesn't
 * define delivery-once semantics, so receivers are expected to do their own
 * dedup if their handler isn't naturally idempotent.
 *
 * <p>
 * Implementations receive the full verified {@link SsfEventToken} rather than
 * just the {@code jti} so they can:
 * <ul>
 * <li>Use a composite key — RFC 8417 §2.2 scopes {@code jti} uniqueness to
 * the issuer, so {@code iss + "::" + jti} is the most correct identity
 * when one receiver consumes from multiple transmitters.</li>
 * <li>Implement time-window heuristics on {@code iat} (e.g. ignore SETs
 * older than 24h regardless of jti).</li>
 * <li>Persist additional context for audit when storing duplicates.</li>
 * </ul>
 *
 * <p>
 * The default implementation, {@link InMemorySsfJtiDedupStore}, is a bounded
 * LRU that's lost on restart. Consumers that need durability (so duplicates
 * are detected across receiver restarts) provide their own
 * {@code @ApplicationScoped} bean — backed by a database, Redis, the
 * filesystem, etc. Any non-{@code @DefaultBean} implementation displaces the
 * built-in one without further wiring.
 *
 * <p>
 * The dedup check fires <em>after</em> the SET has been verified but
 * <em>before</em> {@code SsfEventHandler.handle(...)} inside the extension,
 * on both PUSH and POLL paths. Disabling dedup altogether is done via
 * {@code ssf.receiver.dedup.enabled=false}.
 */
public interface SsfJtiDedupStore {

    /**
     * Atomic check-and-record. Returns {@code true} if {@code event} was
     * already in the store (the SET is a duplicate and should be skipped);
     * returns {@code false} on first sight, after recording the event.
     *
     * <p>
     * Implementations MUST be safe to call from multiple threads.
     */
    boolean seenBefore(SsfEventToken event);

    /** Approximate number of entries currently tracked. {@code -1} means "unknown". */
    int size();
}
