package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

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

/**
 * Starts and stops the pollers of the transmitters with POLL delivery (RFC 8936) with
 * the application. Each poller runs on a virtual thread of its own, named
 * {@code ssf-poller-<transmitter>}: it polls every {@code poll.interval} after
 * {@code poll.start-delay}, or keeps one request outstanding with
 * {@code poll.long-polling}, and sends the pending acknowledgements with a last request
 * when the application stops. With {@code poll.auto-start=false} the poller is not
 * started and the application polls by calling {@link #pollNow()}.
 *
 * <p>
 * The pollers are stopped before the stream registrars (which may delete the stream on
 * shutdown) and before the datasource closes, so that the last request can carry the
 * acknowledgements and remove them from a JDBC store.
 */
@ApplicationScoped
public class SsfPollScheduler {

    private static final Logger LOG = Logger.getLogger(SsfPollScheduler.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    Instance<SsfTransmitters> transmitters;

    private final List<SsfPoller> started = new ArrayList<>();

    /** a lock rather than synchronized, which pins virtual threads before JDK 24 */
    private final ReentrantLock lifecycle = new ReentrantLock();

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
            if (poller.isLongPolling()) {
                LOG.infof("SSF transmitter %s: long polling, a request is held for up to %s (start delay %s, max %d "
                        + "SETs per request)", transmitter, poll.longPollingHold(), poll.startDelay(), poll.maxEvents());
            } else {
                LOG.infof("SSF transmitter %s: polling every %s (start delay %s, max %d SETs per request)",
                        transmitter, poll.interval(), poll.startDelay(), poll.maxEvents());
            }
            lifecycle.lock();
            try {
                poller.start();
                started.add(poller);
            } finally {
                lifecycle.unlock();
            }
        }
    }

    /**
     * Stops the pollers ahead of the other shutdown observers ({@code SsfReceiverLifecycle}
     * stops the registrars at the default priority), as the last request has to reach
     * the stream before it is deleted.
     */
    void onStop(@Observes @Priority(100) ShutdownEvent event) {
        lifecycle.lock();
        try {
            started.forEach(SsfPoller::stop);
            started.clear();
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Polls the default transmitter once, synchronously, with a request answered
     * immediately, and returns the number of SETs fetched. {@code 0} as well if the poll
     * endpoint is not known yet, the transmitter asked to slow down or a poll is in
     * progress (with long polling, the started poller holds one most of the time).
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
}
