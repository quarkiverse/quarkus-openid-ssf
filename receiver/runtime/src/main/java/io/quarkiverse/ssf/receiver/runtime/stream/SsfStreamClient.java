package io.quarkiverse.ssf.receiver.runtime.stream;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.auth.TransmitterAuthClientRequestFilter;
import io.quarkiverse.ssf.receiver.runtime.auth.TransmitterTokenProvider;
import io.quarkiverse.ssf.receiver.runtime.metadata.SsfConfigurationResolver;
import io.quarkiverse.ssf.receiver.runtime.stream.status.SsfTransmitterStreamStatusApi;
import io.quarkiverse.ssf.receiver.runtime.stream.status.StreamStatus;
import io.quarkiverse.ssf.receiver.runtime.stream.status.StreamStatusDto;
import io.quarkiverse.ssf.receiver.runtime.stream.subjects.AddSubjectRequest;
import io.quarkiverse.ssf.receiver.runtime.stream.subjects.RemoveSubjectRequest;
import io.quarkiverse.ssf.receiver.runtime.stream.subjects.SsfTransmitterStreamAddSubjectApi;
import io.quarkiverse.ssf.receiver.runtime.stream.subjects.SsfTransmitterStreamRemoveSubjectApi;
import io.quarkiverse.ssf.receiver.runtime.stream.verification.SsfTransmitterStreamVerificationApi;
import io.quarkiverse.ssf.receiver.runtime.stream.verification.StreamVerificationRequest;

/**
 * Client for transmitter-side stream operations defined by the SSF spec.
 * Exposes read stream configuration (§7.1.1.2), read stream status (§8.1.2.1),
 * update stream status (§8.1.2.2), add/remove subject (§8.1.3.2 / §8.1.3.3),
 * and trigger verification (§8.1.4.2).
 *
 * <p>
 * The endpoint is discovered from the transmitter's {@code ssf-configuration}
 * metadata document; if the transmitter doesn't advertise a {@code status_endpoint}
 * an {@link SsfStreamException} is thrown.
 *
 * <p>
 * HTTP is delegated to a MicroProfile REST Client built programmatically against
 * {@link SsfTransmitterStreamStatusApi}. Outbound calls automatically include
 * {@code Authorization: Bearer <token>} via {@link TransmitterAuthClientRequestFilter}
 * — typically backed by the optional {@code quarkus-oidc-client} integration that
 * obtains the token via the client_credentials grant.
 */
@ApplicationScoped
public class SsfStreamClient {

    private static final Logger LOG = Logger.getLogger(SsfStreamClient.class);

    @Inject
    SsfReceiverConfig config;

    @Inject
    SsfConfigurationResolver metadata;

    @Inject
    TransmitterTokenProvider tokenProvider;

    @Inject
    ReceiverManagedStreamState receiverManagedStreamState;

    /** Reads the status of the configured stream from the transmitter. */
    public StreamStatus status() {
        return statusOf(configuredStreamId());
    }

    /** Reads the status of an arbitrary stream id. */
    public StreamStatus statusOf(String streamId) {
        requireStreamId(streamId);
        return call("status read for stream_id=" + streamId,
                api -> api.status(streamId));
    }

    /** Updates the status of the configured stream. */
    public StreamStatus updateStatus(StreamStatus.Status status) {
        return updateStatus(status, null);
    }

    /** Updates the status of the configured stream with an optional reason. */
    public StreamStatus updateStatus(StreamStatus.Status status, String reason) {
        return updateStatusOf(configuredStreamId(), status, reason);
    }

    /** Updates the status of an arbitrary stream id. {@code reason} may be {@code null}. */
    public StreamStatus updateStatusOf(String streamId, StreamStatus.Status status, String reason) {
        requireStreamId(streamId);
        if (status == null || status == StreamStatus.Status.UNKNOWN) {
            throw new SsfStreamException("status must be one of ENABLED, PAUSED, DISABLED");
        }
        StreamStatusDto request = new StreamStatusDto(
                streamId,
                status.name().toLowerCase(Locale.ROOT),
                reason);
        return call("status update for stream_id=" + streamId + " status=" + request.status(),
                api -> api.updateStatus(request));
    }

    /** Adds a subject to the configured stream (§8.1.3.2), {@code verified} optional. */
    public void addSubject(Map<String, Object> subject, Boolean verified) {
        addSubjectFor(configuredStreamId(), subject, verified);
    }

    /** Adds a subject to the configured stream (§8.1.3.2). */
    public void addSubject(Map<String, Object> subject) {
        addSubject(subject, null);
    }

