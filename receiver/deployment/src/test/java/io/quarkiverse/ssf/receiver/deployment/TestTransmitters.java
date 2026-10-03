package io.quarkiverse.ssf.receiver.deployment;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.test.TestTransmitter;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

/**
 * Starts easyssf's {@link TestTransmitter} before the application and hands it to the
 * test methods.
 *
 * <p>
 * {@code QuarkusExtensionTest} runs the test methods in the Quarkus class loader, while
 * {@code setBeforeAllCustomizer} runs in the class loader of the test. The archive built
 * by {@link #archive(Class[])} makes the test transmitter and what its API refers to
 * (easyssf-core, Nimbus) parent-first, so both see the same classes and the instance
 * stashed in the system properties can be used from a test method.
 */
final class TestTransmitters {

    static final String DEFAULT = "default";

    static final String EVENT_TYPE = SsfEventTypes.CAEP_SESSION_REVOKED;

    static final String PUSH_DELIVERY_URL = "https://my-receiver.example/ssf/push";

    private static final String PARENT_FIRST = "quarkus.class-loading.parent-first-artifacts="
            + "org.easyssf:easyssf-test,org.easyssf:easyssf-core,com.nimbusds:nimbus-jose-jwt\n";

    private TestTransmitters() {
    }

    /**
     * The map of running transmitters, from the holder class as the system class loader
     * loaded it: the same map in both class loaders.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> instances() {
        try {
            Class<?> holder = ClassLoader.getSystemClassLoader().loadClass(TestTransmitterHolder.class.getName());
            return (Map<String, Object>) holder.getField("INSTANCES").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The system property with the issuer of the transmitter, {@code ${test.ssf.<id>.issuer}} in config. */
    static String issuerProperty(String id) {
        return "test.ssf." + id + ".issuer";
    }

    static String streamIdProperty(String id) {
        return "test.ssf." + id + ".stream-id";
    }

    static String tokenUriProperty(String id) {
        return "test.ssf." + id + ".token-uri";
    }

    /** {@code ${...}} reference to a system property, for {@code overrideConfigKey}. */
    static String ref(String property) {
        return "${" + property + "}";
    }

    static TestTransmitter start() {
        return start(DEFAULT);
    }

    static TestTransmitter start(String id) {
        TestTransmitter transmitter = new TestTransmitter();
        instances().put(id, transmitter);
        System.setProperty(issuerProperty(id), transmitter.issuer());
        System.setProperty(tokenUriProperty(id), transmitter.tokenUri());
        return transmitter;
    }

    static TestTransmitter current() {
        return current(DEFAULT);
    }

    static TestTransmitter current(String id) {
        TestTransmitter transmitter = (TestTransmitter) instances().get(id);
        if (transmitter == null) {
            throw new IllegalStateException("No test transmitter '" + id + "' was started");
        }
        return transmitter;
    }

    static void stop() {
        stop(DEFAULT);
    }

    static void stop(String id) {
        TestTransmitter transmitter = (TestTransmitter) instances().remove(id);
        if (transmitter != null) {
            transmitter.close();
        }
        System.clearProperty(issuerProperty(id));
        System.clearProperty(streamIdProperty(id));
        System.clearProperty(tokenUriProperty(id));
    }

    /** The test archive, with the given beans and the parent-first class loading. */
    static JavaArchive archive(Class<?>... classes) {
        return ShrinkWrap.create(JavaArchive.class)
                .addClasses(TestTransmitters.class, TestTransmitterHolder.class)
                .addClasses(classes)
                .addAsResource(new StringAsset(PARENT_FIRST), "application.properties");
    }

    /**
     * Creates a stream with PUSH delivery at the transmitter, as an operator would, and
     * publishes its id as {@code test.ssf.<id>.stream-id}.
     */
    static String addPushStream(String id, TestTransmitter transmitter) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("method", "urn:ietf:rfc:8935");
        delivery.put("endpoint_url", PUSH_DELIVERY_URL);
        return addStream(id, transmitter, delivery);
    }

    /** Creates a stream with POLL delivery at the transmitter. */
    static String addPollStream(String id, TestTransmitter transmitter) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("method", "urn:ietf:rfc:8936");
        return addStream(id, transmitter, delivery);
    }

    private static String addStream(String id, TestTransmitter transmitter, Map<String, Object> delivery) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("delivery", delivery);
        request.put("events_requested", List.of(EVENT_TYPE));
        request.put("events_supported", List.of(EVENT_TYPE));
        request.put("description", "test stream");
        String streamId = (String) transmitter.addStream(request).get("stream_id");
        System.setProperty(streamIdProperty(id), streamId);
        return streamId;
    }

    /** Waits until the stream of the transmitter was looked up or registered. */
    static void awaitRegistered(SsfTransmitter transmitter) {
        SsfStreamRegistrar registrar = transmitter.getStreamRegistrar();
        if (registrar == null) {
            throw new IllegalStateException("The transmitter " + transmitter + " has no stream registrar");
        }
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    if (registrar.getState() != SsfStreamRegistrar.State.REGISTERED) {
                        throw new AssertionError("stream registration is " + registrar.getState() + ": "
                                + registrar.getLastError());
                    }
                });
    }
}
