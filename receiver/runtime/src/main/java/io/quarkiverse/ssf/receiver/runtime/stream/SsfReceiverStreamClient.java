package io.quarkiverse.ssf.receiver.runtime.stream;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.core.stream.SsfStreamStatus;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamException;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.SsfTransmitterConfig;

/**
 * The stream management API (SSF 1.0, section 8.1) for the stream of this receiver at
 * the default transmitter, without having to pass the stream id around: the id is the
 * one the stream registrar looked up or created, or the configured {@code stream-id}.
 *
 * <p>
 * A thin convenience over easyssf: {@link #transmitter()} gives the full
 * {@link SsfTransmitter} with its {@link SsfStreamClient}, and {@link SsfTransmitters}
 * has every transmitter when there are several.
 */
@Singleton
public class SsfReceiverStreamClient {

    private static final Set<String> STATUSES = Set.of(SsfStreamStatus.ENABLED, SsfStreamStatus.PAUSED,
            SsfStreamStatus.DISABLED);

    @Inject
    SsfReceiverConfig config;

    @Inject
    Instance<SsfTransmitters> transmitters;

    /**
     * @return the default transmitter
     * @throws IllegalStateException if none is configured
     */
    public SsfTransmitter transmitter() {
        return transmitters.get().primary()
                .orElseThrow(() -> new IllegalStateException("There is no default SSF transmitter, use SsfTransmitters"));
    }

    /**
     * The identifier of the stream of this receiver at the default transmitter: the one
     * the stream registrar looked up or created, else the configured {@code stream-id}.
     *
     * @throws SsfStreamException if it is not known (yet): the stream is neither
     *         configured nor managed, or its registration is still running
     */
    public String streamId() {
        SsfTransmitter transmitter = transmitter();
        String streamId = transmitter.getReceiverStream().getStreamId();
        if (streamId == null) {
            SsfTransmitterConfig transmitterConfig = config.transmitters().get(transmitter.getName());
            streamId = (transmitterConfig != null) ? transmitterConfig.streamId().filter(id -> !id.isBlank()).orElse(null)
                    : null;
        }
        if (streamId == null) {
            throw new SsfStreamException("The stream of the receiver at the SSF transmitter " + transmitter
                    + " is not known (yet): configure stream-id, or wait for the stream registration", 0, null);
        }
        return streamId;
    }

    /** The configuration of the stream as it was last looked up or registered, empty while unknown. */
    public Optional<SsfStreamConfiguration> stream() {
        return transmitter().getReceiverStream().getConfiguration();
    }

    /** Reads the configuration of the stream from the transmitter. */
    public SsfStreamConfiguration configuration() {
        return streamClient().getStream(streamId());
    }

    /** The streams of this receiver at the transmitter. */
    public List<SsfStreamConfiguration> listStreams() {
        return streamClient().getStreams();
    }

    /** Reads the status of the stream from the transmitter. */
    public SsfStreamStatus status() {
        return streamClient().getStatus(streamId());
    }

    /**
     * Enables, pauses or disables the stream.
     *
     * @param status {@code enabled}, {@code paused} or {@code disabled}
     * @param reason why, may be {@code null}
     */
    public SsfStreamStatus updateStatus(String status, String reason) {
        String normalized = (status != null) ? status.toLowerCase(java.util.Locale.ROOT) : null;
        if (normalized == null || !STATUSES.contains(normalized)) {
            throw new SsfStreamException("status must be one of enabled, paused, disabled (got: " + status + ")", 0,
                    null);
        }
        return streamClient().updateStatus(streamId(), normalized, (reason != null && !reason.isBlank()) ? reason : null);
    }

    /**
     * Asks the transmitter to send events about the subject.
     *
     * @param subject a subject identifier, see {@code SsfSubjectIdentifiers}
     * @param verified whether this receiver has verified the subject
     */
    public void addSubject(Map<String, Object> subject, boolean verified) {
        streamClient().addSubject(streamId(), requireSubject(subject), verified);
    }

    /** Asks the transmitter to no longer send events about the subject. */
    public void removeSubject(Map<String, Object> subject) {
        streamClient().removeSubject(streamId(), requireSubject(subject));
    }

    /**
     * Asks the transmitter to send a verification event on the stream. The event is
     * validated against the returned state when it arrives.
     *
     * @return the state the verification event has to echo
     */
    public String requestVerification() {
        return transmitter().getStreamVerification().requestVerification(streamClient(), streamId());
    }

    /** Deletes the stream at the transmitter. */
    public void deleteStream() {
        streamClient().deleteStream(streamId());
        transmitter().getReceiverStream().setConfiguration(null);
    }

    private SsfStreamClient streamClient() {
        SsfStreamClient client = transmitter().getStreamClient();
        if (client == null) {
            throw new IllegalStateException("The SSF transmitter " + transmitter() + " has no stream client");
        }
        return client;
    }

    private static Map<String, Object> requireSubject(Map<String, Object> subject) {
        if (subject == null || subject.isEmpty()) {
            throw new SsfStreamException("subject must not be empty", 0, null);
        }
        if (!subject.containsKey("format")) {
            throw new SsfStreamException("subject must have a 'format' member", 0, null);
        }
        return subject;
    }
}
