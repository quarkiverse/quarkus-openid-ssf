package io.quarkiverse.ssf.receiver.example.resourceserver;

import jakarta.enterprise.context.ApplicationScoped;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubject;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.jboss.logging.Logger;

/**
 * An application specific handler. It is invoked for every verified SET, in addition to
 * the handler of {@link TokenRevocation} that revokes the access tokens.
 */
@ApplicationScoped
public class AuditingSsfEventHandler implements SsfEventHandler {

    private static final Logger LOG = Logger.getLogger(AuditingSsfEventHandler.class);

    @Override
    public void handle(SsfEventContext eventContext) {
        for (String eventType : eventContext.eventTypes()) {
            SsfSubject subject = eventContext.subjectFor(eventType);
            LOG.infof("Security event %s for user %s and session %s: %s", SsfEventTypes.aliasOf(eventType),
                    subject.subject(), subject.sessionId(), eventContext.eventFor(eventType));
        }
    }
}
