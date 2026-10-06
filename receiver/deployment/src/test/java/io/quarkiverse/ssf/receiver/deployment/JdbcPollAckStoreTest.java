package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.easyssf.receiver.jdbc.JdbcSsfPollAckStore;
import org.easyssf.receiver.jdbc.JdbcSsfStoreCleanup;
import org.easyssf.receiver.poll.SsfPollAckStore;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.nimbusds.jwt.SignedJWT;

import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPollScheduler;
import io.quarkus.test.QuarkusUnitTest;

/**
 * With {@code quarkus-agroal} and a datasource, the acknowledgements a poller owes its
 * transmitter wait in the table {@code <prefix>POLL_ACK}: created on startup, purged by
 * the cleanup, and an acknowledgement the transmitter did not accept stays in it until a
 * later request carried it.
 */
public class JdbcPollAckStoreTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive(FailingAckHttpClient.class))
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                TestTransmitters.addPollStream(TestTransmitters.DEFAULT, transmitter);
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:ssf-acks;DB_CLOSE_DELAY=-1")
            .overrideConfigKey("quarkus.openid-ssf.receiver.jdbc.table-prefix", "TEST_")
            .overrideConfigKey("quarkus.openid-ssf.receiver.jdbc.cleanup-interval", "1s")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.delivery-method", "POLL")
            .overrideConfigKey("quarkus.openid-ssf.receiver.poll.auto-start", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @Inject
    SsfPollScheduler scheduler;

    @Inject
    SsfTransmitters transmitters;

    @Inject
    SsfPollAckStore ackStore;

    @Inject
    JdbcSsfStoreCleanup cleanup;

    @Inject
    SsfHttpClient httpClient;

    @Inject
    DataSource dataSource;

    private SsfPoller poller;

    @BeforeEach
    void reset() {
        TestTransmitters.awaitRegistered(transmitters.primary().orElseThrow());
        poller = transmitters.primary().orElseThrow().getPoller();
        ((FailingAckHttpClient) httpClient).failAckRequests.set(0);
        TestTransmitters.current().acknowledgedSets().clear();
    }

    @Test
    @DisplayName("The table is created on startup and the cleanup covers the store")
    void storeOverTheDatasource() throws Exception {
        assertThat(ackStore, instanceOf(JdbcSsfPollAckStore.class));
        assertTrue(cleanup.isRunning(), "the periodic cleanup runs");
        assertEquals(0, countRows());
        assertEquals(0, poller.getPendingAckCount());
    }

    @Test
    @DisplayName("An acknowledgement the transmitter did not accept waits in the table for the next request")
    void pendingAckSurvivesInTheTable() throws Exception {
        TestTransmitter transmitter = TestTransmitters.current();
        String set = transmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        String jti = SignedJWT.parse(set).getJWTClaimsSet().getJWTID();
        transmitter.queueSet(set);

        // the SET is fetched and handled, the request that acknowledges it fails
        ((FailingAckHttpClient) httpClient).failAckRequests.set(1);
        assertThrows(IllegalStateException.class, () -> scheduler.pollNow());
        assertThat(transmitter.acknowledgedSets(), not(hasItem(jti)));
        assertEquals(1, poller.getPendingAckCount());
        assertEquals(1, countRows(), "the acknowledgement is in the table");

        // the next poll carries it and removes it from the table
        scheduler.pollNow();
        assertThat(transmitter.acknowledgedSets(), hasItem(jti));
        assertEquals(0, poller.getPendingAckCount());
        assertEquals(0, countRows());
    }

    private int countRows() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM TEST_POLL_ACK")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    /**
     * Replaces the {@code SsfHttpClient} of the extension: a poll request that carries
     * acknowledgements fails with {@code 503} as often as the test asks for.
     */
    @Singleton
    public static class FailingAckHttpClient implements SsfHttpClient {
        private final SsfHttpClient delegate = new JdkSsfHttpClient();
        final AtomicInteger failAckRequests = new AtomicInteger();

        @Override
        public SsfHttpResponse execute(SsfHttpRequest request) throws IOException {
            if ("POST".equals(request.method()) && request.uri().getPath().endsWith("/poll")
                    && request.body() != null && request.body().contains("\"ack\"")
                    && failAckRequests.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return new SsfHttpResponse(503, Map.of(), null);
            }
            return delegate.execute(request);
        }
    }
}
