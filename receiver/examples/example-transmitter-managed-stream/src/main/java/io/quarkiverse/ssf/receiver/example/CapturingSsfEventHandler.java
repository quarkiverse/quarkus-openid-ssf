package io.quarkiverse.ssf.receiver.example;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.jboss.logging.Logger;

/**
 * The application's {@link SsfEventHandler}: keeps the last 50 SETs for the
 * {@code /events} endpoints. The handler runs before the transmitter gets its
 * {@code 202} (PUSH) or acknowledgement (POLL); throwing would make the transmitter
 * deliver the SET again.
 */
@ApplicationScoped
public class CapturingSsfEventHandler implements SsfEventHandler {

    private static final Logger LOG = Logger.getLogger(CapturingSsfEventHandler.class);

    private static final int CAPACITY = 50;

    private final ConcurrentLinkedDeque<CapturedEvent> events = new ConcurrentLinkedDeque<>();

    @Inject
    SsfTransmitters transmitters;

    @Override
    public void handle(SsfEventContext eventContext) {
        CapturedEvent captured = CapturedEvent.of(eventContext.eventToken(),
                transmitters.nameOf(eventContext.eventToken().iss()));
        LOG.infof("Captured SSF event jti=%s transmitter=%s iat=%s aud=%s txn=%s subject=%s events=%s",
                captured.jti(),
                captured.transmitter(),
                captured.iat(),
                captured.aud(),
                captured.txn(),
                eventContext.subject().raw(),
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
