package io.quarkiverse.ssf.receiver.runtime.event;

import java.util.List;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class SsfReceiverStartupValidator {

    private static final Logger LOG = Logger.getLogger(SsfReceiverStartupValidator.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    SsfAliases aliases;

    void onStart(@Observes @Priority(100) StartupEvent event) {
        if (!config.enabled()) {
            // Single boot log line; the other observers stay silent when disabled
            // so we don't spam five "skipping" lines for the same kill switch.
            LOG.infof("quarkus.openid-ssf.receiver.enabled=false — SSF receiver is disabled, no automatic activity will run");
            return;
        }
        switch (config.streamManagement()) {
            case TRANSMITTER -> {
                if (config.streamId().isEmpty()) {
                    throw new IllegalStateException(
                            "quarkus.openid-ssf.receiver.stream-id is required when stream-management=TRANSMITTER");
                }
            }
            case RECEIVER -> {
                if (config.deliveryMethod() == SsfReceiverConfig.DeliveryMethod.PUSH
                        && config.push().deliveryEndpointUrl().isEmpty()) {
                    throw new IllegalStateException(
                            "quarkus.openid-ssf.receiver.push.delivery-endpoint-url is required when "
                                    + "stream-management=RECEIVER and delivery-method=PUSH");
                }
                if (config.receiverManaged().registerStream()
                        && config.streamId().isEmpty()
                        && config.eventsRequested().map(List::isEmpty).orElse(true)) {
                    throw new IllegalStateException(
                            "quarkus.openid-ssf.receiver.events-requested must list at least one event URI or alias when "
                                    + "stream-management=RECEIVER and the extension is asked to register a stream");
                }
            }
        }
        // Resolve every events-requested entry up-front (regardless of mode)
        // so a typo in an alias name fails immediately at startup with a
        // helpful message, rather than silently being sent to the transmitter
        // as a non-URI string. Resolution is cheap and idempotent.
        config.eventsRequested().ifPresent(this::validateEventsRequested);
    }

    private void validateEventsRequested(List<String> entries) {
        for (String entry : entries) {
            try {
                aliases.resolveEventTypeRef(entry);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "quarkus.openid-ssf.receiver.events-requested contains an invalid entry: " + e.getMessage(), e);
            }
        }
    }
}
