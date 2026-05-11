package io.quarkiverse.ssf.receiver.runtime.stream;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Holds the {@code stream_id} the receiver-managed registrar discovered (or created)
 * on startup. {@link SsfStreamClient} consults this before falling back to the static
 * {@code quarkus.openid-ssf.receiver.stream-id} property, so receiver-managed apps don't have to know
 * their stream id ahead of time.
 *
 * <p>
 * Always present in the bean container regardless of {@code stream-management} mode;
 * it stays empty when the transmitter owns the stream.
 */
@ApplicationScoped
public class ReceiverManagedStreamState {

    private volatile String streamId;

    public Optional<String> streamId() {
        return Optional.ofNullable(streamId);
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public void clear() {
        this.streamId = null;
    }
}