    /** Adds a subject to an arbitrary stream id. {@code verified} may be {@code null}. */
    public void addSubjectFor(String streamId, Map<String, Object> subject, Boolean verified) {
        requireStreamId(streamId);
        requireSubject(subject);

        URI baseUri = metadata.get().addSubjectEndpoint();
        if (baseUri == null) {
            throw new SsfStreamException("Transmitter metadata does not advertise an add_subject_endpoint");
        }

        String description = "add subject for stream_id=" + streamId;
        LOG.debugf("Calling SSF add_subject endpoint %s — %s", baseUri, description);

        SsfTransmitterStreamAddSubjectApi api = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .register(new TransmitterAuthClientRequestFilter(tokenProvider))
                .build(SsfTransmitterStreamAddSubjectApi.class);

        try {
            api.addSubject(new AddSubjectRequest(streamId, subject, verified));
        } catch (WebApplicationException e) {
            throw asStreamException(description, baseUri, e);
        } catch (RuntimeException e) {
            throw new SsfStreamException(description + " — request failed for " + baseUri, e);
        }
    }

    /** Removes a subject from the configured stream (§8.1.3.3). */
    public void removeSubject(Map<String, Object> subject) {
        removeSubjectFor(configuredStreamId(), subject);
    }

    /** Removes a subject from an arbitrary stream id. */
    public void removeSubjectFor(String streamId, Map<String, Object> subject) {
        requireStreamId(streamId);
        requireSubject(subject);

        URI baseUri = metadata.get().removeSubjectEndpoint();
        if (baseUri == null) {
            throw new SsfStreamException("Transmitter metadata does not advertise a remove_subject_endpoint");
        }

        String description = "remove subject for stream_id=" + streamId;
        LOG.debugf("Calling SSF remove_subject endpoint %s — %s", baseUri, description);

        SsfTransmitterStreamRemoveSubjectApi api = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .register(new TransmitterAuthClientRequestFilter(tokenProvider))
                .build(SsfTransmitterStreamRemoveSubjectApi.class);

        try {
            api.removeSubject(new RemoveSubjectRequest(streamId, subject));
        } catch (WebApplicationException e) {
            throw asStreamException(description, baseUri, e);
        } catch (RuntimeException e) {
            throw new SsfStreamException(description + " — request failed for " + baseUri, e);
        }
    }

    private static void requireSubject(Map<String, Object> subject) {
        if (subject == null || subject.isEmpty()) {
            throw new SsfStreamException("subject must not be null or empty");
        }
        if (!subject.containsKey("format")) {
            throw new SsfStreamException("subject must include a 'format' field");
        }
    }

    private static SsfStreamException asStreamException(String description, URI baseUri, WebApplicationException e) {
        int code = e.getResponse() != null ? e.getResponse().getStatus() : -1;
        String body = e.getResponse() != null ? readBody(e.getResponse()) : "";
        return new SsfStreamException(
                description + " — HTTP " + code + " from " + baseUri + " body=" + body, e);
    }

    /**
     * Requests a Verification Event for the configured stream with a freshly-generated
     * random {@code state} value (§8.1.4.2). Returns the state used so the caller can
     * correlate the inbound Verification SET. {@code null} state would mean an
     * unsolicited-style request — use {@link #requestVerification(String)} explicitly
     * if that's what's wanted.
     */
    public String requestVerification() {
        String state = UUID.randomUUID().toString();
        requestVerification(state);
        return state;
    }

    /**
     * Requests a Verification Event for the configured stream with the given
     * {@code state} (may be {@code null} to omit the parameter from the request).
     */
    public void requestVerification(String state) {
        requestVerificationFor(configuredStreamId(), state);
    }

    /** Requests a Verification Event for an arbitrary stream id, with optional state. */
    public void requestVerificationFor(String streamId, String state) {
        requireStreamId(streamId);
        URI baseUri = metadata.get().verificationEndpoint();
        if (baseUri == null) {
            throw new SsfStreamException("Transmitter metadata does not advertise a verification_endpoint");
        }

        String description = "verification request for stream_id=" + streamId
                + (state != null ? " state=" + state : " (no state)");
        LOG.debugf("Calling SSF verification endpoint %s — %s", baseUri, description);

        SsfTransmitterStreamVerificationApi api = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .register(new TransmitterAuthClientRequestFilter(tokenProvider))
                .build(SsfTransmitterStreamVerificationApi.class);

        try {
            api.requestVerification(new StreamVerificationRequest(streamId, state));
        } catch (WebApplicationException e) {
            int code = e.getResponse() != null ? e.getResponse().getStatus() : -1;
            String body = e.getResponse() != null ? readBody(e.getResponse()) : "";
            throw new SsfStreamException(
                    description + " — HTTP " + code + " from " + baseUri + " body=" + body, e);
        } catch (RuntimeException e) {
            throw new SsfStreamException(description + " — request failed for " + baseUri, e);
        }
    }

