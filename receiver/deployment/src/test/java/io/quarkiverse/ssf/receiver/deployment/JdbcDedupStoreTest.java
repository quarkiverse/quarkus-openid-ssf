package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.jdbc.JdbcSsfJtiDedupStore;
import org.easyssf.receiver.jdbc.JdbcSsfStoreCleanup;
import org.easyssf.receiver.set.SsfJtiDedupStore;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;

/**
 * With {@code quarkus-agroal} and a datasource, processed SETs are remembered in the
 * database: the table is created on startup, a SET posted twice reaches the handler once,
 * and its row is in the table.
 */
public class JdbcDedupStoreTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive(CountingHandler.class))
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                TestTransmitters.addPushStream(TestTransmitters.DEFAULT, transmitter);
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:ssf;DB_CLOSE_DELAY=-1")
            .overrideConfigKey("quarkus.openid-ssf.receiver.jdbc.table-prefix", "TEST_")
            .overrideConfigKey("quarkus.openid-ssf.receiver.jdbc.cleanup-interval", "1s")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN);

    @Inject
    SsfEventHandler handler;

    @Inject
    SsfJtiDedupStore dedupStore;

    @Inject
    JdbcSsfStoreCleanup cleanup;

    @Inject
    DataSource dataSource;

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    @Test
    @DisplayName("Processed SETs are remembered in the table, a duplicate reaches the handler once")
    void duplicateSkippedThroughTheDatabase() throws Exception {
        assertThat(dedupStore, instanceOf(JdbcSsfJtiDedupStore.class));
        assertTrue(cleanup.isRunning(), "the periodic cleanup runs");

        String set = TestTransmitters.current().set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
        String jti = com.nimbusds.jwt.SignedJWT.parse(set).getJWTClaimsSet().getJWTID();
        CountingHandler counting = (CountingHandler) handler;
        counting.invocations.set(0);

        for (int i = 0; i < 2; i++) {
            given().header("Content-Type", "application/secevent+jwt").body(set)
                    .when().post("/ssf/push")
                    .then().statusCode(202);
        }
        assertEquals(1, counting.invocations.get());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM TEST_PROCESSED_SET WHERE JTI = '" + jti + "'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "the SET is in the table");
        }
    }

    @Singleton
    public static class CountingHandler implements SsfEventHandler {
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public void handle(SsfEventContext eventContext) {
            invocations.incrementAndGet();
        }
    }
}
