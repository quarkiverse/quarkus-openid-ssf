package io.quarkiverse.ssf.receiver.runtime.dedup;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventToken;
import io.quarkus.arc.DefaultBean;

/**
 * Default {@link SsfJtiDedupStore} — a bounded, FIFO-evicting in-memory map.
 * Capacity is configurable via {@code quarkus.openid-ssf.receiver.dedup.capacity} (default
 * {@code 10_000}); on overflow the oldest entry is evicted, so the dedup
 * window is approximate.
 *
 * <p>
 * Lost on restart — at-least-once semantics still apply across receiver
 * restarts. Consumers that need exactly-once across restarts should implement
 * the SPI against a durable store (database, Redis, …).
 */
@ApplicationScoped
@DefaultBean
public class InMemorySsfJtiDedupStore implements SsfJtiDedupStore {

    @Inject
    SsfReceiverConfig config;

    private LinkedHashMap<String, Boolean> seen;

    @PostConstruct
    void init() {
        // The configured value is the eviction threshold (clamped to at least 1).
        // The initial-capacity argument to LinkedHashMap is just a hash-table
        // sizing hint — floored at 16 so tiny configured values don't thrash
        // the bucket array, but doesn't affect the eviction policy.
        int capacity = Math.max(1, config.dedup().capacity());
        int initialCapacity = Math.max(16, capacity);
        // access-order = false → insertion-order; oldest entry evicted on overflow.
        // Synchronized externally on every call; no concurrent map needed.
        seen = new LinkedHashMap<>(initialCapacity, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    @Override
    public synchronized boolean seenBefore(SsfEventToken event) {
        if (event == null) {
            return false;
        }
        String key = dedupKey(event);
        if (key == null) {
            // Verifier already rejects SETs with no jti; guard defensively.
            return false;
        }
        return seen.put(key, Boolean.TRUE) != null;
    }

    /**
     * Composite identity key — {@code iss::jti}. RFC 8417 §2.2 scopes jti
     * uniqueness to the issuer, so a receiver consuming from multiple
     * transmitters that happen to mint colliding jti values stays correct.
     */
    private static String dedupKey(SsfEventToken event) {
        if (event.jti() == null || event.jti().isBlank()) {
            return null;
        }
        String iss = event.iss() == null ? "" : event.iss();
        return iss + "::" + event.jti();
    }

    @Override
    public synchronized int size() {
        return seen.size();
    }
}
