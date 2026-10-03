package io.quarkiverse.ssf.receiver.conformance;

import org.easyssf.test.conformance.receiver.ConformancePlan;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class Ssf10PushReceiverConformanceTest extends QuarkusReceiverConformanceTest {

    @Override
    protected ConformancePlan plan() {
        return ConformancePlan.SSF_1_0_PUSH;
    }
}
