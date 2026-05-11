package io.quarkiverse.ssf.receiver.runtime.metadata;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The decoded SSF transmitter metadata document — the JSON returned from
 * {@code <issuer>/.well-known/ssf-configuration}. Mirrors the fields defined in the
 * <a href="https://openid.net/specs/openid-sharedsignals-framework-1_0.html#section-7.1">
 * SSF spec §7.1</a>; {@code additionalProperties} carries any keys the transmitter
 * advertised that aren't modelled here, for forward compatibility.
 */
public record SsfTransmitterMetadata(
        String specVersion,
        URI issuer,
        URI configurationEndpoint,
        URI jwksUri,
        URI statusEndpoint,
        URI addSubjectEndpoint,
        URI removeSubjectEndpoint,
        URI verificationEndpoint,
        List<String> deliveryMethodsSupported,
        List<String> authorizationSchemes,
        List<String> criticalSubjectMembers,
        List<Map<String, Object>> defaultSubjects,
        Map<String, Object> additionalProperties) {
}
