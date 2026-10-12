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
 * A receiver has to know the audience of its SETs. Without {@code expected-audience} and
 * without a stream it registers or looks up ({@code stream-management=RECEIVER},
 * {@code stream-id}) the start fails, naming the three ways out: the property, the
 * stream, or {@code set-validation.allow-any-audience}.
 */
public class AudienceRequiredTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer", "https://idp.example/realms/test")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .assertException(failure -> {
                String messages = "";
                for (Throwable t = failure; t != null; t = t.getCause()) {
                    messages += t.getMessage() + "\n";
                }
                assertThat(messages, containsString("quarkus.openid-ssf.receiver.expected-audience"));
                assertThat(messages, containsString("quarkus.openid-ssf.receiver.stream-management=RECEIVER"));
                assertThat(messages, containsString("quarkus.openid-ssf.receiver.stream-id"));
                assertThat(messages,
                        containsString("quarkus.openid-ssf.receiver.set-validation.allow-any-audience=true"));
            });

    @Test
    @DisplayName("No expected-audience, no stream -> the start fails naming the three ways out")
    void startRefused() {
        fail("the application must not start");
    }
}
