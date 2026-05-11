package io.quarkiverse.ssf.receiver.deployment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Boots a WireMock SSF transmitter that exposes:
 * GET /.well-known/ssf-configuration — metadata pointing at /jwks and /streams/status
 * GET /jwks — JWKS backed by an in-process RSA key
 * GET /streams/status?stream_id=... — stream status response
 *
 * Pre-signs SETs with the RSA key so the test method (running in the Quarkus classloader)
 * has no static-state dependency on this class — everything is passed via System
 * properties.
 */
final class JwksWireMock {

    static final String PROP_METADATA_URL = "quarkus.openid-ssf.receiver.transmitter-metadata-url";
    static final String PROP_VALID_SET = "test.ssf.valid-set";
    static final String PROP_WRONG_AUD_SET = "test.ssf.wrong-aud-set";
    static final String PROP_EXPECTED_JTI = "test.ssf.expected-jti";
    static final String PROP_EXPECTED_IAT_SEC = "test.ssf.expected-iat-sec";
    static final String PROP_EXPECTED_ISS = "test.ssf.expected-iss";
    static final String PROP_EXPECTED_AUD = "test.ssf.expected-aud";
    static final String PROP_EXPECTED_TXN = "test.ssf.expected-txn";
    static final String PROP_EXPECTED_EVENT_TYPE = "test.ssf.expected-event-type";
    static final String PROP_EXPECTED_SUBJECT_FORMAT = "test.ssf.expected-subject-format";
    static final String PROP_EXPECTED_SUBJECT_VALUE = "test.ssf.expected-subject-value";
    static final String PROP_EXPECTED_STREAM_STATUS = "test.ssf.expected-stream-status";
    static final String PROP_EXPECTED_STREAM_REASON = "test.ssf.expected-stream-reason";
    static final String PROP_WIREMOCK_PORT = "test.ssf.wiremock-port";
    static final String PROP_POLL_URL = "test.ssf.poll-url";
    /**
     * Private RSAKey serialized to JWK JSON, so test methods running in the
     * Quarkus classloader (where {@link #rsaKey()} is null) can re-hydrate
     * the signing key to mint custom SETs.
     */
    static final String PROP_PRIVATE_JWK = "test.ssf.private-jwk";

    static final String EVENT_TYPE = "https://schemas.openid.net/secevent/caep/event-type/session-revoked";
    static final String SUBJECT_FORMAT = "opaque";
    static final String SUBJECT_VALUE = "user-1234";
    static final String STREAM_ID = "stream-1";
    static final String STREAM_STATUS = "paused";
    static final String STREAM_REASON = "SYSTEM_DOWN_FOR_MAINTENANCE";

    private static final String KID = "test-key-1";
    private static final String METADATA_PATH = "/.well-known/ssf-configuration";
    private static final String JWKS_PATH = "/jwks";
    private static final String STATUS_PATH = "/streams/status";
    private static final String CONFIG_PATH = "/streams/configuration";
    private static final String VERIFY_PATH = "/streams/verify";
    private static final String ADD_SUBJECT_PATH = "/streams/subjects/add";
    private static final String REMOVE_SUBJECT_PATH = "/streams/subjects/remove";

    static final int MIN_VERIFICATION_INTERVAL = 60;
    static final int INACTIVITY_TIMEOUT = 3600;
    static final String STREAM_DESCRIPTION = "test stream";

    static final String POLL_PATH = "/poll";

    /** Which transport the wiremock advertises in the stream config's delivery block. */
    enum DeliveryMode {
        PUSH,
        POLL
    }

    private static WireMockServer server;
    private static RSAKey rsaKey;

    private JwksWireMock() {
    }

    static void start(String issuer, String expectedAudience) {
        start(issuer, expectedAudience, DeliveryMode.PUSH);
    }

