package io.quarkiverse.ssf.receiver.deployment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.fail;

import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * With {@code jdbc.initialize-schema=false} a {@code PROCESSED_SET} table of easyssf
 * 0.1.0 is not changed: the start fails and the message names the statement to run and
 * where the migration scripts are.
 */
public class JdbcSchemaUpgradeRefusedTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> TestTransmitters.archive())
            .setBeforeAllCustomizer(() -> {
                TestTransmitter transmitter = TestTransmitters.start();
                TestTransmitters.addPushStream(TestTransmitters.DEFAULT, transmitter);
            })
            .setAfterAllCustomizer(TestTransmitters::stop)
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url",
                    "jdbc:h2:mem:ssf-upgrade-refused;DB_CLOSE_DELAY=-1;INIT=" + JdbcSchemaUpgradeTest.OLD_TABLE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.jdbc.table-prefix", "TEST_")
            .overrideConfigKey("quarkus.openid-ssf.receiver.jdbc.initialize-schema", "false")
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-issuer",
                    TestTransmitters.ref(TestTransmitters.issuerProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.expected-audience", TestTransmitter.AUDIENCE)
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-management", "TRANSMITTER")
            .overrideConfigKey("quarkus.openid-ssf.receiver.stream-id",
                    TestTransmitters.ref(TestTransmitters.streamIdProperty(TestTransmitters.DEFAULT)))
            .overrideConfigKey("quarkus.openid-ssf.receiver.transmitter-access-token", TestTransmitter.ACCESS_TOKEN)
            .assertException(failure -> {
                String messages = "";
                for (Throwable t = failure; t != null; t = t.getCause()) {
                    messages += t.getMessage() + "\n";
                }
                assertThat(messages, containsString("lacks the column(s) [STATE]"));
                assertThat(messages, containsString(
                        "ALTER TABLE TEST_PROCESSED_SET ADD STATE VARCHAR(16) DEFAULT 'PROCESSED' NOT NULL"));
                assertThat(messages, containsString("classpath:org/easyssf/receiver/jdbc/migration/"));
                assertThat(messages, containsString("quarkus.openid-ssf.receiver.jdbc.initialize-schema=true"));
            });

    @Test
    @DisplayName("The start fails naming the ALTER TABLE and the migration scripts")
    void startRefused() {
        fail("the application must not start");
    }
}