    /** Reads the configuration of the configured stream from the transmitter (§7.1.1.2). */
    public StreamConfiguration configuration() {
        return configurationOf(configuredStreamId());
    }

    /** Reads the configuration of an arbitrary stream id from the transmitter. */
    public StreamConfiguration configurationOf(String streamId) {
        requireStreamId(streamId);
        return callConfigurationApi("configuration read for stream_id=" + streamId,
                api -> api.configuration(streamId), true);
    }

    /**
     * Creates a new stream on the transmitter (§7.1.1.1). The transmitter assigns the
     * {@code stream_id}; this method returns the full echoed-back configuration.
     */
    public StreamConfiguration createStream(StreamConfiguration request) {
        if (request == null) {
            throw new SsfStreamException("stream configuration request must not be null");
        }
        StreamConfigurationDto dto = toDto(request, /* requireStreamId */ false);
        return callConfigurationApi("stream create", api -> api.create(dto), true);
    }

    /**
     * Replaces an existing stream's configuration (§8.1.1.4, PUT).
     * {@code request.streamId()} is required. Per the spec, missing
     * Receiver-Supplied properties are interpreted as deletion requests — so
     * include every property you still want to keep, even unchanged ones. Use
     * {@link #patchStream(StreamConfiguration)} when you only want to change a
     * few fields.
     */
    public StreamConfiguration updateStream(StreamConfiguration request) {
        if (request == null) {
            throw new SsfStreamException("stream configuration request must not be null");
        }
        if (request.streamId() == null || request.streamId().isBlank()) {
            throw new SsfStreamException("stream_id is required when updating a stream");
        }
        StreamConfigurationDto dto = toDto(request, /* requireStreamId */ true);
        return callConfigurationApi("stream update for stream_id=" + request.streamId(),
                api -> api.update(dto), true);
    }

    /**
     * Partially updates an existing stream's configuration (§8.1.1.3, PATCH).
     * {@code request.streamId()} is required; any field left {@code null} /
     * empty in {@code request} is omitted from the JSON body — and per the
     * spec, "properties missing in the request MUST NOT be changed by the
     * Transmitter."
     *
     * <p>
     * Prefer this over {@link #updateStream(StreamConfiguration)} when you
     * only want to change a few properties — e.g. flipping {@code description}
     * or appending to {@code events_requested} without having to re-send
     * {@code delivery}, {@code aud}, etc. (PUT would otherwise treat them as
     * deletions.)
     */
    public StreamConfiguration patchStream(StreamConfiguration request) {
        if (request == null) {
            throw new SsfStreamException("stream configuration request must not be null");
        }
        if (request.streamId() == null || request.streamId().isBlank()) {
            throw new SsfStreamException("stream_id is required when patching a stream");
        }
        StreamConfigurationDto dto = toDto(request, /* requireStreamId */ true);
        return callConfigurationApi("stream patch for stream_id=" + request.streamId(),
                api -> api.patch(dto), true);
    }

    /** Deletes a stream (§7.1.1.4). */
    public void deleteStream(String streamId) {
        requireStreamId(streamId);
        callConfigurationApi("stream delete for stream_id=" + streamId, api -> {
            api.delete(streamId);
            return null;
        }, false);
    }

    /**
     * Lists all streams owned by the calling Receiver (§7.1.1.2 with no {@code stream_id}).
     * Returns an empty list rather than {@code null} if the transmitter responds with no
     * streams.
     */
    public List<StreamConfiguration> listStreams() {
        URI baseUri = metadata.get().configurationEndpoint();
        if (baseUri == null) {
            throw new SsfStreamException("Transmitter metadata does not advertise a configuration_endpoint");
        }
        String description = "stream list";
        LOG.debugf("Calling SSF stream configuration endpoint %s — %s", baseUri, description);

        SsfTransmitterStreamConfigurationApi api = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .register(new TransmitterAuthClientRequestFilter(tokenProvider))
                .build(SsfTransmitterStreamConfigurationApi.class);

        try {
            List<StreamConfigurationDto> dtos = api.listStreams();
            if (dtos == null || dtos.isEmpty()) {
                return List.of();
            }
            return dtos.stream()
                    .filter(d -> d != null && d.streamId() != null)
                    .map(StreamConfigurationDto::toStreamConfiguration)
                    .toList();
        } catch (WebApplicationException e) {
            throw asStreamException(description, baseUri, e);
        } catch (RuntimeException e) {
            throw new SsfStreamException(description + " — request failed for " + baseUri, e);
        }
    }

