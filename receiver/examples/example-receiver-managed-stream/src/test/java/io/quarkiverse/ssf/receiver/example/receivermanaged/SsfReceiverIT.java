package io.quarkiverse.ssf.receiver.example.receivermanaged;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * {@link SsfReceiverTest} against the packaged application, the native binary with
 * {@code -Pnative}: the one place the JSON support shaded in Nimbus and the virtual
 * threads of easyssf are exercised under GraalVM.
 */
@QuarkusIntegrationTest
public class SsfReceiverIT extends SsfReceiverTest {
}
