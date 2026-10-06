package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.easyssf.receiver.poll.InMemorySsfPollAckStore;
import org.easyssf.receiver.poll.SsfPollAckStore;

import io.quarkus.arc.DefaultBean;

/**
 * The {@link SsfPollAckStore} without a datasource: the acknowledgements a poller owes
 * its transmitter wait in memory until the next poll request, and are lost when the
 * application stops before one went out (the transmitter then delivers the SET again,
 * and it is skipped as a duplicate). An application bean of the type replaces it.
 */
@Singleton
public class InMemorySsfPollAckStoreProducer {

    @Produces
    @Singleton
    @DefaultBean
    public SsfPollAckStore ackStore() {
        return new InMemorySsfPollAckStore();
    }
}
