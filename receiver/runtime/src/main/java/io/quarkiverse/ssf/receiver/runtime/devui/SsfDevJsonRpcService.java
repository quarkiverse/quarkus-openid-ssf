package io.quarkiverse.ssf.receiver.runtime.devui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.core.metadata.SsfTransmitterMetadata;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.core.stream.SsfStreamStatus;
import org.easyssf.receiver.stream.SsfStreamException;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.SsfTransmitterConfig;
import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;

/**
 * JSON-RPC backend of the Dev UI pages: transmitter metadata, the stream of the receiver
 * at the default transmitter, its status, subjects and verification. Lives in the runtime
 * artifact so that Arc wires its dependencies.
 */
@Singleton
public class SsfDevJsonRpcService {

    private static final Set<String> KNOWN_METADATA = Set.of("issuer", "spec_version", "jwks_uri",
            "configuration_endpoint", "status_endpoint", "add_subject_endpoint", "remove_subject_endpoint",
            "verification_endpoint", "delivery_methods_supported", "authorization_schemes",
            "critical_subject_members", "default_subjects");

    @Inject
    SsfReceiverConfig config;

    @Inject
    Instance<SsfTransmitters> transmitters;

    @Inject
    SsfReceiverStreamClient streamClient;

    /**
     * Whether the stream of the default transmitter is known, without calling the
     * transmitter. The page shows a "registration in progress" note instead of its other
     * calls while {@code ready=false}.
     */
    public RegistrationStatus registrationStatus() {
        if (!config.enabled()) {
            return new RegistrationStatus(false, null, null,
                    "quarkus.openid-ssf.receiver.enabled=false, the receiver is disabled");
        }
        Optional<SsfTransmitter> primary = transmitters.get().primary();
        if (primary.isEmpty()) {
            return new RegistrationStatus(false, null, null,
                    "No default transmitter is configured, only named ones: " + transmitters.get().all());
        }
        SsfTransmitter transmitter = primary.get();
        String mode = transmitterConfig(transmitter).streamManagement().name();
        String streamId = transmitter.getReceiverStream().getStreamId();
        SsfStreamRegistrar registrar = transmitter.getStreamRegistrar();
        if (streamId != null) {
            return new RegistrationStatus(true, mode, streamId, null);
        }
        if (registrar == null) {
            return new RegistrationStatus(false, mode, null,
                    "No stream is configured: set quarkus.openid-ssf.receiver.stream-id or stream-management=RECEIVER");
        }
        String message = switch (registrar.getState()) {
            case FAILED -> "Stream registration failed: " + registrar.getLastError();
            case NOT_STARTED -> "Stream registration has not started";
            default -> "Stream registration in progress"
                    + ((registrar.getLastError() != null) ? ", last attempt: " + registrar.getLastError() : "");
        };
        return new RegistrationStatus(false, mode, null, message);
    }

    public ConfiguredStream configuredStream() {
        SsfTransmitter transmitter = streamClient.transmitter();
        SsfTransmitterConfig transmitterConfig = transmitterConfig(transmitter);
        return new ConfiguredStream(
                transmitter.getReceiverStream().getStreamId(),
                transmitterConfig.streamManagement().name(),
                transmitterConfig.deliveryMethod().name(),
                transmitter.getIssuer(),
                transmitter.getName(),
                transmitterConfig.expectedAudience().orElse(null));
    }

    public Map<String, Object> transmitterMetadata() {
        SsfTransmitterMetadata metadata = streamClient.transmitter().getMetadataResolver().resolve();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issuer", metadata.issuer());
        result.put("specVersion", metadata.specVersion());
        result.put("jwksUri", text(metadata.jwksUri()));
        result.put("configurationEndpoint", text(metadata.configurationEndpoint()));
        result.put("statusEndpoint", text(metadata.statusEndpoint()));
        result.put("addSubjectEndpoint", text(metadata.addSubjectEndpoint()));
        result.put("removeSubjectEndpoint", text(metadata.removeSubjectEndpoint()));
        result.put("verificationEndpoint", text(metadata.verificationEndpoint()));
        result.put("deliveryMethodsSupported", metadata.deliveryMethodsSupported());
        result.put("authorizationSchemes", metadata.authorizationSchemes().stream().map(Object::toString).toList());
        result.put("criticalSubjectMembers", metadata.criticalSubjectMembers());
        Map<String, Object> additional = new LinkedHashMap<>();
        metadata.claims().forEach((name, value) -> {
            if (!KNOWN_METADATA.contains(name)) {
                additional.put(name, value);
            }
        });
        result.put("additionalProperties", additional);
        return result;
    }

