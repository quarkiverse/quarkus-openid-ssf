package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;

import jakarta.inject.Inject;

import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * {@code http.max-response-size} bounds what the HTTP client reads from a transmitter: a
 * larger response fails the call instead of being buffered.
 */
public class MaxResponseSizeTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(TestTransmitters::archive)
            .setBeforeAllCustomizer(TestTransmitters::start)
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", "https://receiver.example")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.http.max-response-size", "64");

    @Inject
    SsfHttpClient httpClient;

    @Test
    @DisplayName("http.max-response-size=64 -> the JWK Set of the transmitter is too large to be read")
    void largerResponseFails() {
        assertEquals(64, ((JdkSsfHttpClient) httpClient).getMaxResponseSize());
        URI jwks = URI.create(TestTransmitters.current().jwksUri());
        IOException e = assertThrows(IOException.class, () -> httpClient.execute(SsfHttpRequest.get(jwks)));
        assertThat(e.getMessage(), containsString(jwks.toString()));
    }
}
