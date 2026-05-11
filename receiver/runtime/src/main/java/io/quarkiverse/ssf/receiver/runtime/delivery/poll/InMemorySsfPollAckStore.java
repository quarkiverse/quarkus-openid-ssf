package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

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