    static void start(String issuer, String expectedAudience, DeliveryMode mode) {
        try {
            rsaKey = new RSAKeyGenerator(2048).keyID(KID).generate();
            server = new WireMockServer(options().dynamicPort());
            server.start();

            String jwksUrl = server.baseUrl() + JWKS_PATH;
            String statusUrl = server.baseUrl() + STATUS_PATH;
            String configUrl = server.baseUrl() + CONFIG_PATH;
            String verifyUrl = server.baseUrl() + VERIFY_PATH;
            String addSubjectUrl = server.baseUrl() + ADD_SUBJECT_PATH;
            String removeSubjectUrl = server.baseUrl() + REMOVE_SUBJECT_PATH;
            String pushDeliveryUrl = "https://my-receiver.example/ssf/push";
            String pollDeliveryUrl = server.baseUrl() + POLL_PATH;

            String deliveryMethodUrn = mode == DeliveryMode.POLL
                    ? "urn:ietf:rfc:8936"
                    : "urn:ietf:rfc:8935";
            String deliveryEndpointUrl = mode == DeliveryMode.POLL
                    ? pollDeliveryUrl
                    : pushDeliveryUrl;

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("spec_version", "1.0");
            metadata.put("issuer", issuer);
            metadata.put("jwks_uri", jwksUrl);
            metadata.put("status_endpoint", statusUrl);
            metadata.put("configuration_endpoint", configUrl);
            metadata.put("verification_endpoint", verifyUrl);
            metadata.put("add_subject_endpoint", addSubjectUrl);
            metadata.put("remove_subject_endpoint", removeSubjectUrl);
            metadata.put("delivery_methods_supported",
                    List.of("urn:ietf:rfc:8935", "urn:ietf:rfc:8936"));

            server.stubFor(get(urlEqualTo(METADATA_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(JSONObjectUtils.toJSONString(metadata))));

            server.stubFor(get(urlEqualTo(JWKS_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(new JWKSet(rsaKey.toPublicJWK()).toString())));

            Map<String, Object> statusBody = new LinkedHashMap<>();
            statusBody.put("stream_id", STREAM_ID);
            statusBody.put("status", STREAM_STATUS);
            statusBody.put("reason", STREAM_REASON);
            server.stubFor(get(urlEqualTo(STATUS_PATH + "?stream_id=" + STREAM_ID))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(JSONObjectUtils.toJSONString(statusBody))));

            Map<String, Object> deliveryBody = new LinkedHashMap<>();
            deliveryBody.put("method", deliveryMethodUrn);
            deliveryBody.put("endpoint_url", deliveryEndpointUrl);
            Map<String, Object> configBody = new LinkedHashMap<>();
            configBody.put("stream_id", STREAM_ID);
            configBody.put("iss", issuer);
            configBody.put("aud", List.of(expectedAudience));
            configBody.put("events_supported", List.of(EVENT_TYPE));
            configBody.put("events_requested", List.of(EVENT_TYPE));
            configBody.put("events_delivered", List.of(EVENT_TYPE));
            configBody.put("delivery", deliveryBody);
            configBody.put("min_verification_interval", MIN_VERIFICATION_INTERVAL);
            configBody.put("inactivity_timeout", INACTIVITY_TIMEOUT);
            configBody.put("description", STREAM_DESCRIPTION);
            server.stubFor(get(urlEqualTo(CONFIG_PATH + "?stream_id=" + STREAM_ID))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(JSONObjectUtils.toJSONString(configBody))));

            server.stubFor(post(urlEqualTo(VERIFY_PATH))
                    .willReturn(aResponse().withStatus(204)));

            server.stubFor(post(urlEqualTo(ADD_SUBJECT_PATH))
                    .willReturn(aResponse().withStatus(200)));

            server.stubFor(post(urlEqualTo(REMOVE_SUBJECT_PATH))
                    .willReturn(aResponse().withStatus(204)));

            String jti = UUID.randomUUID().toString();
            String txn = "txn-" + UUID.randomUUID();
            long iatSec = System.currentTimeMillis() / 1000L;

            String validSet = sign(issuer, jti, iatSec, List.of(expectedAudience), txn, true);
            String wrongAudSet = sign(issuer, UUID.randomUUID().toString(), iatSec,
                    List.of("https://some.other.receiver.example/"),
                    "txn-mismatch", true);

            System.setProperty(PROP_WIREMOCK_PORT, Integer.toString(server.port()));
            System.setProperty(PROP_POLL_URL, pollDeliveryUrl);
            System.setProperty(PROP_PRIVATE_JWK, rsaKey.toJSONString());
            System.setProperty(PROP_METADATA_URL, server.baseUrl() + METADATA_PATH);
            System.setProperty(PROP_VALID_SET, validSet);
            System.setProperty(PROP_WRONG_AUD_SET, wrongAudSet);
            System.setProperty(PROP_EXPECTED_JTI, jti);
            System.setProperty(PROP_EXPECTED_IAT_SEC, Long.toString(iatSec));
            System.setProperty(PROP_EXPECTED_ISS, issuer);
            System.setProperty(PROP_EXPECTED_AUD, expectedAudience);
            System.setProperty(PROP_EXPECTED_TXN, txn);
            System.setProperty(PROP_EXPECTED_EVENT_TYPE, EVENT_TYPE);
            System.setProperty(PROP_EXPECTED_SUBJECT_FORMAT, SUBJECT_FORMAT);
            System.setProperty(PROP_EXPECTED_SUBJECT_VALUE, SUBJECT_VALUE);
            System.setProperty(PROP_EXPECTED_STREAM_STATUS, STREAM_STATUS);
            System.setProperty(PROP_EXPECTED_STREAM_REASON, STREAM_REASON);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set up JwksWireMock", e);
        }
    }

