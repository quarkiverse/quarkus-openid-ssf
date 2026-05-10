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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventContext;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventHandler;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventToken;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * Layer-2 test: the push-endpoint's shared-secret check honors
 * {@code ssf.receiver.push.expected-auth-header} exactly. Wrong / missing
 * Authorization → 401, correct → 202 + handler invocation.
 *
 * <p>
 * Also covers the "handler throws → still 202" contract (the SET was
 * accepted, the handler's failure is logged but doesn't surface to the
 * transmitter).
 */
public class PushRouteAuthTest {

    private static final String ISSUER = "https://test.transmitter/realms/r1";
    private static final String AUDIENCE = "https://my-receiver.example/ssf";
    private static final String SHARED_SECRET = "Bearer s3cret-shared";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(ThrowingHandler.class, JwksWireMock.class))
            .setBeforeAllCustomizer(() -> JwksWireMock.start(ISSUER, AUDIENCE))
            .setAfterAllCustomizer(JwksWireMock::stop)
            .overrideConfigKey("ssf.receiver.transmitter-issuer", ISSUER)
            .overrideConfigKey("ssf.receiver.expected-audience", AUDIENCE)
            .overrideConfigKey("ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("ssf.receiver.stream-id", "stream-1")
            .overrideConfigKey("ssf.receiver.delivery-method", "PUSH")
            .overrideConfigKey("ssf.receiver.push.expected-auth-header", SHARED_SECRET)
            // Disable dedup so the same SET can be posted multiple times
            // without being silently skipped on the second attempt.
            .overrideConfigKey("ssf.receiver.dedup.enabled", "false");

    @Inject
    SsfEventHandler handler;

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    @BeforeEach
    void resetHandler() {
        ((ThrowingHandler) handler).reset();
    }

    @Test
    @DisplayName("Correct Authorization header → 202 + handler invoked")
    void correctAuthAccepts() throws Exception {
        String setJwt = System.getProperty(JwksWireMock.PROP_VALID_SET);

        given()
                .header("Content-Type", "application/secevent+jwt")
                .header("Authorization", SHARED_SECRET)
                .body(setJwt)
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(202);

        ThrowingHandler captured = (ThrowingHandler) handler;
        assertTrue(captured.latch.await(5, TimeUnit.SECONDS), "handler was not invoked within 5s");
        assertNotNull(captured.last.get());
    }

    @Test
    @DisplayName("Wrong Authorization header → 401, handler NOT invoked")
    void wrongAuthRejects() throws Exception {
        String setJwt = System.getProperty(JwksWireMock.PROP_VALID_SET);

        given()
                .header("Content-Type", "application/secevent+jwt")
                .header("Authorization", "Bearer wrong-secret")
                .body(setJwt)
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(401);

        ThrowingHandler captured = (ThrowingHandler) handler;
        // Latch should NOT count down; verify it's still > 0 by waiting briefly.
        // We can't await(0) reliably, so check that waiting 200ms times out.
        boolean handlerWasCalled = captured.latch.await(200, TimeUnit.MILLISECONDS);
        assertTrue(!handlerWasCalled, "handler must not be invoked on 401");
    }

    @Test
    @DisplayName("Missing Authorization header → 401")
    void missingAuthRejects() {
        String setJwt = System.getProperty(JwksWireMock.PROP_VALID_SET);

        given()
                .header("Content-Type", "application/secevent+jwt")
                .body(setJwt)
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("Handler throws → still 202 (the SET was accepted; handler error is logged)")
    void handlerThrowsStill202() throws Exception {
        String setJwt = System.getProperty(JwksWireMock.PROP_VALID_SET);

        ((ThrowingHandler) handler).throwOnNext.set(true);

        given()
                .header("Content-Type", "application/secevent+jwt")
                .header("Authorization", SHARED_SECRET)
                .body(setJwt)
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(202);

        ThrowingHandler captured = (ThrowingHandler) handler;
        // Verify the handler was actually called (and threw) — the exception
        // shouldn't escape into the HTTP response.
        assertTrue(captured.latch.await(5, TimeUnit.SECONDS),
                "handler was not invoked even though SET was accepted");
        assertTrue(captured.threwLastTime.get(),
                "handler should have thrown; the test relies on the exception path");
    }

    @Singleton
    public static class ThrowingHandler implements SsfEventHandler {
        // volatile so test threads observe the new latch immediately after reset().
        volatile CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<SsfEventToken> last = new AtomicReference<>();
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicBoolean throwOnNext = new AtomicBoolean(false);
        final AtomicBoolean threwLastTime = new AtomicBoolean(false);

        @Override
        public void handle(SsfEventContext eventContext) {
            last.set(eventContext.eventToken());
            invocations.incrementAndGet();
            try {
                if (throwOnNext.compareAndSet(true, false)) {
                    threwLastTime.set(true);
                    throw new RuntimeException("handler intentionally throws for the 'still 202' test");
                }
                threwLastTime.set(false);
            } finally {
                latch.countDown();
            }
        }

        void reset() {
            latch = new CountDownLatch(1);
            last.set(null);
            invocations.set(0);
            throwOnNext.set(false);
            threwLastTime.set(false);
        }
    }
}
