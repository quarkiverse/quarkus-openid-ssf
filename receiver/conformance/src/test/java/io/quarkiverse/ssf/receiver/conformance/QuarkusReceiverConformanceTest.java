package io.quarkiverse.ssf.receiver.conformance;

import jakarta.inject.Inject;

import org.easyssf.test.conformance.receiver.AbstractReceiverConformanceTest;
import org.easyssf.test.conformance.receiver.ConformanceRunner;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;

/**
 * The conformance plans against the Quarkus receiver under test. The application listens
 * with TLS on {@link AbstractReceiverConformanceTest#receiverPort()}, see
 * {@code src/test/resources/application.properties}; {@link ConformanceSuiteResource}
 * starts the suite and points the application at it. The subclasses carry
 * {@code @QuarkusTest}: Quarkus registers only the annotated class itself as a bean.
 */
@WithTestResource(value = ConformanceSuiteResource.class, scope = TestResourceScope.MATCHING_RESOURCES)
abstract class QuarkusReceiverConformanceTest extends AbstractReceiverConformanceTest {

    @Inject
    ConformanceRunner runner;

    @Override
    protected ConformanceRunner runner() {
        return runner;
    }
}
