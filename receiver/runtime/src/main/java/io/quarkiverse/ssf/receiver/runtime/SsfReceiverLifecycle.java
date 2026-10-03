package io.quarkiverse.ssf.receiver.runtime;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.easyssf.receiver.jdbc.JdbcSsfStoreCleanup;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.event.SsfEventAliases;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * Starts the background work of the receiver once the application is up and stops it on
 * shutdown: looking up or registering the streams of the transmitters, and deleting them
 * on shutdown when asked to. Polling is driven by the {@code SsfPollScheduler}.
 *
 * <p>
 * Building the transmitters here, at startup, makes a configuration error fail the
 * start instead of the first SET.
 */
@ApplicationScoped
public class SsfReceiverLifecycle {

    private static final Logger LOG = Logger.getLogger(SsfReceiverLifecycle.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    Instance<SsfTransmitters> transmitters;

    @Inject
    Instance<JdbcSsfStoreCleanup> storeCleanup;

    private volatile SsfTransmitters started;

    void onStart(@Observes @Priority(200) StartupEvent event) {
        if (!config.enabled()) {
            LOG.info("quarkus.openid-ssf.receiver.enabled=false: the SSF receiver is disabled, no automatic activity will run");
            return;
        }
        SsfEventAliases.register(config);
        SsfTransmitters all = transmitters.get();
        all.start();
        started = all;
        all.all().forEach(this::resolveMetadataInBackground);
        if (storeCleanup.isResolvable()) {
            JdbcSsfStoreCleanup cleanup = storeCleanup.get();
            if (cleanup.isEnabled()) {
                cleanup.start();
                LOG.infof("Expired rows of the SSF receiver tables are purged every %s", config.jdbc().cleanupInterval());
            }
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        SsfTransmitters all = started;
        started = null;
        if (all != null) {
            all.stop();
        }
        if (storeCleanup.isResolvable()) {
            storeCleanup.get().stop();
        }
    }

    /**
     * Retrieves the metadata of a transmitter once at startup, so that a transmitter that
     * cannot be reached shows up in the log before the first SET. Done in the background:
     * easyssf retrieves the metadata lazily anyway, and an unreachable transmitter must
     * not hold the start up.
     */
    private void resolveMetadataInBackground(SsfTransmitter transmitter) {
        Thread.ofVirtual().name("ssf-metadata-" + transmitter.getName()).start(() -> {
            try {
                transmitter.getMetadataResolver().resolve();
            } catch (RuntimeException e) {
                LOG.warnf("SSF transmitter %s: %s (retried when the first SET arrives)", transmitter, e.getMessage());
                LOG.debugf(e, "Cause of the failed SSF metadata retrieval");
            }
        });
    }
}
