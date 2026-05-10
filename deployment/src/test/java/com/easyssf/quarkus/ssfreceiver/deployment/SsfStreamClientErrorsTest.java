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
package com.easyssf.quarkus.ssfreceiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfStreamClient;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfStreamException;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.status.StreamStatus;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Layer-2 test for {@link SsfStreamClient}'s error surfaces:
 * <ul>
 * <li>Argument validation throws {@link SsfStreamException} with a useful
 * message (blank stream_id, missing subject format, etc.) — no NPEs leaking
 * to callers.</li>
 * <li>Transmitter 4xx responses surface as {@code SsfStreamException} that
 * includes the HTTP code and the endpoint URL, so operators can correlate
 * with transmitter logs.</li>
 * </ul>
 *
 * <p>
 * The unhappy-path "unknown stream" cases work by calling the client with
 * a stream_id WireMock doesn't have a stub for — WireMock returns 404
 * for unstubbed routes, which is exactly the transmitter behavior we
 * want to validate.
 */
public class SsfStreamClientErrorsTest {

    private static final String ISSUER = "https://test.transmitter/realms/r1";
    private static final String AUDIENCE = "https://my-receiver.example/ssf";
    private static final String UNKNOWN_STREAM_ID = "this-stream-does-not-exist";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(JwksWireMock.class))
            .setBeforeAllCustomizer(() -> JwksWireMock.start(ISSUER, AUDIENCE))
            .setAfterAllCustomizer(JwksWireMock::stop)
            .overrideConfigKey("ssf.receiver.transmitter-issuer", ISSUER)
            .overrideConfigKey("ssf.receiver.expected-audience", AUDIENCE)
            .overrideConfigKey("ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("ssf.receiver.stream-id", "stream-1")
            .overrideConfigKey("ssf.receiver.delivery-method", "PUSH");

    @Inject
    SsfStreamClient streamClient;

    @Test
    @DisplayName("statusOf(null) → SsfStreamException, not NPE")
    void statusOfNullThrowsExplicitly() {
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> streamClient.statusOf(null));
        assertThat(ex.getMessage().toLowerCase(), containsString("stream"));
    }

    @Test
    @DisplayName("statusOf(\"\") → SsfStreamException, not silent")
    void statusOfBlankThrowsExplicitly() {
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> streamClient.statusOf("   "));
        assertThat(ex.getMessage().toLowerCase(), containsString("stream"));
    }

    @Test
    @DisplayName("statusOf(unknownStream) → 404 surfaces as SsfStreamException with HTTP code in message")
    void statusOfUnknownStreamReports404() {
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> streamClient.statusOf(UNKNOWN_STREAM_ID));
        // Either the message itself includes "404" or the wrapped cause does;
        // we just want it to be possible for an operator to find the HTTP code.
        String combined = ex.getMessage()
                + (ex.getCause() != null ? " | " + ex.getCause().getMessage() : "");
        assertThat(combined, containsString("404"));
    }

    @Test
    @DisplayName("configurationOf(unknownStream) → 404 surfaces as SsfStreamException")
    void configurationOfUnknownStreamReports404() {
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> streamClient.configurationOf(UNKNOWN_STREAM_ID));
        String combined = ex.getMessage()
                + (ex.getCause() != null ? " | " + ex.getCause().getMessage() : "");
        assertThat(combined, containsString("404"));
    }

    @Test
    @DisplayName("updateStatusOf(null status) → SsfStreamException — caller can't bypass status validation")
    void updateStatusRejectsNullStatus() {
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> streamClient.updateStatusOf("stream-1", null, "anything"));
        assertThat(ex.getMessage().toLowerCase(), containsString("status"));
    }

    @Test
    @DisplayName("updateStatusOf(UNKNOWN status) → SsfStreamException — UNKNOWN is not a sendable value")
    void updateStatusRejectsUnknownStatus() {
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> streamClient.updateStatusOf("stream-1", StreamStatus.Status.UNKNOWN, null));
        assertThat(ex.getMessage().toLowerCase(), containsString("status"));
    }

    @Test
    @DisplayName("addSubjectFor with empty subject map → SsfStreamException")
    void addSubjectRejectsEmptyMap() {
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> streamClient.addSubjectFor("stream-1", Map.of(), null));
        assertThat(ex.getMessage().toLowerCase(), containsString("subject"));
    }

    @Test
    @DisplayName("addSubjectFor with subject missing 'format' → SsfStreamException citing the missing field")
    void addSubjectRequiresFormatField() {
        // Looks plausible (has an id) but missing the required SSF "format".
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> streamClient.addSubjectFor("stream-1", Map.of("id", "user-1"), null));
        assertThat(ex.getMessage().toLowerCase(), containsString("format"));
    }
}
