package io.quarkiverse.ssf.receiver.deployment;

import static io.restassured.RestAssured.given;
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
 * A {@code PROCESSED_SET} table of easyssf 0.1.0 (no {@code STATE} column, created here
 * by the {@code INIT} script of the H2 URL) is upgraded on startup with
 * {@code jdbc.initialize-schema=true}, and the receiver de-duplicates through it.
 */
public class JdbcSchemaUpgradeTest {

    static final String OLD_TABLE = "CREATE TABLE IF NOT EXISTS TEST_PROCESSED_SET ("
            + "ISSUER VARCHAR(255) NOT NULL, JTI VARCHAR(255) NOT NULL, PROCESSED_AT BIGINT NOT NULL, "
            + "CONSTRAINT TEST_PROCESSED_SET_PK PRIMARY KEY (ISSUER, JTI))";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive(CountingHandler.class))
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                TestTransmitters.addPushStream(TestTransmitters.DEFAULT, transmitter);
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url",
                    "jdbc:h2:mem:ssf-upgrade;DB_CLOSE_DELAY=-1;INIT=" + OLD_TABLE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.jdbc.table-prefix", "TEST_")
            .overrideConfigKey("quarkus.openid-ssf.receiver.jdbc.initialize-schema", "true")
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
    DataSource dataSource;

    @BeforeAll
    static void registerSeceventEncoder() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/secevent+jwt", ContentType.TEXT));
    }

    @Test
    @DisplayName("The STATE column was added to the old table, and a duplicate reaches the handler once")
    void tableUpgraded() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT STATE, PROCESSED_AT FROM TEST_PROCESSED_SET")) {
            assertEquals(2, rows.getMetaData().getColumnCount());
        }

        String set = TestTransmitters.current().set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("user-1234"));
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
                ResultSet rows = statement.executeQuery("SELECT STATE FROM TEST_PROCESSED_SET")) {
            assertTrue(rows.next());
            assertEquals("PROCESSED", rows.getString(1));
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
