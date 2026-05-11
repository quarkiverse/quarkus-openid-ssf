package io.quarkiverse.ssf.receiver.example;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.event.SsfAliases;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventContext;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventHandler;

@ApplicationScoped
public class CapturingSsfEventHandler implements SsfEventHandler {

    private static final Logger LOG = Logger.getLogger(CapturingSsfEventHandler.class);

    private static final int CAPACITY = 50;

    private final ConcurrentLinkedDeque<CapturedEvent> events = new ConcurrentLinkedDeque<>();

    @Inject
    SsfAliases aliases;

    @Override
    public void handle(SsfEventContext eventContext) {
        CapturedEvent captured = CapturedEvent.of(eventContext.eventToken(), aliases);
        LOG.infof("Captured SSF event jti=%s iss=%s iat=%s aud=%s txn=%s subjectId=%s payloads=%s",
                captured.jti(),
                captured.issAlias(),
                captured.iat(),
                captured.aud(),
                captured.txn(),
                captured.subjectId(),
                captured.events());
        events.addFirst(captured);
        while (events.size() > CAPACITY) {
            events.pollLast();
        }
    }

    public Optional<CapturedEvent> latestEvent() {
        return events.stream().findFirst();
    }

    public List<CapturedEvent> recentEvents() {
        return List.copyOf(events);
    }
}
