package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.SsfTransmitterConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;

/**
 * Drives the pollers of the transmitters with POLL delivery (RFC 8936): a Vert.x
 * periodic timer per transmitter runs {@link SsfPoller#pollNow()} on a virtual thread
 * every {@code poll.interval}, after {@code poll.start-delay}. A tick is skipped while a
 * poll is still running. With {@code poll.auto-start=false} no timer is scheduled and
 * the application polls by calling {@link #pollNow()}.
 */
@ApplicationScoped
public class SsfPollScheduler {

    private static final Logger LOG = Logger.getLogger(SsfPollScheduler.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    Vertx vertx;

    @Inject
    Instance<SsfTransmitters> transmitters;

    private final List<Long> timers = new ArrayList<>();

    void onStart(@Observes @Priority(300) StartupEvent event) {
        if (!config.enabled()) {
            return;
        }
        Map<String, SsfTransmitterConfig> configured = config.configuredTransmitters();
        for (SsfTransmitter transmitter : transmitters.get().all()) {
            SsfPoller poller = transmitter.getPoller();
            SsfTransmitterConfig transmitterConfig = configured.get(transmitter.getName());
            if (poller == null || transmitterConfig == null) {
                continue;
            }
            SsfTransmitterConfig.Poll poll = transmitterConfig.poll();
            if (!poll.autoStart()) {
                LOG.infof("SSF transmitter %s: poll.auto-start=false, polling is driven by the application", transmitter);
                continue;
            }
            long initialDelay = Math.max(1L, poll.startDelay().toMillis());
            long interval = Math.max(1L, poll.interval().toMillis());
            LOG.infof("SSF transmitter %s: polling every %s (start delay %s, max %d SETs per request)", transmitter,
                    poll.interval(), poll.startDelay(), poll.maxEvents());
            synchronized (timers) {
                timers.add(vertx.setPeriodic(initialDelay, interval, id -> poll(transmitter)));
            }
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        synchronized (timers) {
            timers.forEach(vertx::cancelTimer);
            timers.clear();
        }
    }

    /**
     * Polls the default transmitter once, synchronously, and returns the number of SETs
     * fetched. {@code 0} as well if the poll endpoint is not known yet, the transmitter
     * asked to slow down or a poll is in progress.
     *
     * @throws IllegalStateException if the receiver is disabled, has no default
     *         transmitter or the transmitter does not deliver by POLL
     */
    public int pollNow() {
        SsfTransmitter transmitter = ensureEnabled().primary()
                .orElseThrow(() -> new IllegalStateException("There is no default SSF transmitter to poll, name one"));
        return pollNow(transmitter);
    }

    /**
     * Polls the named transmitter once, synchronously.
     *
     * @see #pollNow()
     */
    public int pollNow(String transmitterName) {
        SsfTransmitter transmitter = ensureEnabled().get(transmitterName)
                .orElseThrow(() -> new IllegalStateException("There is no SSF transmitter named '" + transmitterName + "'"));
        return pollNow(transmitter);
    }

    private int pollNow(SsfTransmitter transmitter) {
        SsfPoller poller = transmitter.getPoller();
        if (poller == null) {
            throw new IllegalStateException(
                    "The SSF transmitter " + transmitter + " does not deliver by POLL (delivery-method)");
        }
        return poller.pollNow();
    }

    private SsfTransmitters ensureEnabled() {
        if (!config.enabled()) {
            throw new IllegalStateException(
                    "The SSF receiver is disabled (quarkus.openid-ssf.receiver.enabled=false), nothing to poll");
        }
        return transmitters.get();
    }

    private void poll(SsfTransmitter transmitter) {
        Thread.ofVirtual().name("ssf-poll-" + transmitter.getName()).start(() -> {
            try {
                transmitter.getPoller().pollNow();
            } catch (RuntimeException e) {
                LOG.warnf("Could not poll the SSF transmitter %s: %s", transmitter, e.getMessage());
                LOG.debugf(e, "Cause of the failed poll");
            }
        });
    }
}