    private StreamConfiguration callConfigurationApi(
            String description,
            Function<SsfTransmitterStreamConfigurationApi, StreamConfigurationDto> invocation,
            boolean expectResponseBody) {
        URI baseUri = metadata.get().configurationEndpoint();
        if (baseUri == null) {
            throw new SsfStreamException("Transmitter metadata does not advertise a configuration_endpoint");
        }

        LOG.debugf("Calling SSF stream configuration endpoint %s — %s", baseUri, description);

        SsfTransmitterStreamConfigurationApi api = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .register(new TransmitterAuthClientRequestFilter(tokenProvider))
                .build(SsfTransmitterStreamConfigurationApi.class);

        try {
            StreamConfigurationDto dto = invocation.apply(api);
            if (!expectResponseBody) {
                return null;
            }
            if (dto == null || dto.streamId() == null) {
                throw new SsfStreamException(description + " — response missing required stream_id: " + dto);
            }
            return dto.toStreamConfiguration();
        } catch (WebApplicationException e) {
            throw asStreamException(description, baseUri, e);
        } catch (SsfStreamException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SsfStreamException(description + " — request failed for " + baseUri, e);
        }
    }

    private static StreamConfigurationDto toDto(StreamConfiguration src, boolean requireStreamId) {
        if (requireStreamId && (src.streamId() == null || src.streamId().isBlank())) {
            throw new SsfStreamException("stream_id is required for this operation");
        }
        StreamConfigurationDto.DeliveryDto deliveryDto = null;
        if (src.delivery() != null) {
            deliveryDto = new StreamConfigurationDto.DeliveryDto();
            deliveryDto.setMethod(src.delivery().method());
            deliveryDto.setEndpointUrl(src.delivery().endpointUrl());
            if (src.delivery().additionalParameters() != null) {
                src.delivery().additionalParameters().forEach(deliveryDto::setOther);
            }
        }
        return new StreamConfigurationDto(
                emptyToNull(src.streamId()),
                emptyToNull(src.iss()),
                src.aud() == null || src.aud().isEmpty() ? null : List.copyOf(src.aud()),
                src.eventsSupported() == null || src.eventsSupported().isEmpty() ? null : List.copyOf(src.eventsSupported()),
                src.eventsRequested() == null || src.eventsRequested().isEmpty() ? null : List.copyOf(src.eventsRequested()),
                src.eventsDelivered() == null || src.eventsDelivered().isEmpty() ? null : List.copyOf(src.eventsDelivered()),
                deliveryDto,
                src.minVerificationInterval(),
                src.inactivityTimeout(),
                emptyToNull(src.description()));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private StreamStatus call(String description, Function<SsfTransmitterStreamStatusApi, StreamStatusDto> invocation) {
        URI baseUri = metadata.statusEndpoint()
                .orElseThrow(() -> new SsfStreamException("Transmitter metadata does not advertise a status_endpoint"));

        LOG.debugf("Calling SSF stream endpoint %s — %s", baseUri, description);

        SsfTransmitterStreamStatusApi api = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .register(new TransmitterAuthClientRequestFilter(tokenProvider))
                .build(SsfTransmitterStreamStatusApi.class);

        try {
            StreamStatusDto dto = invocation.apply(api);
            if (dto == null || dto.streamId() == null || dto.status() == null) {
                throw new SsfStreamException(description + " — response missing required fields: " + dto);
            }
            return dto.toStreamStatus();
        } catch (WebApplicationException e) {
            int code = e.getResponse() != null ? e.getResponse().getStatus() : -1;
            String body = e.getResponse() != null ? readBody(e.getResponse()) : "";
            throw new SsfStreamException(
                    description + " — HTTP " + code + " from " + baseUri + " body=" + body, e);
        } catch (SsfStreamException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SsfStreamException(description + " — request failed for " + baseUri, e);
        }
    }

    private String configuredStreamId() {
        return receiverManagedStreamState.streamId()
                .or(config::streamId)
                .orElseThrow(() -> new SsfStreamException(
                        "stream_id is not configured (receiver-managed registration may not have completed)"));
    }

    private static void requireStreamId(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            throw new SsfStreamException("streamId must not be blank");
        }
    }

    private static String readBody(Response response) {
        try {
            return response.readEntity(String.class);
        } catch (Exception e) {
            return "<unreadable>";
        }
    }
}
