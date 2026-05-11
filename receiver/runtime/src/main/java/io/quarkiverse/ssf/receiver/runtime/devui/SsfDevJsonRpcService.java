package io.quarkiverse.ssf.receiver.runtime.devui;

import java.util.Locale;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.event.SsfAliases;
import io.quarkiverse.ssf.receiver.runtime.metadata.SsfConfigurationResolver;
import io.quarkiverse.ssf.receiver.runtime.metadata.SsfTransmitterMetadata;
import io.quarkiverse.ssf.receiver.runtime.stream.ReceiverManagedStreamState;
import io.quarkiverse.ssf.receiver.runtime.stream.SsfStreamClient;
import io.quarkiverse.ssf.receiver.runtime.stream.SsfStreamException;
import io.quarkiverse.ssf.receiver.runtime.stream.StreamConfiguration;
import io.quarkiverse.ssf.receiver.runtime.stream.status.StreamStatus;
import io.quarkiverse.ssf.receiver.runtime.stream.subjects.SsfSubjects;

/**
 * JsonRPC backend for the SSF receiver Dev UI page. Surfaces the same operations
 * exposed by {@link SsfStreamClient} and {@link SsfConfigurationResolver} so the Dev UI
 * web component can read transmitter metadata, fetch the current stream status,
 * and toggle it (ENABLED / PAUSED / DISABLED) from the browser.
 *
 * <p>
 * Lives in the runtime artifact so the bean's dependencies (REST client,
 * metadata resolver, OIDC token provider) are wired by Arc.
 */
@ApplicationScoped
public class SsfDevJsonRpcService {

    @Inject
    SsfReceiverConfig config;

    @Inject
    SsfConfigurationResolver metadata;

    @Inject
    SsfStreamClient streamClient;

    @Inject
    ReceiverManagedStreamState receiverManagedStreamState;

    @Inject
    SsfAliases aliases;

    /**
     * Reports whether a stream id is currently resolvable — without making any
     * outbound call. The Dev UI page calls this first and skips its other JsonRpc
     * calls when {@code ready=false}, so the user sees a friendly "registration
     * in progress" message instead of a stack trace during the receiver-managed
     * registrar's discover-or-create dance.
     *
     * <ul>
     * <li>{@code TRANSMITTER}: ready iff {@code quarkus.openid-ssf.receiver.stream-id} is configured.</li>
     * <li>{@code RECEIVER}: ready iff the registrar has populated
     * {@link ReceiverManagedStreamState} (or the operator pinned a stream id).</li>
     * </ul>
     */
    public RegistrationStatus registrationStatus() {
        boolean enabled = config.enabled();
        if (!enabled) {
            return new RegistrationStatus(false, config.streamManagement().name(), null,
                    "quarkus.openid-ssf.receiver.enabled=false — receiver disabled");
        }
        return switch (config.streamManagement()) {
            case TRANSMITTER -> {
                String streamId = config.streamId().orElse(null);
                yield new RegistrationStatus(streamId != null, "TRANSMITTER", streamId,
                        streamId != null ? null : "quarkus.openid-ssf.receiver.stream-id is not configured");
            }
            case RECEIVER -> {
                String streamId = receiverManagedStreamState.streamId()
                        .or(config::streamId)
                        .orElse(null);
                yield new RegistrationStatus(streamId != null, "RECEIVER", streamId,
                        streamId != null ? null : "Receiver-managed registration in progress…");
            }
        };
    }

    public ConfiguredStream configuredStream() {
        return new ConfiguredStream(
                config.streamId().orElse(null),
                config.streamManagement().name(),
                config.deliveryMethod().name(),
                config.transmitterIssuer().toString(),
                config.expectedAudience().orElse(null));
    }

    public SsfTransmitterMetadata transmitterMetadata() {
        return metadata.get();
    }

    /**
     * Snapshot of the configured aliases (built-in defaults + user overrides).
     * Surfaced on the Stream Management page so operators can see the same
     * URI → short-name mapping that ends up in metric tags / log lines.
     */
    public AliasesSnapshot configuredAliases() {
        return new AliasesSnapshot(
                aliases.eventTypeAliasesByUri(),
                aliases.issuerAliasesByUri(),
                aliases.receiverAlias());
    }

