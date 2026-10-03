package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.easyssf.core.stream.SsfStreamStatus;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;
import io.quarkus.test.QuarkusUnitTest;

/**
 * The OAuth2 client credentials provider with {@code client_secret_basic} (the default):
 * the credentials go into the {@code Authorization} header of the token request, which
 * the token endpoint of the test transmitter verifies.
 */
public class Oauth2TokenProviderBasicTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive())
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                TestTransmitters.addPushStream(TestTransmitters.DEFAULT, transmitter);
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.token-endpoint",
                    TestTransmitters.ref(TestTransmitters.tokenUriProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-id", TestTransmitter.CLIENT_ID)
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-secret", TestTransmitter.CLIENT_SECRET)
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-auth-method", "basic");

    @Inject
    SsfReceiverStreamClient streamClient;

    @Inject
    SsfTransmitters transmitters;

    @Test
    @DisplayName("Basic authentication at the token endpoint -> the stream API can be called")
    void basicAuthenticationAtTheTokenEndpoint() {
        TestTransmitters.awaitRegistered(transmitters.primary().orElseThrow());
        SsfStreamStatus status = streamClient.status();
        assertThat(status.status(), equalTo(SsfStreamStatus.ENABLED));
        assertEquals(1, TestTransmitters.current().tokenRequests());
    }
}
