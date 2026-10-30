package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.fail;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * The access token is sent to the poll endpoint, so a configured
 * {@code poll.endpoint-url} is held to the URI policy of the transmitter: plain
 * {@code http} on a host that is not a loopback address fails the start, naming the
 * property.
 */
public class PollEndpointInsecureRefusedTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer", "https://idp.example/realms/test")
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", "https://receiver.example")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "POLL")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.endpoint-url", "http://idp.example/realms/test/poll")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", "token")
            .assertException(failure -> {
                String messages = "";
                for (Throwable t = failure; t != null; t = t.getCause()) {
                    messages += t.getMessage() + "\n";
                }
                assertThat(messages, containsString("quarkus.openid-ssf.receiver.poll.endpoint-url"));
                assertThat(messages, containsString("http://idp.example/realms/test/poll"));
            });

    @Test
    @DisplayName("poll.endpoint-url with plain http on a remote host -> the start fails naming the property")
    void startRefused() {
        fail("the application must not start");
    }
}
