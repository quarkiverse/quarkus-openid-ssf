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

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * Layer-2 boundary tests for {@code SetVerifier} — every documented
 * verification-failure mode produces 400. Mints defective SETs in the
 * {@code setBeforeAllCustomizer} lambda (test classloader, same place as
 * {@link JwksWireMock#start}) and bridges them into the Quarkus classloader
 * via system properties — same pattern as the existing smoke test.
 */
public class SetVerifierVerificationTest {

    private static final String ISSUER = "https://test.transmitter/realms/r1";
    private static final String AUDIENCE = "https://my-receiver.example/ssf";

    private static final String PROP_BAD_SIGNATURE_SET = "test.ssf.bad-signature-set";
    private static final String PROP_MISSING_ISS_SET = "test.ssf.missing-iss-set";
    private static final String PROP_WRONG_ISS_SET = "test.ssf.wrong-iss-set";
    private static final String PROP_MISSING_IAT_SET = "test.ssf.missing-iat-set";
    private static final String PROP_MISSING_JTI_SET = "test.ssf.missing-jti-set";
    private static final String PROP_UNKNOWN_KID_SET = "test.ssf.unknown-kid-set";
    private static final String PROP_RS384_SET = "test.ssf.rs384-set";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(JwksWireMock.class))
            .setBeforeAllCustomizer(() -> {
                JwksWireMock.start(ISSUER, AUDIENCE);
                mintVerificationVariants();
            })
            .setAfterAllCustomizer(() -> {
                JwksWireMock.stop();
                System.clearProperty(PROP_BAD_SIGNATURE_SET);
                System.clearProperty(PROP_MISSING_ISS_SET);
                System.clearProperty(PROP_WRONG_ISS_SET);
                System.clearProperty(PROP_MISSING_IAT_SET);
                System.clearProperty(PROP_MISSING_JTI_SET);
                System.clearProperty(PROP_UNKNOWN_KID_SET);
                System.clearProperty(PROP_RS384_SET);
            })
            .overrideConfigKey("ssf.receiver.transmitter-issuer", ISSUER)
            .overrideConfigKey("ssf.receiver.expected-audience", AUDIENCE)
            .overrideConfigKey("ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("ssf.receiver.stream-id", "stream-1")
            .overrideConfigKey("ssf.receiver.delivery-method", "PUSH")
            // Disable the startup probe — we don't care about the stream config
            // here, only the verifier's claim checks.
            .overrideConfigKey("ssf.receiver.transmitter-managed.probe-on-startup", "false");

    private static void mintVerificationVariants() {
        try {
            // 1. Signed by an attacker key with the same kid the JWKS advertises —
            //    the receiver will fetch the JWK by kid and the signature won't verify.
            RSAKey attackerKey = JwksWireMock.newAttackerKey(JwksWireMock.kid());
            String badSig = JwksWireMock.signClaims(
                    JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build(),
                    attackerKey, JwksWireMock.kid());
            System.setProperty(PROP_BAD_SIGNATURE_SET, badSig);

            // 2. Missing iss claim — Nimbus serializes a builder without setIssuer
            //    as a JWT with no "iss" key.
            JWTClaimsSet missingIss = new JWTClaimsSet.Builder(
                    JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build())
                    .issuer(null)
                    .build();
            System.setProperty(PROP_MISSING_ISS_SET, JwksWireMock.signClaims(
                    missingIss, JwksWireMock.rsaKey(), JwksWireMock.kid()));

            // 3. iss != ssf.receiver.transmitter-issuer
            JWTClaimsSet wrongIss = new JWTClaimsSet.Builder(
                    JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build())
                    .issuer("https://attacker.example/realms/evil")
                    .build();
            System.setProperty(PROP_WRONG_ISS_SET, JwksWireMock.signClaims(
                    wrongIss, JwksWireMock.rsaKey(), JwksWireMock.kid()));

            // 4. Missing iat claim
            JWTClaimsSet missingIat = new JWTClaimsSet.Builder(
                    JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build())
                    .issueTime(null)
                    .build();
            System.setProperty(PROP_MISSING_IAT_SET, JwksWireMock.signClaims(
                    missingIat, JwksWireMock.rsaKey(), JwksWireMock.kid()));

            // 5. Missing jti claim
            JWTClaimsSet missingJti = new JWTClaimsSet.Builder(
                    JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build())
                    .jwtID(null)
                    .build();
            System.setProperty(PROP_MISSING_JTI_SET, JwksWireMock.signClaims(
                    missingJti, JwksWireMock.rsaKey(), JwksWireMock.kid()));

            // 6. Unknown kid in the JWS header — JwksResolver refreshes once,
            //    still doesn't find it, fails.
            String unknownKidSet = JwksWireMock.signClaims(
                    JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build(),
                    JwksWireMock.rsaKey(), "unknown-kid-not-in-jwks");
            System.setProperty(PROP_UNKNOWN_KID_SET, unknownKidSet);

            // 7. Signed with RS384 (the JWKS key is RSA, so the signature would
            //    verify) — but RS384 isn't in the default set-validation
            //    accepted-algorithms allowlist [RS256], so the verifier rejects
            //    it before signature check (CAEP Interop §3.1).
            System.setProperty(PROP_RS384_SET,
                    signWithAlg(JwksWireMock.canonicalClaims(ISSUER, AUDIENCE).build(),
                            JwksWireMock.rsaKey(), JwksWireMock.kid(),
                            com.nimbusds.jose.JWSAlgorithm.RS384));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint verification variants", e);
        }
    }

    /** Sign a SET with an arbitrary JWS alg — JwksWireMock.signClaims always uses RS256. */
    private static String signWithAlg(
            com.nimbusds.jwt.JWTClaimsSet claims,
            com.nimbusds.jose.jwk.RSAKey signingKey,
            String kid,
            com.nimbusds.jose.JWSAlgorithm alg) throws Exception {
        com.nimbusds.jose.JWSHeader header = new com.nimbusds.jose.JWSHeader.Builder(alg)
                .type(new com.nimbusds.jose.JOSEObjectType("secevent+jwt"))
                .keyID(kid)
                .build();
        com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(header, claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(signingKey.toRSAPrivateKey()));
        return jwt.serialize();
    }

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    private static void postAndExpect400(String setJwt) {
        given()
                .header("Content-Type", "application/secevent+jwt")
                .body(setJwt)
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("SET signed by a different key → 400")
    void badSignature() {
        postAndExpect400(System.getProperty(PROP_BAD_SIGNATURE_SET));
    }

    @Test
    @DisplayName("SET with missing iss → 400")
    void missingIss() {
        postAndExpect400(System.getProperty(PROP_MISSING_ISS_SET));
    }

    @Test
    @DisplayName("SET with iss that doesn't match transmitter-issuer → 400")
    void wrongIss() {
        postAndExpect400(System.getProperty(PROP_WRONG_ISS_SET));
    }

    @Test
    @DisplayName("SET with missing iat → 400")
    void missingIat() {
        postAndExpect400(System.getProperty(PROP_MISSING_IAT_SET));
    }

    @Test
    @DisplayName("SET with missing jti → 400")
    void missingJti() {
        postAndExpect400(System.getProperty(PROP_MISSING_JTI_SET));
    }

    @Test
    @DisplayName("SET with kid not present in JWKS → 400 (after one refresh)")
    void unknownKid() {
        postAndExpect400(System.getProperty(PROP_UNKNOWN_KID_SET));
    }

    @Test
    @DisplayName("Empty body → 400")
    void emptyBody() {
        given()
                .header("Content-Type", "application/secevent+jwt")
                .body("")
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("SET signed with RS384 → 400 (alg not in accepted-algorithms allowlist; CAEP Interop §3.1 → RS256 only)")
    void disallowedAlgorithmRejected() {
        postAndExpect400(System.getProperty(PROP_RS384_SET));
    }

    @Test
    @DisplayName("Body that isn't a parseable JWT → 400")
    void notAJwt() {
        given()
                .header("Content-Type", "application/secevent+jwt")
                .body("definitely-not-a-jwt")
                .when()
                .post("/ssf/push")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("SET with wrong audience → 400 (smoke harness already mints this one)")
    void wrongAudience() {
        // PROP_WRONG_AUD_SET is set by JwksWireMock.start(); reuse it here so
        // every aud-validation case lives in one test class.
        postAndExpect400(System.getProperty(JwksWireMock.PROP_WRONG_AUD_SET));
    }
}
