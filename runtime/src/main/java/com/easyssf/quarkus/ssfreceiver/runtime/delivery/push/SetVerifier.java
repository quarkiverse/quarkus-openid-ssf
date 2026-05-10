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
package com.easyssf.quarkus.ssfreceiver.runtime.delivery.push;

import java.text.ParseException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.easyssf.quarkus.ssfreceiver.runtime.SsfReceiverConfig;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfEventToken;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@ApplicationScoped
public class SetVerifier {

    @Inject
    SsfReceiverConfig config;

    @Inject
    JwksResolver jwks;

    public SsfEventToken verify(String setJwt) throws SsfVerificationException {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(setJwt);
        } catch (ParseException e) {
            throw new SsfVerificationException("SET could not be parsed as a signed JWT", e);
        }

        String kid = jwt.getHeader().getKeyID();
        if (kid == null || kid.isBlank()) {
            throw new SsfVerificationException("SET header is missing kid");
        }

        JWK key = jwks.find(kid);
        if (key == null) {
            throw new SsfVerificationException("No JWK found for kid " + kid);
        }

        try {
            if (!jwt.verify(verifierFor(key, jwt.getHeader().getAlgorithm()))) {
                throw new SsfVerificationException("SET signature did not verify");
            }
        } catch (JOSEException e) {
            throw new SsfVerificationException("SET signature verification failed", e);
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new SsfVerificationException("SET claims could not be parsed", e);
        }

        String iss = claims.getIssuer();
        if (iss == null || iss.isBlank()) {
            throw new SsfVerificationException("SET is missing iss");
        }
        String expectedIss = config.transmitterIssuer().toString();
        if (!expectedIss.equals(iss)) {
            throw new SsfVerificationException("SET iss " + iss + " does not match transmitter-issuer " + expectedIss);
        }

        Date iatDate = claims.getIssueTime();
        if (iatDate == null) {
            throw new SsfVerificationException("SET is missing iat");
        }

        String jti = claims.getJWTID();
        if (jti == null || jti.isBlank()) {
            throw new SsfVerificationException("SET is missing jti");
        }

        List<String> audience = claims.getAudience();
        List<String> safeAudience = audience != null ? List.copyOf(audience) : List.of();

        Optional<String> expectedAudience = config.expectedAudience();
        if (expectedAudience.isPresent() && !safeAudience.contains(expectedAudience.get())) {
            throw new SsfVerificationException(
                    "SET aud " + safeAudience + " does not contain expected audience " + expectedAudience.get());
        }

        Map<String, Object> events = readObjectClaim(claims, "events");
        Map<String, Object> subjectId = readObjectClaim(claims, "sub_id");
        String txn = readStringClaim(claims, "txn");
        Map<String, Object> additional = additionalClaims(claims);

        return new SsfEventToken(
                jti,
                iss,
                Instant.ofEpochMilli(iatDate.getTime()),
                safeAudience,
                events,
                subjectId,
                txn,
                additional);
    }

    /**
     * Claim names already surfaced by dedicated {@link SsfEventToken} accessors —
     * everything else gets bundled into {@code additionalProperties} so the
     * consumer can read e.g. {@code exp} / {@code nbf} / {@code sub} or
     * transmitter-specific extensions without losing the data.
     */
    private static final Set<String> MODELLED_CLAIMS = Set.of(
            "jti",
            "iss",
            "iat",
            "aud",
            "events",
            "sub_id",
            "txn");

    private static Map<String, Object> additionalClaims(JWTClaimsSet claims) {
        Map<String, Object> all = claims.getClaims();
        if (all == null || all.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : all.entrySet()) {
            if (!MODELLED_CLAIMS.contains(e.getKey())) {
                extra.put(e.getKey(), e.getValue());
            }
        }
        return Collections.unmodifiableMap(extra);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObjectClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap((Map<String, Object>) map);
        }
        return null;
    }

    private static String readStringClaim(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (ParseException e) {
            return null;
        }
    }

    private static JWSVerifier verifierFor(JWK jwk, JWSAlgorithm alg) throws JOSEException, SsfVerificationException {
        if (jwk instanceof RSAKey rsa) {
            return new RSASSAVerifier(rsa);
        }
        if (jwk instanceof ECKey ec) {
            return new ECDSAVerifier(ec);
        }
        throw new SsfVerificationException("Unsupported JWK type for alg " + alg + ": " + jwk.getKeyType());
    }
}
