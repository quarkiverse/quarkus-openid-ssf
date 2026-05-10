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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfAliases;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.status.StreamStatus;

/**
 * Pure unit tests for the formatter helpers shared by the registrar / probe.
 * Lives in the same package as {@link StreamLogFormat} so it can call the
 * package-private statics directly.
 */
class StreamLogFormatTest {

    private static StreamConfiguration cfgWithDelivery(String method, URI endpoint) {
        return new StreamConfiguration(
                "stream-1", "https://tx.example", List.of(),
                List.of(), List.of(), List.of(),
                method == null ? null : new StreamConfiguration.Delivery(method, endpoint, Map.of()),
                null, null, null);
    }

    @Nested
    @DisplayName("describeDeliveryMethod")
    class DescribeDeliveryMethod {

        @Test
        @DisplayName("RFC 8935 URN → 'PUSH'")
        void pushUrn() {
            assertThat(StreamLogFormat.describeDeliveryMethod(
                    cfgWithDelivery("urn:ietf:rfc:8935", URI.create("https://x/push"))),
                    equalTo("PUSH"));
        }

        @Test
        @DisplayName("RFC 8936 URN → 'POLL'")
        void pollUrn() {
            assertThat(StreamLogFormat.describeDeliveryMethod(
                    cfgWithDelivery("urn:ietf:rfc:8936", null)),
                    equalTo("POLL"));
        }

        @Test
        @DisplayName("Unknown method URN is logged verbatim")
        void unknownUrnVerbatim() {
            assertThat(StreamLogFormat.describeDeliveryMethod(
                    cfgWithDelivery("urn:ietf:rfc:9999", null)),
                    equalTo("urn:ietf:rfc:9999"));
        }

        @Test
        @DisplayName("Missing delivery / null method → '<none>'")
        void noDelivery() {
            assertThat(StreamLogFormat.describeDeliveryMethod(cfgWithDelivery(null, null)),
                    equalTo("<none>"));
        }
    }

    @Nested
    @DisplayName("endpointFieldName")
    class EndpointFieldName {

        @Test
        @DisplayName("PUSH → push_endpoint")
        void push() {
            assertThat(StreamLogFormat.endpointFieldName(
                    cfgWithDelivery("urn:ietf:rfc:8935", URI.create("https://x"))),
                    equalTo("push_endpoint"));
        }

        @Test
        @DisplayName("POLL → poll_endpoint")
        void poll() {
            assertThat(StreamLogFormat.endpointFieldName(
                    cfgWithDelivery("urn:ietf:rfc:8936", URI.create("https://x"))),
                    equalTo("poll_endpoint"));
        }

        @Test
        @DisplayName("Unknown / missing → generic 'endpoint'")
        void unknown() {
            assertThat(StreamLogFormat.endpointFieldName(
                    cfgWithDelivery("urn:ietf:rfc:9999", null)),
                    equalTo("endpoint"));
            assertThat(StreamLogFormat.endpointFieldName(cfgWithDelivery(null, null)),
                    equalTo("endpoint"));
        }
    }

    @Nested
    @DisplayName("endpointOrNone")
    class EndpointOrNone {

        @Test
        @DisplayName("Returns the URL when present")
        void returnsUrl() {
            URI url = URI.create("https://x.example/push");
            assertThat(StreamLogFormat.endpointOrNone(cfgWithDelivery("urn:ietf:rfc:8935", url)),
                    equalTo(url));
        }

        @Test
        @DisplayName("Returns '<none>' when delivery block is missing")
        void noDelivery() {
            assertThat(StreamLogFormat.endpointOrNone(cfgWithDelivery(null, null)),
                    equalTo("<none>"));
        }

        @Test
        @DisplayName("Returns '<none>' when method is set but URL is null (POLL on create)")
        void deliveryWithoutEndpoint() {
            assertThat(StreamLogFormat.endpointOrNone(cfgWithDelivery("urn:ietf:rfc:8936", null)),
                    equalTo("<none>"));
        }
    }

    @Nested
    @DisplayName("eventsDelivered")
    class EventsDelivered {

        private static StreamConfiguration cfgWithEventsDelivered(List<URI> events) {
            return new StreamConfiguration(
                    "stream-1", "https://tx.example", List.of(),
                    List.of(), List.of(), events,
                    null, null, null, null);
        }

        @Test
        @DisplayName("Resolves URIs through SsfAliases for log readability")
        void resolvesAliases() {
            URI sessionRevoked = URI.create(
                    "https://schemas.openid.net/secevent/caep/event-type/session-revoked");
            // Mocking SsfAliases directly (it lives in another package, so we can't
            // reach into its package-private @PostConstruct hook from here).
            SsfAliases aliases = mock(SsfAliases.class);
            when(aliases.eventTypeAlias(sessionRevoked.toString())).thenReturn("CaepSessionRevoked");

            List<String> rendered = StreamLogFormat.eventsDelivered(
                    cfgWithEventsDelivered(List.of(sessionRevoked)),
                    aliases);

            assertThat(rendered, contains("CaepSessionRevoked"));
        }

        @Test
        @DisplayName("URIs without an alias fall through unchanged")
        void unknownUriPassThrough() {
            URI custom = URI.create("https://example.com/event/x");
            SsfAliases aliases = mock(SsfAliases.class);
            // SsfAliases.eventTypeAlias returns the URI itself when no alias is registered;
            // mock that contract so we don't bind to its internal map.
            when(aliases.eventTypeAlias(custom.toString())).thenReturn(custom.toString());

            List<String> rendered = StreamLogFormat.eventsDelivered(
                    cfgWithEventsDelivered(List.of(custom)),
                    aliases);

            assertThat(rendered, contains("https://example.com/event/x"));
        }

        @Test
        @DisplayName("Null / empty events_delivered → empty list")
        void emptyOrNull() {
            SsfAliases aliases = mock(SsfAliases.class);
            assertThat(StreamLogFormat.eventsDelivered(cfgWithEventsDelivered(null), aliases),
                    empty());
            assertThat(StreamLogFormat.eventsDelivered(cfgWithEventsDelivered(List.of()), aliases),
                    empty());
        }
    }

    @Nested
    @DisplayName("statusLabel")
    class StatusLabel {

        @Test
        @DisplayName("ENABLED status → 'enabled'")
        void enabled() {
            assertThat(StreamLogFormat.statusLabel(new StreamStatus("s1", "enabled", null)),
                    equalTo("enabled"));
        }

        @Test
        @DisplayName("Status with reason → 'paused/<reason>'")
        void pausedWithReason() {
            assertThat(StreamLogFormat.statusLabel(
                    new StreamStatus("s1", "paused", "SYSTEM_DOWN_FOR_MAINTENANCE")),
                    equalTo("paused/SYSTEM_DOWN_FOR_MAINTENANCE"));
        }

        @Test
        @DisplayName("Blank reason is dropped, no trailing slash")
        void blankReasonOmitted() {
            assertThat(StreamLogFormat.statusLabel(new StreamStatus("s1", "enabled", "  ")),
                    equalTo("enabled"));
        }

        @Test
        @DisplayName("Unknown status maps via StreamStatus.known() → 'unknown'")
        void unknownStatus() {
            // Anything outside ENABLED/PAUSED/DISABLED gets bucketed.
            assertThat(StreamLogFormat.statusLabel(new StreamStatus("s1", "weird-state", null)),
                    equalTo("unknown"));
        }

        @Test
        @DisplayName("null input → 'unknown' (defensive)")
        void nullInput() {
            assertThat(StreamLogFormat.statusLabel(null), equalTo("unknown"));
        }
    }
}
