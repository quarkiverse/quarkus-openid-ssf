package io.quarkiverse.ssf.receiver.runtime.event;

import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.jboss.logging.Logger;

import io.quarkus.arc.DefaultBean;

/**
 * The handler in effect while the application has no {@link SsfEventHandler} bean of its
 * own: logs every SET at INFO.
 */
@Singleton
@DefaultBean
public class LoggingSsfEventHandler implements SsfEventHandler {

    private static final Logger LOG = Logger.getLogger(LoggingSsfEventHandler.class);

    @Override
    public void handle(SsfEventContext eventContext) {
        SsfEventToken eventToken = eventContext.eventToken();
        LOG.infof("SSF event received jti=%s iss=%s iat=%s events=%s", eventToken.jti(), eventToken.iss(),
                eventToken.iat(), eventContext.eventTypes().stream().map(SsfEventTypes::aliasOf).toList());
    }
}