    /**
     * The event type aliases (built-in and configured), keyed by URI, and the names of
     * the transmitters, keyed by issuer: the vocabulary of the log lines and metric tags.
     */
    public AliasesSnapshot configuredAliases() {
        Map<String, String> eventTypeAliases = new LinkedHashMap<>();
        SsfEventTypes.aliases().forEach((alias, uri) -> eventTypeAliases.putIfAbsent(uri, alias));
        Map<String, String> transmitterNames = new LinkedHashMap<>();
        transmitters.get().all().forEach(t -> transmitterNames.put(t.getIssuer(), t.getName()));
        return new AliasesSnapshot(eventTypeAliases, transmitterNames);
    }

    public SsfStreamStatus status() {
        return streamClient.status();
    }

    public Map<String, Object> streamConfiguration() {
        return toMap(streamClient.configuration());
    }

    public VerificationRequested requestVerification() {
        return new VerificationRequested(streamClient.requestVerification());
    }

    public SubjectOperationResult addSubject(String format, String value, String issForIssSub, Boolean verified) {
        Map<String, Object> subject = buildSubject(format, value, issForIssSub);
        streamClient.addSubject(subject, Boolean.TRUE.equals(verified));
        return new SubjectOperationResult("added", subject);
    }

    public SubjectOperationResult removeSubject(String format, String value, String issForIssSub) {
        Map<String, Object> subject = buildSubject(format, value, issForIssSub);
        streamClient.removeSubject(subject);
        return new SubjectOperationResult("removed", subject);
    }

    public SsfStreamStatus updateStatus(String status, String reason) {
        return streamClient.updateStatus(status, reason);
    }

    private SsfTransmitterConfig transmitterConfig(SsfTransmitter transmitter) {
        return config.transmitters().get(transmitter.getName());
    }

    private static Map<String, Object> toMap(SsfStreamConfiguration stream) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("streamId", stream.streamId());
        result.put("iss", stream.issuer());
        result.put("aud", stream.audience());
        result.put("eventsSupported", stream.eventsSupported());
        result.put("eventsRequested", stream.eventsRequested());
        result.put("eventsDelivered", stream.eventsDelivered());
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("method", stream.deliveryMethod());
        delivery.put("endpointUrl", text(stream.deliveryEndpointUrl()));
        result.put("delivery", delivery);
        result.put("minVerificationInterval", stream.claims().get("min_verification_interval"));
        result.put("inactivityTimeout", stream.claims().get("inactivity_timeout"));
        result.put("description", stream.description());
        return result;
    }

    private static String text(Object value) {
        return (value != null) ? value.toString() : null;
    }

    private static Map<String, Object> buildSubject(String format, String value, String issForIssSub) {
        if (format == null || format.isBlank()) {
            throw new SsfStreamException("subject format is required", 0, null);
        }
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "email" -> SsfSubjectIdentifiers.email(requireValue(value, "email"));
            case "opaque" -> SsfSubjectIdentifiers.opaque(requireValue(value, "id"));
            case "iss_sub" -> SsfSubjectIdentifiers.issSub(requireValue(issForIssSub, "iss"), requireValue(value, "sub"));
            case "complex" -> parseComplex(value);
            default -> throw new SsfStreamException(
                    "Unsupported subject format: " + format + " (expected: iss_sub, email, opaque, complex)", 0, null);
        };
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new SsfStreamException("subject " + name + " is required", 0, null);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseComplex(String json) {
        if (json == null || json.isBlank()) {
            throw new SsfStreamException("complex subject body is required (JSON object)", 0, null);
        }
        try {
            Map<String, Object> parsed = com.nimbusds.jose.util.JSONObjectUtils.parse(json);
            Map<String, Map<String, Object>> members = new LinkedHashMap<>();
            parsed.forEach((name, member) -> {
                if (!"format".equals(name) && member instanceof Map<?, ?> map) {
                    members.put(name, (Map<String, Object>) map);
                }
            });
            return SsfSubjectIdentifiers.complex(members);
        } catch (java.text.ParseException e) {
            throw new SsfStreamException("complex subject body is not valid JSON: " + e.getMessage(), 0, e);
        }
    }

    public record RegistrationStatus(boolean ready, String mode, String streamId, String message) {
    }

    public record ConfiguredStream(String streamId, String streamManagement, String deliveryMethod,
            String transmitterIssuer, String transmitterName, String expectedAudience) {
    }

    public record AliasesSnapshot(Map<String, String> eventTypeAliases, Map<String, String> issuerAliases) {
    }

    public record VerificationRequested(String state) {
    }

    public record SubjectOperationResult(String operation, Map<String, Object> subject) {
    }

}
