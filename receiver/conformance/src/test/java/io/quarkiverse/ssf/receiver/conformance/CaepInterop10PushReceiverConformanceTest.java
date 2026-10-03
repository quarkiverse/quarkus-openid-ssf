package io.quarkiverse.ssf.receiver.conformance;

import org.easyssf.test.conformance.receiver.ConformancePlan;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class CaepInterop10PushReceiverConformanceTest extends QuarkusReceiverConformanceTest {

    @Override
    protected ConformancePlan plan() {
        return ConformancePlan.CAEP_INTEROP_1_0_PUSH;
    }
}
