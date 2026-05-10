/*
 * Copyright 2026 Thomas Darimont and the easyssf.com contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.easyssf.quarkus.ssfreceiver.runtime.metadata;

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