    /**
     * Test-only accessor: the RSA key the wiremock JWKS advertises. Use it to
     * mint custom SETs that the receiver should accept (signed with this key).
     * Mint SETs with a different key by calling {@link #signClaims(JWTClaimsSet, RSAKey, String)}
     * with a freshly-generated key — the receiver should reject those.
     */
    static RSAKey rsaKey() {
        return rsaKey;
    }

    /** The {@code kid} the wiremock JWKS advertises. */
    static String kid() {
        return KID;
    }

    /**
     * Test-only accessor: the running WireMock server, so individual tests
     * can register / reset stubs (e.g. POLL test cases that return different
     * SET batches per @Test).
     */
    static WireMockServer server() {
        return server;
    }

    /** Absolute URL of the poll endpoint advertised in the stream config (POLL mode). */
    static String pollUrl() {
        return server.baseUrl() + POLL_PATH;
    }

    /**
     * Signs an arbitrary {@link JWTClaimsSet} with the given key and {@code kid}.
     * Used by verification tests to construct SETs with missing / wrong claims
     * or wrong signing keys.
     */
    static String signClaims(JWTClaimsSet claims, RSAKey signingKey, String kid) throws Exception {
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType("secevent+jwt"));
        if (kid != null) {
            header.keyID(kid);
        }
        SignedJWT jwt = new SignedJWT(header.build(), claims);
        jwt.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
        return jwt.serialize();
    }

    /**
     * Mint a fresh RSA key for "signed by an attacker" tests. The receiver
     * fetches the wiremock JWKS, which only carries the canonical key ID, so
     * SETs signed with this key should fail verification.
     */
    static RSAKey newAttackerKey(String kid) throws Exception {
        return new RSAKeyGenerator(2048).keyID(kid).generate();
    }

    /**
     * Convenience: a fully-formed claims-set builder pre-populated with the
     * canonical issuer/jti/iat/aud/sub_id/events/txn so verification tests
     * can flip exactly one field and assert the rejection.
     */
    static JWTClaimsSet.Builder canonicalClaims(String issuer, String audience) {
        Map<String, Object> subjectId = new LinkedHashMap<>();
        subjectId.put("format", SUBJECT_FORMAT);
        subjectId.put("id", SUBJECT_VALUE);
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(EVENT_TYPE, Map.of("initiating_entity", "policy"));
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(new Date(System.currentTimeMillis()))
                .claim("sub_id", subjectId)
                .claim("txn", "txn-" + UUID.randomUUID())
                .claim("events", events);
    }

    private static String sign(String issuer, String jti, long iatSec, List<String> audience,
            String txn, boolean withSsfPayload) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .jwtID(jti)
                .issueTime(new Date(iatSec * 1000L));
        if (withSsfPayload) {
            Map<String, Object> subjectId = new LinkedHashMap<>();
            subjectId.put("format", SUBJECT_FORMAT);
            subjectId.put("id", SUBJECT_VALUE);
            builder.claim("sub_id", subjectId);
            builder.claim("txn", txn);
            Map<String, Object> events = new LinkedHashMap<>();
            events.put(EVENT_TYPE, Map.of("initiating_entity", "policy"));
            builder.claim("events", events);
        }
        JWTClaimsSet claims = builder.build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(KID)
                        .type(new JOSEObjectType("secevent+jwt"))
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
        return jwt.serialize();
    }

    static void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        rsaKey = null;
        System.clearProperty(PROP_WIREMOCK_PORT);
        System.clearProperty(PROP_POLL_URL);
        System.clearProperty(PROP_PRIVATE_JWK);
        System.clearProperty(PROP_METADATA_URL);
        System.clearProperty(PROP_VALID_SET);
        System.clearProperty(PROP_WRONG_AUD_SET);
        System.clearProperty(PROP_EXPECTED_JTI);
        System.clearProperty(PROP_EXPECTED_IAT_SEC);
        System.clearProperty(PROP_EXPECTED_ISS);
        System.clearProperty(PROP_EXPECTED_AUD);
        System.clearProperty(PROP_EXPECTED_TXN);
        System.clearProperty(PROP_EXPECTED_EVENT_TYPE);
        System.clearProperty(PROP_EXPECTED_SUBJECT_FORMAT);
        System.clearProperty(PROP_EXPECTED_SUBJECT_VALUE);
        System.clearProperty(PROP_EXPECTED_STREAM_STATUS);
        System.clearProperty(PROP_EXPECTED_STREAM_REASON);
    }
}
