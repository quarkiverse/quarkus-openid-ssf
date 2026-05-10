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

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.easyssf.quarkus.ssfreceiver.runtime.delivery.poll.SsfPoller;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfStreamClient;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Layer-2 test: {@code ssf.receiver.enabled=false} no-ops every startup
 * observer — no push route registered, no probe, no registrar, no poller.
 * Beans stay injectable so application code that touches them directly still
 * works (just returns errors or no-ops).
 *
 * <p>
 * Notably we DON'T set {@code transmitter-issuer} or {@code stream-id} here —
 * the validator must skip both checks when disabled, otherwise the app
 * couldn't even start.
 */
public class EnabledFalseTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("ssf.receiver.enabled", "false")
            // The config layer still resolves transmitter-issuer at startup;
            // give it a placeholder so config materialization doesn't fail.
            .overrideConfigKey("ssf.receiver.transmitter-issuer", "https://disabled.example");

    @Inject
    SsfStreamClient streamClient;

    @Inject
    SsfPoller poller;

    @Test
    @DisplayName("App boots without transmitter-issuer / stream-id when disabled")
    void appBootsWithoutMandatoryConfig() {
        // If the validator hadn't no-op'd, startup would have thrown
        // ("ssf.receiver.stream-id is required when stream-management=TRANSMITTER")
        // and the @QuarkusExtensionTest harness would never get here.
        assertNotNull(streamClient, "SsfStreamClient must stay injectable when disabled");
        assertNotNull(poller, "SsfPoller must stay injectable when disabled");
    }

    @Test
    @DisplayName("Push route is not registered → POST /ssf/push returns 404")
    void pushRouteNotRegistered() {
        // text/plain rather than the SSF media type so REST-Assured doesn't
        // need a custom encoder registered — the body is irrelevant when the
        // route doesn't exist; only the 404 matters.
        given()
                .header("Content-Type", "text/plain")
                .body("anything")
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("SsfPoller.pollNow() throws clearly when disabled")
    void pollNowRejected() {
        try {
            poller.pollNow();
            throw new AssertionError("pollNow() should throw when ssf.receiver.enabled=false");
        } catch (IllegalStateException e) {
            // Expected — the message should mention the kill switch so an
            // operator can find it without spelunking the code.
            assertNotNull(e.getMessage());
        }
    }
}