    public StreamStatus status() {
        return streamClient.status();
    }

    public StreamConfiguration streamConfiguration() {
        return streamClient.configuration();
    }

    public VerificationRequested requestVerification() {
        String state = streamClient.requestVerification();
        return new VerificationRequested(state);
    }

    /**
     * Adds a subject built from the simple {@code format} + {@code value} inputs the
     * Dev UI exposes. Supports the formats Keycloak emits: {@code iss_sub},
     * {@code email}, {@code opaque}, {@code complex}. For {@code complex}, {@code value}
     * is parsed as JSON ({@link Map}) of nested subject fields.
     */
    public SubjectOperationResult addSubject(String format, String value, String issForIssSub, Boolean verified) {
        Map<String, Object> subject = buildSubject(format, value, issForIssSub);
        streamClient.addSubject(subject, verified);
        return new SubjectOperationResult("added", subject);
    }

    public SubjectOperationResult removeSubject(String format, String value, String issForIssSub) {
        Map<String, Object> subject = buildSubject(format, value, issForIssSub);
        streamClient.removeSubject(subject);
        return new SubjectOperationResult("removed", subject);
    }

    private static Map<String, Object> buildSubject(String format, String value, String issForIssSub) {
        if (format == null || format.isBlank()) {
            throw new SsfStreamException("subject format is required");
        }
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "email" -> SsfSubjects.email(requireValue(value, "email"));
            case "opaque" -> SsfSubjects.opaque(requireValue(value, "id"));
            case "iss_sub" -> SsfSubjects.issSub(
                    requireValue(issForIssSub, "iss"),
                    requireValue(value, "sub"));
            case "complex" -> parseComplex(value);
            default -> throw new SsfStreamException(
                    "Unsupported subject format: " + format + " (expected: iss_sub, email, opaque, complex)");
        };
    }

    private static String requireValue(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new SsfStreamException("subject " + name + " is required");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseComplex(String json) {
        if (json == null || json.isBlank()) {
            throw new SsfStreamException("complex subject body is required (JSON object)");
        }
        try {
            Map<String, Object> parsed = com.nimbusds.jose.util.JSONObjectUtils.parse(json);
            Map<String, Map<String, Object>> attrs = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                if ("format".equals(e.getKey()))
                    continue;
                if (e.getValue() instanceof Map<?, ?> m) {
                    attrs.put(e.getKey(), (Map<String, Object>) m);
                }
            }
            return SsfSubjects.complex(attrs);
        } catch (java.text.ParseException e) {
            throw new SsfStreamException("complex subject body is not valid JSON: " + e.getMessage(), e);
        }
    }

    public record VerificationRequested(String state) {
    }

    public record SubjectOperationResult(String operation, Map<String, Object> subject) {
    }

    public StreamStatus updateStatus(String status, String reason) {
        StreamStatus.Status target;
        try {
            target = StreamStatus.Status.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new SsfStreamException("status must be one of ENABLED, PAUSED, DISABLED (got: " + status + ")");
        }
        if (target == StreamStatus.Status.UNKNOWN) {
            throw new SsfStreamException("status must be one of ENABLED, PAUSED, DISABLED");
        }
        String safeReason = reason == null || reason.isBlank() ? null : reason;
        return streamClient.updateStatus(target, safeReason);
    }

    public record ConfiguredStream(
            String streamId,
            String streamManagement,
            String deliveryMethod,
            String transmitterIssuer,
            String expectedAudience) {
    }

    /** Lightweight readiness signal — never makes an outbound call. */
    public record RegistrationStatus(
            boolean ready,
            String mode,
            String streamId,
            String message) {
    }

    /** Snapshot of the configured aliases for display in the Dev UI. */
    public record AliasesSnapshot(
            Map<String, String> eventTypeAliases,
            Map<String, String> issuerAliases,
            String receiverAlias) {
    }
}
