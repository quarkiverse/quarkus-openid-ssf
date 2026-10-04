package io.quarkiverse.ssf.receiver.example.scim;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * {@link ScimProvisioningTest} against the packaged application, the native binary with
 * {@code -Pnative}: the SCIM Events of easyssf under GraalVM.
 */
@QuarkusIntegrationTest
public class ScimProvisioningIT extends ScimProvisioningTest {
}
