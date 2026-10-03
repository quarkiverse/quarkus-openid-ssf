package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import jakarta.inject.Inject;

import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamException;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The error surface of the stream management API: argument validation throws
 * {@link SsfStreamException} with a useful message, and a transmitter error surfaces with
 * its HTTP status.
 */
public class SsfStreamClientErrorsTest {

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
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "PUSH")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @Inject
    SsfReceiverStreamClient streamClient;

    @Inject
    SsfTransmitters transmitters;

    private SsfStreamClient easyssfClient() {
        return transmitters.primary().orElseThrow().getStreamClient();
    }

    @Test
    @DisplayName("Unknown stream -> SsfStreamException with the 404 of the transmitter")
    void unknownStreamReports404() {
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> easyssfClient().getStatus("this-stream-does-not-exist"));
        assertThat(ex.getStatusCode(), equalTo(404));
        assertThat(ex.getMessage(), containsString("404"));
    }

    @Test
    @DisplayName("Blank stream id -> rejected before a request is made")
    void blankStreamIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> easyssfClient().getStream("   "));
    }

    @Test
    @DisplayName("updateStatus with a status that is not enabled, paused or disabled -> SsfStreamException")
    void updateStatusRejectsUnknownStatus() {
        SsfStreamException ex = assertThrows(SsfStreamException.class, () -> streamClient.updateStatus("bogus", null));
        assertThat(ex.getMessage().toLowerCase(), containsString("status"));
    }

    @Test
    @DisplayName("addSubject with an empty subject -> SsfStreamException")
    void addSubjectRejectsEmptyMap() {
        SsfStreamException ex = assertThrows(SsfStreamException.class, () -> streamClient.addSubject(Map.of(), false));
        assertThat(ex.getMessage().toLowerCase(), containsString("subject"));
    }

    @Test
    @DisplayName("addSubject with a subject without 'format' -> SsfStreamException naming the member")
    void addSubjectRequiresFormat() {
        SsfStreamException ex = assertThrows(SsfStreamException.class,
                () -> streamClient.addSubject(Map.of("id", "user-1"), false));
        assertThat(ex.getMessage().toLowerCase(), containsString("format"));
    }

    @Test
    @DisplayName("Transmitter down -> SsfStreamException without an HTTP status")
    void transmitterDown() {
        TestTransmitters.current().setAvailable(false);
        try {
            SsfStreamException ex = assertThrows(SsfStreamException.class, () -> streamClient.status());
            assertThat(ex.getStatusCode(), equalTo(503));
        } finally {
            TestTransmitters.current().setAvailable(true);
        }
    }
}
