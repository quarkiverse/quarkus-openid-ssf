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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventHandler;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventContext;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * Layer-2 test: jti dedup integration. The default
 * {@code InMemorySsfJtiDedupStore} drops a SET whose {@code iss::jti} key
 * matches one already dispatched. The receiver still answers {@code 202}
 * (the SET was successfully received and verified — we just don't deliver
 * it to the handler twice).
 *
 * <p>
 * Layer-1 covers the dedup store itself; this test covers the wiring through
 * {@code SsfPushRoute.handle}.
 */
public class DedupIntegrationTest {

    private static final String ISSUER = "https://test.transmitter/realms/r1";
    private static final String AUDIENCE = "https://my-receiver.example/ssf";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(CountingHandler.class, JwksWireMock.class))
            .setBeforeAllCustomizer(() -> JwksWireMock.start(ISSUER, AUDIENCE))
            .setAfterAllCustomizer(JwksWireMock::stop)
            .overrideConfigKey("ssf.receiver.transmitter-issuer", ISSUER)
            .overrideConfigKey("ssf.receiver.expected-audience", AUDIENCE)
            .overrideConfigKey("ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("ssf.receiver.stream-id", "stream-1")
            .overrideConfigKey("ssf.receiver.delivery-method", "PUSH")
            // Dedup is on by default; explicit for clarity.
            .overrideConfigKey("ssf.receiver.dedup.enabled", "true");

    @Inject
    SsfEventHandler handler;

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    private static void postSet(String setJwt) {
        given()
                .header("Content-Type", "application/secevent+jwt")
                .body(setJwt)
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(202);
    }

    @Test
    @DisplayName("Same SET posted twice → handler invoked exactly once")
    void duplicateSkipped() throws Exception {
        String setJwt = System.getProperty(JwksWireMock.PROP_VALID_SET);
        CountingHandler captured = (CountingHandler) handler;
        captured.reset();

        // First post — should hit the handler.
        postSet(setJwt);
        assertTrue(captured.firstInvocation.await(5, TimeUnit.SECONDS),
                "first post should reach the handler");

        // Second post — same jti+iss, dedup store should skip the dispatch.
        postSet(setJwt);

        // Give the dispatch executor a moment to (not) re-fire. We can't await
        // a "didn't happen" event directly; sleep briefly then assert count.
        Thread.sleep(250);
        assertThat("handler must be invoked exactly once for two posts of the same SET",
                captured.invocations.get(), equalTo(1));
    }

    @Singleton
    public static class CountingHandler implements SsfEventHandler {
        volatile CountDownLatch firstInvocation = new CountDownLatch(1);
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public void handle(SsfEventContext eventContext) {
            invocations.incrementAndGet();
            firstInvocation.countDown();
        }

        void reset() {
            firstInvocation = new CountDownLatch(1);
            invocations.set(0);
        }
    }
}
