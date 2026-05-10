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
package com.easyssf.quarkus.ssfreceiver.runtime.stream;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * On-the-wire shape of an SSF stream configuration document — spec §8.1.1.
 * Internal type; the public {@link StreamConfiguration} record is what consumers see.
 *
 * <p>
 * {@code aud} accepts either a JSON string or an array of strings (per the JWT
 * audience convention) thanks to {@link JsonFormat.Feature#ACCEPT_SINGLE_VALUE_AS_ARRAY}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamConfigurationDto(
        @JsonProperty("stream_id") String streamId,
        @JsonProperty("iss") String iss,
        @JsonProperty("aud") @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> aud,
        @JsonProperty("events_supported") List<URI> eventsSupported,
        @JsonProperty("events_requested") List<URI> eventsRequested,
        @JsonProperty("events_delivered") List<URI> eventsDelivered,
        @JsonProperty("delivery") DeliveryDto delivery,
        @JsonProperty("min_verification_interval") Integer minVerificationInterval,
        @JsonProperty("inactivity_timeout") Integer inactivityTimeout,
        @JsonProperty("description") String description) {

    /**
     * Per §8.1.1 / §6.1 {@code delivery} carries {@code method} plus method-dependent
     * fields. {@code endpoint_url} is the canonical key for PUSH (RFC 8935); any other
     * fields are kept in {@code other} via {@link JsonAnySetter} for forward compatibility.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class DeliveryDto {
        @JsonProperty("method")
        private String method;
        @JsonProperty("endpoint_url")
        private URI endpointUrl;
        private final Map<String, Object> other = new LinkedHashMap<>();

        public DeliveryDto() {
        }

        public String method() {
            return method;
        }

        public URI endpointUrl() {
            return endpointUrl;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public void setEndpointUrl(URI endpointUrl) {
            this.endpointUrl = endpointUrl;
        }

        @JsonAnyGetter
        public Map<String, Object> other() {
            return other;
        }

        @JsonAnySetter
        public void setOther(String key, Object value) {
            other.put(key, value);
        }
    }

    public StreamConfiguration toStreamConfiguration() {
        StreamConfiguration.Delivery deliveryRec = null;
        if (delivery != null) {
            deliveryRec = new StreamConfiguration.Delivery(
                    delivery.method(),
                    delivery.endpointUrl(),
                    Collections.unmodifiableMap(new LinkedHashMap<>(delivery.other())));
        }
        return new StreamConfiguration(
                streamId,
                iss,
                aud == null ? List.of() : List.copyOf(aud),
                eventsSupported == null ? List.of() : List.copyOf(eventsSupported),
                eventsRequested == null ? List.of() : List.copyOf(eventsRequested),
                eventsDelivered == null ? List.of() : List.copyOf(eventsDelivered),
                deliveryRec,
                minVerificationInterval,
                inactivityTimeout,
                description);
    }
}
