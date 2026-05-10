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

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventHandler;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventToken;
import com.easyssf.quarkus.ssfreceiver.runtime.metadata.SsfConfigurationResolver;
import com.easyssf.quarkus.ssfreceiver.runtime.metadata.SsfTransmitterMetadata;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfStreamClient;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.StreamConfiguration;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.status.StreamStatus;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.subjects.SsfSubjects;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

public class SsfReceiverSmokeTest {

    private static final String ISSUER = "https://test.transmitter/realms/r1";
    private static final String AUDIENCE = "https://my-receiver.example/ssf";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(CapturingHandler.class, JwksWireMock.class))
            .setBeforeAllCustomizer(() -> JwksWireMock.start(ISSUER, AUDIENCE))
            .setAfterAllCustomizer(JwksWireMock::stop)
            .overrideConfigKey("ssf.receiver.transmitter-issuer", ISSUER)
            .overrideConfigKey("ssf.receiver.expected-audience", AUDIENCE)
            .overrideConfigKey("ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("ssf.receiver.stream-id", "stream-1")
            .overrideConfigKey("ssf.receiver.delivery-method", "PUSH");

    @Inject
    SsfEventHandler handler;

    @Inject
    SsfStreamClient streamClient;

    @Inject
    SsfConfigurationResolver metadataResolver;

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    @Test
    void pushedSetIsVerifiedAndDispatched() throws Exception {
        String setJwt = System.getProperty(JwksWireMock.PROP_VALID_SET);
        String expectedJti = System.getProperty(JwksWireMock.PROP_EXPECTED_JTI);
        long expectedIatSec = Long.parseLong(System.getProperty(JwksWireMock.PROP_EXPECTED_IAT_SEC));
        String expectedIss = System.getProperty(JwksWireMock.PROP_EXPECTED_ISS);
        String expectedAud = System.getProperty(JwksWireMock.PROP_EXPECTED_AUD);
        String expectedTxn = System.getProperty(JwksWireMock.PROP_EXPECTED_TXN);
        String expectedEventType = System.getProperty(JwksWireMock.PROP_EXPECTED_EVENT_TYPE);
        String expectedSubjectFormat = System.getProperty(JwksWireMock.PROP_EXPECTED_SUBJECT_FORMAT);
        String expectedSubjectValue = System.getProperty(JwksWireMock.PROP_EXPECTED_SUBJECT_VALUE);

        given()
                .header("Content-Type", "application/secevent+jwt")
                .body(setJwt)
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(202);

        CapturingHandler capturing = (CapturingHandler) handler;
        assertTrue(capturing.latch.await(5, TimeUnit.SECONDS), "handler was not invoked within 5s");
        SsfEventToken eventToken = capturing.last.get();
        assertNotNull(eventToken);
        assertThat(eventToken.jti(), equalTo(expectedJti));
        assertThat(eventToken.iss(), equalTo(expectedIss));
        assertThat(eventToken.iat(), is(notNullValue()));
        assertThat(eventToken.iat().getEpochSecond(), equalTo(expectedIatSec));

        assertThat(eventToken.aud(), equalTo(List.of(expectedAud)));
        assertThat(eventToken.txn(), equalTo(expectedTxn));

        assertThat(eventToken.subjectId(), is(notNullValue()));
        assertThat(eventToken.subjectId().get("format"), equalTo(expectedSubjectFormat));
        assertThat(eventToken.subjectId().get("id"), equalTo(expectedSubjectValue));

        assertThat(eventToken.events(), is(notNullValue()));
        assertThat(eventToken.events().keySet(), equalTo(java.util.Set.of(expectedEventType)));
        Object eventPayload = eventToken.events().get(expectedEventType);
        assertThat(eventPayload, is(notNullValue()));
        assertThat(((Map<?, ?>) eventPayload).get("initiating_entity"), equalTo("policy"));

        assertThat(capturing.invocations.get(), equalTo(1));
    }

    @Test
    void subjectsCanBeAddedAndRemoved() {
        // Each call returns void on success (200 / 204); reaching the next line means success.
        streamClient.addSubject(SsfSubjects.email("user@example.com"), Boolean.TRUE);
        streamClient.removeSubject(SsfSubjects.email("user@example.com"));

        // iss_sub form
        streamClient.addSubject(SsfSubjects.issSub(ISSUER, "user-1234"), null);
    }

    @Test
    void verificationCanBeRequested() {
        String state = streamClient.requestVerification();
        assertNotNull(state);
        assertThat(state, is(notNullValue()));
        // No exception thrown — the WireMock stub returns 204 No Content as required by §8.1.4.2.
    }

    @Test
    void streamConfigurationIsResolvedFromTransmitter() {
        StreamConfiguration cfg = streamClient.configuration();
        assertNotNull(cfg);
        assertThat(cfg.streamId(), equalTo("stream-1"));
        assertThat(cfg.iss(), equalTo(ISSUER));
        assertThat(cfg.aud(), equalTo(List.of(AUDIENCE)));
        assertThat(cfg.eventsSupported(), is(notNullValue()));
        assertThat(cfg.eventsRequested(), is(notNullValue()));
        assertThat(cfg.eventsDelivered(), is(notNullValue()));
        assertThat(cfg.delivery(), is(notNullValue()));
        assertThat(cfg.delivery().method(), equalTo("urn:ietf:rfc:8935"));
        assertThat(cfg.delivery().endpointUrl(), is(notNullValue()));
        assertThat(cfg.minVerificationInterval(), equalTo(JwksWireMock.MIN_VERIFICATION_INTERVAL));
        assertThat(cfg.inactivityTimeout(), equalTo(JwksWireMock.INACTIVITY_TIMEOUT));
        assertThat(cfg.description(), equalTo(JwksWireMock.STREAM_DESCRIPTION));
    }

    @Test
    void streamStatusIsResolvedFromMetadata() {
        String expectedStatus = System.getProperty(JwksWireMock.PROP_EXPECTED_STREAM_STATUS);
        String expectedReason = System.getProperty(JwksWireMock.PROP_EXPECTED_STREAM_REASON);

        SsfTransmitterMetadata metadata = metadataResolver.get();
        assertNotNull(metadata);
        assertThat(metadata.statusEndpoint(), is(notNullValue()));
        assertThat(metadata.jwksUri(), is(notNullValue()));

        StreamStatus status = streamClient.status();
        assertNotNull(status);
        assertThat(status.streamId(), equalTo("stream-1"));
        assertThat(status.status(), equalTo(expectedStatus));
        assertThat(status.reason(), equalTo(expectedReason));
        assertThat(status.known(), equalTo(StreamStatus.Status.PAUSED));
    }

    @Test
    void setWithMismatchedAudienceIsRejected() {
        String wrongAudSet = System.getProperty(JwksWireMock.PROP_WRONG_AUD_SET);

        given()
                .header("Content-Type", "application/secevent+jwt")
                .body(wrongAudSet)
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(400);
    }

    @Singleton
    public static class CapturingHandler implements SsfEventHandler {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<SsfEventToken> last = new AtomicReference<>();
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public void handle(SsfEventToken event) {
            last.set(event);
            invocations.incrementAndGet();
            latch.countDown();
        }
    }
}
