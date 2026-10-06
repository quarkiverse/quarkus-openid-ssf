package io.quarkiverse.ssf.receiver.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPollScheduler;
import io.quarkiverse.ssf.receiver.runtime.devui.SsfDevJsonRpcService;
import io.quarkus.test.QuarkusUnitTest;

/**
 * The JSON-RPC service behind the Dev UI (added to the archive as a bean, as the
 * processor only registers it in dev mode) describes the poller of a transmitter with
 * POLL delivery.
 */
public class DevUiPollStatusTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive(SsfDevJsonRpcService.class))
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                TestTransmitters.addPollStream(TestTransmitters.DEFAULT, transmitter);
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "POLL")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.auto-start", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.interval", "45s")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @Inject
    SsfTransmitters transmitters;

    @Inject
    SsfPollScheduler scheduler;

    @Inject
    SsfDevJsonRpcService devUi;

    @BeforeEach
    void awaitStream() {
        TestTransmitters.awaitRegistered(transmitters.primary().orElseThrow());
    }

    @Test
    @DisplayName("pollStatus describes the poller: mode, last poll, pending acknowledgements")
    void pollStatus() {
        List<SsfDevJsonRpcService.PollStatus> before = devUi.pollStatus();
        assertEquals(1, before.size());
        SsfDevJsonRpcService.PollStatus status = before.get(0);
        assertEquals("default", status.transmitterName());
        assertEquals(TestTransmitters.current().issuer(), status.transmitterIssuer());
        assertFalse(status.autoStart());
        assertFalse(status.running());
        assertFalse(status.longPolling());
        assertEquals("PT45S", status.interval());
        assertNull(status.longPollingHold());
        assertNull(status.lastPoll());
        assertEquals(0, status.pendingAcks());

        scheduler.pollNow();

        SsfDevJsonRpcService.PollStatus after = devUi.pollStatus().get(0);
        assertTrue(after.lastPoll() != null && after.lastSuccessfulPoll() != null, "the poll is reported");
        assertNull(after.pollError());
    }
}
