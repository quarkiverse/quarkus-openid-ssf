package io.quarkiverse.ssf.receiver.runtime.delivery.push;

import java.nio.charset.StandardCharsets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.easyssf.receiver.push.SsfPushHandler;
import org.easyssf.receiver.push.SsfPushResponse;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.SsfTransmitterConfig;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * The push endpoint (RFC 8935): a Vert.x route at {@code push.endpoint-path} that hands
 * every request to easyssf's {@link SsfPushHandler}. Registered when a configured
 * transmitter delivers by PUSH.
 *
 * <p>
 * A SET is verified and handled before the transmitter gets its {@code 202}: a failing
 * handler yields {@code 500} and the transmitter delivers the SET again. The handler runs
 * on a worker thread, as verifying a SET may fetch keys.
 */
@ApplicationScoped
public class SsfPushRoute {

    private static final Logger LOG = Logger.getLogger(SsfPushRoute.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    Instance<SsfPushHandler> pushHandler;

    public void registerRoute(@Observes Router router) {
        if (!config.enabled()) {
            return;
        }
        boolean push = config.configuredTransmitters().values().stream()
                .anyMatch(transmitter -> transmitter.deliveryMethod() == SsfTransmitterConfig.DeliveryMethod.PUSH);
        if (!push) {
            LOG.debug("No SSF transmitter delivers by PUSH, not registering the push endpoint");
            return;
        }
        String path = config.push().endpointPath();
        LOG.infof("Registering the SSF push endpoint at %s", path);
        router.post(path)
                .handler(BodyHandler.create().setBodyLimit(SsfPushHandler.MAX_SET_SIZE + 1L))
                .blockingHandler(this::handle);
    }

    void handle(RoutingContext ctx) {
        Buffer buffer = ctx.body().buffer();
        byte[] body = (buffer != null) ? buffer.getBytes() : new byte[0];
        SsfPushResponse response = pushHandler.get().handle(ctx.request().getHeader(HttpHeaders.AUTHORIZATION), body);
        ctx.response().setStatusCode(response.status());
        if (response.body() == null) {
            ctx.response().end();
            return;
        }
        byte[] content = response.body().getBytes(StandardCharsets.UTF_8);
        ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, SsfPushResponse.CONTENT_TYPE)
                .putHeader(HttpHeaders.CONTENT_LANGUAGE, SsfPushResponse.CONTENT_LANGUAGE)
                .end(Buffer.buffer(content));
    }
}
