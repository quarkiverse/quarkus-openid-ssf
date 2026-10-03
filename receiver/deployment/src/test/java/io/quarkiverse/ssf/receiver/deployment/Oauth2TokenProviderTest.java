package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The OAuth2 client credentials provider ({@code oauth2.*}) with
 * {@code client_secret_post}: the token endpoint of the test transmitter accepts the
 * credentials in the form body, tokens are cached, and a token the transmitter no longer
 * accepts is replaced on the spot.
 */
public class Oauth2TokenProviderTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
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
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.client-auth-method", "post")
            .overrideConfigKey("quarkus.openid-ssf.receiver.oauth2.scopes", "ssf.read,ssf.manage");

    @Inject
    SsfReceiverStreamClient streamClient;

    @Inject
    SsfTransmitters transmitters;

    @Test
    @DisplayName("The client credentials provider is selected, tokens are cached and renewed after a 401")
    void tokensAreObtainedCachedAndRenewed() {
        assertThat(transmitters.primary().orElseThrow().getTokenProvider().getClass().getSimpleName(),
                containsString("ClientCredentials"));
        TestTransmitter transmitter = TestTransmitters.current();
        // the stream lookup at startup obtained the first token
        TestTransmitters.awaitRegistered(transmitters.primary().orElseThrow());
        assertEquals(1, transmitter.tokenRequests());

        SsfStreamStatus status = streamClient.status();
        assertThat(status.status(), equalTo(SsfStreamStatus.ENABLED));
        assertEquals(1, transmitter.tokenRequests(), "the cached token is reused");

        transmitter.expireAccessTokens();
        assertThat(streamClient.status().status(), equalTo(SsfStreamStatus.ENABLED));
        assertEquals(2, transmitter.tokenRequests(), "a 401 makes the provider obtain a new token");
    }
}
