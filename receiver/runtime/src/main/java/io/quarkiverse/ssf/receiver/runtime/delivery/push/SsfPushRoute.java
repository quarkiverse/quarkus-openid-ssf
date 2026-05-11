package io.quarkiverse.ssf.receiver.runtime.delivery.push;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.dedup.SsfJtiDedupStore;
import io.quarkiverse.ssf.receiver.runtime.event.SsfAliases;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventContext;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventHandler;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventToken;
import io.quarkiverse.ssf.receiver.runtime.metrics.SsfReceiverMetrics;
import io.quarkiverse.ssf.receiver.runtime.metrics.SsfReceiverMetrics.DeliverySource;
import io.quarkiverse.ssf.receiver.runtime.metrics.SsfReceiverMetrics.PushRejectReason;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

@ApplicationScoped
public class SsfPushRoute {

    private static final Logger LOG = Logger.getLogger(SsfPushRoute.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    SetVerifier verifier;

    @Inject
    SsfEventHandler handler;

    @Inject
    SsfReceiverMetrics metrics;

    @Inject
    SsfJtiDedupStore dedupStore;

    @Inject
    SsfAliases aliases;

    private final ExecutorService dispatchExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory());

    public void registerRoute(@Observes Router router) {
        if (!config.enabled()) {
            return;
        }
        if (config.deliveryMethod() != SsfReceiverConfig.DeliveryMethod.PUSH) {
            LOG.debugf("delivery-method=%s — not registering SSF push endpoint", config.deliveryMethod());
            return;
        }
        String path = config.push().endpointPath();
        LOG.infof("Registering SSF push endpoint at %s", path);
        router.post(path)
                .handler(BodyHandler.create())
                .handler(this::handle);
    }

    @PreDestroy
    void shutdown() {
        dispatchExecutor.shutdown();
    }

    void handle(RoutingContext ctx) {
        var expectedAuth = config.push().expectedAuthHeader();
        if (expectedAuth.isPresent()) {
            String actual = ctx.request().getHeader("Authorization");
            if (!expectedAuth.get().equals(actual)) {
                metrics.pushRejected(PushRejectReason.AUTH);
                ctx.response().setStatusCode(401).end();
                return;
            }
        }

        String body = ctx.body() != null ? ctx.body().asString() : null;
        if (body == null || body.isBlank()) {
            metrics.pushRejected(PushRejectReason.BODY);
            ctx.response().setStatusCode(400).end();
            return;
        }

        SsfEventToken event;
        try {
            event = verifier.verify(body);
        } catch (SsfVerificationException e) {
            // Log the cause chain at DEBUG too — operator-visible 400s without
            // a cause leave no trail (e.g. metadata fetch failure, network error).
            if (LOG.isDebugEnabled()) {
                LOG.debugf(e, "Rejecting SET: %s", e.getMessage());
            }
            metrics.pushRejected(PushRejectReason.VERIFY);
            ctx.response().setStatusCode(400).end();
            return;
        }

        metrics.pushAccepted();

        // jti dedup — drop silently if we've already dispatched this SET.
        // Still respond 202 (the SET WAS successfully received and verified;
        // we just won't deliver it to the handler twice).
        if (config.dedup().enabled() && dedupStore.seenBefore(event)) {
            LOG.debugf("Skipping duplicate SET jti=%s iss=%s (push)", event.jti(), event.iss());
            metrics.dedupSkipped(DeliverySource.PUSH);
            ctx.response().setStatusCode(202).end();
            return;
        }

        recordEventTypes(event);

        // Build the alias-resolved view once, so the handler's hasEvent / payloadFor
        // calls are O(1) and the configured aliases are visible without each handler
        // having to inject SsfAliases itself.
        SsfEventContext eventContext = SsfEventContext.of(event, aliases);

        // SET has been accepted; dispatch handler asynchronously so handler exceptions
        // don't turn the response into a 5xx.
        dispatchExecutor.execute(() -> {
            try {
                handler.handle(eventContext);
            } catch (RuntimeException e) {
                metrics.pushHandlerError();
                LOG.errorf(e, "SsfEventHandler threw for jti=%s", event.jti());
            }
        });

        ctx.response().setStatusCode(202).end();
    }

    private void recordEventTypes(SsfEventToken event) {
        if (event.events() == null || event.events().isEmpty()) {
            return;
        }
        // RFC 8417 §2.2: a SET's `events` is keyed by event-type URI. Count once per type.
        for (String eventTypeUri : event.events().keySet()) {
            metrics.eventProcessed(eventTypeUri, event.iss(), DeliverySource.PUSH);
        }
    }

    private static ThreadFactory virtualThreadFactory() {
        return Thread.ofVirtual().name("ssf-push-dispatch-", 1).factory();
    }
}
