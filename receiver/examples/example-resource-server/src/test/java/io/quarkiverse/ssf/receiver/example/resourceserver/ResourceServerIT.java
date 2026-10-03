package io.quarkiverse.ssf.receiver.example.resourceserver;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * {@link ResourceServerTest} against the packaged application, the native binary with
 * {@code -Pnative}: exercises the JSON support shaded in Nimbus and the virtual threads
 * of easyssf under GraalVM.
 */
@QuarkusIntegrationTest
public class ResourceServerIT extends ResourceServerTest {
}
