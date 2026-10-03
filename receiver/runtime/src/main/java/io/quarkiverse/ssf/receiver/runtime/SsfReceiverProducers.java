package io.quarkiverse.ssf.receiver.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.push.SsfPushHandler;
import org.easyssf.receiver.set.SsfJtiDedupStore;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetVerifier;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.auth.OidcTransmitterTokenProviders;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventAliases;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.runtime.configuration.ConfigurationException;

/**
 * Wires the easyssf receiver into CDI. Every producer is a {@link DefaultBean}: an
 * application bean of the same type replaces it.
 *
 * <ul>
 * <li>{@link SsfHttpClient}: the JDK HTTP client with the {@code http.*} timeouts.</li>
 * <li>{@link SsfReceiverMetrics}: records nothing; {@code quarkus-micrometer} swaps in
 * the Micrometer implementation.</li>
 * <li>{@link SsfSetProcessor}: verifies, de-duplicates and dispatches SETs of every
 * transmitter to the {@link SsfEventHandler} beans.</li>
 * <li>{@link SsfPushHandler}: handles push requests, authenticating each transmitter
 * with its {@code push.expected-auth-header}.</li>
 * <li>{@link SsfTransmitters}: one {@link SsfTransmitter} per configured transmitter,
 * built by {@link SsfTransmitterFactory} and customized by the
 * {@link SsfTransmitterCustomizer} beans.</li>
 * </ul>
 */
@Singleton
public class SsfReceiverProducers {

    private static final Logger LOG = Logger.getLogger(SsfReceiverProducers.class);

    @Inject
    SsfReceiverConfig config;

    @PostConstruct
    void registerEventAliases() {
        SsfEventAliases.register(config);
    }

    @Produces
    @Singleton
    @DefaultBean
    public SsfHttpClient httpClient() {
        SsfReceiverConfig.Http http = config.http();
        JdkSsfHttpClient httpClient = new JdkSsfHttpClient(http.connectTimeout(), http.readTimeout());
        httpClient.setUserAgent(http.userAgent().orElse(null));
        return httpClient;
    }

    @Produces
    @Singleton
    @DefaultBean
    public SsfReceiverMetrics metrics() {
        return SsfReceiverMetrics.NOOP;
    }

    /**
     * Processes the SETs of every transmitter: the issuer of a SET selects the
     * transmitter that verifies it. The transmitters are looked up when the first SET
     * arrives, as the pollers of the transmitters need the processor.
     */
    @Produces
    @Singleton
    @DefaultBean
    public SsfSetProcessor setProcessor(Instance<SsfTransmitters> transmitters, Instance<SsfJtiDedupStore> dedupStore,
            InjectableInstance<SsfEventHandler> handlers, SsfReceiverMetrics metrics) {
        SsfJtiDedupStore store = config.dedup().enabled() ? dedupStore.get() : null;
        SsfSetVerifier verifier = encodedSet -> transmitters.get().verifier().verify(encodedSet);
        SsfSetProcessor processor = new SsfSetProcessor(verifier, store, orderedHandlers(handlers));
        processor.setMetrics(metrics);
        processor.setStreamVerifications(issuer -> transmitters.get().streamVerification(issuer));
        return processor;
    }

    @Produces
    @Singleton
    @DefaultBean
    public SsfPushHandler pushHandler(SsfSetProcessor processor, Instance<SsfTransmitters> transmitters,
            SsfReceiverMetrics metrics) {
        SsfPushHandler handler = new SsfPushHandler(processor, issuer -> transmitters.get().pushAuthorizationHeader(issuer));
        handler.setMetrics(metrics);
        return handler;
    }

    @Produces
    @Singleton
    @DefaultBean
    public SsfTransmitters transmitters(SsfHttpClient httpClient, SsfSetProcessor processor,
            SsfReceiverMetrics metrics, Instance<OidcTransmitterTokenProviders> oidcProviders,
            Instance<SsfTransmitterCustomizer> customizers) {
        Map<String, SsfTransmitterConfig> configured = config.configuredTransmitters();
        if (configured.isEmpty()) {
            throw new ConfigurationException("quarkus.openid-ssf.receiver.transmitter-issuer: the issuer of the SSF "
                    + "transmitter must be configured, or transmitters under quarkus.openid-ssf.receiver.<name>. Set "
                    + "quarkus.openid-ssf.receiver.enabled=false to switch the receiver off.");
        }
        OidcTransmitterTokenProviders oidc = oidcProviders.isResolvable() ? oidcProviders.get() : null;
        List<SsfTransmitterCustomizer> customizerList = customizers.stream().toList();
        List<SsfTransmitter> transmitters = new ArrayList<>();
        configured.forEach((name, transmitterConfig) -> {
            SsfTransmitter.Builder builder = SsfTransmitterFactory.builder(name, transmitterConfig, httpClient,
                    processor, metrics, oidc);
            customizerList.forEach(customizer -> customizer.customize(builder));
            transmitters.add(builder.build());
        });
        try {
            SsfTransmitters all = new SsfTransmitters(transmitters);
            if (all.all().size() > 1) {
                LOG.infof("SSF transmitters: %s", all.all());
            }
            return all;
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("quarkus.openid-ssf.receiver: " + e.getMessage(), e);
        }
    }

    /**
     * The handler beans, those with a higher {@code @Priority} first; the order of beans
     * without one is unspecified.
     */
    private static List<SsfEventHandler> orderedHandlers(InjectableInstance<SsfEventHandler> handlers) {
        List<InstanceHandle<SsfEventHandler>> handles = new ArrayList<>();
        handlers.handles().forEach(handles::add);
        handles.sort(Comparator.comparingInt((InstanceHandle<SsfEventHandler> h) -> h.getBean().getPriority())
                .reversed());
        return handles.stream().map(InstanceHandle::get).toList();
    }
}
