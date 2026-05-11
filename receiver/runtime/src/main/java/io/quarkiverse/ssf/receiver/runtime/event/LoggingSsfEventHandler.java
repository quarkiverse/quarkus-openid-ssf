package io.quarkiverse.ssf.receiver.runtime.event;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.arc.DefaultBean;

@ApplicationScoped
@DefaultBean
public class LoggingSsfEventHandler implements SsfEventHandler {

    private static final Logger LOG = Logger.getLogger(LoggingSsfEventHandler.class);

    @Override
    public void handle(SsfEventContext eventContext) {
        SsfEventToken eventToken = eventContext.eventToken();
        LOG.infof("SSF event received jti=%s iss=%s iat=%s events=%s",
                eventToken.jti(),
                eventContext.issAlias(),
                eventToken.iat(),
                eventContext.eventsByAlias().keySet());
    }
}
