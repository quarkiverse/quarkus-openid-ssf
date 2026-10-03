package io.quarkiverse.ssf.receiver.deployment;

import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverLifecycle;
import io.quarkiverse.ssf.receiver.runtime.SsfReceiverProducers;
import io.quarkiverse.ssf.receiver.runtime.dedup.InMemorySsfJtiDedupStoreProducer;
import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPollScheduler;
import io.quarkiverse.ssf.receiver.runtime.delivery.push.SsfPushRoute;
import io.quarkiverse.ssf.receiver.runtime.event.LoggingSsfEventHandler;
import io.quarkiverse.ssf.receiver.runtime.stream.SsfReceiverStreamClient;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

class SsfReceiverProcessor {

    private static final Logger LOG = Logger.getLogger(SsfReceiverProcessor.class);

    private static final String FEATURE = "ssf-receiver";

    /** The optional integrations; named by string so that their classes are only loaded when wanted. */
    private static final String OIDC_TOKEN_PROVIDERS_CLASS = "io.quarkiverse.ssf.receiver.runtime.auth.OidcClientTransmitterTokenProviders";
    private static final String MICROMETER_METRICS_CLASS = "io.quarkiverse.ssf.receiver.runtime.metrics.MicrometerSsfReceiverMetricsProducer";
    private static final String JDBC_STORE_PRODUCER_CLASS = "io.quarkiverse.ssf.receiver.runtime.jdbc.JdbcSsfJtiDedupStoreProducer";
    private static final String HEALTH_CHECK_CLASS = "io.quarkiverse.ssf.receiver.runtime.health.SsfReceiverHealthCheck";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * easyssf's types in Jandex, so that application code can use them in bean and
     * resource signatures (a JAX-RS resource returning an {@code SsfStreamConfiguration},
     * say) and the extensions that act on the index see them.
     */
    @BuildStep
    void indexEasyssf(io.quarkus.deployment.annotations.BuildProducer<IndexDependencyBuildItem> index) {
        index.produce(new IndexDependencyBuildItem("org.easyssf", "easyssf-core"));
        index.produce(new IndexDependencyBuildItem("org.easyssf", "easyssf-receiver"));
        index.produce(new IndexDependencyBuildItem("org.easyssf", "easyssf-receiver-jdbc"));
    }

    /**
     * The records applications are likely to serialize (an event token in a REST
     * response, the stream configuration in a status page) are reflectively accessible
     * in a native image.
     */
    @BuildStep
    ReflectiveClassBuildItem reflectiveRecords() {
        return ReflectiveClassBuildItem.builder(
                "org.easyssf.core.event.SsfEventToken",
                "org.easyssf.core.event.SsfSubject",
                "org.easyssf.core.event.SsfSubjectIdentifier",
                "org.easyssf.core.metadata.SsfTransmitterMetadata",
                "org.easyssf.core.stream.SsfStreamConfiguration",
                "org.easyssf.core.stream.SsfStreamStatus")
                .methods().fields().build();
    }

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClasses(
                        SsfReceiverProducers.class,
                        SsfReceiverLifecycle.class,
                        SsfPushRoute.class,
                        SsfPollScheduler.class,
                        SsfReceiverStreamClient.class,
                        LoggingSsfEventHandler.class)
                .build();
    }

    /**
     * Processed SETs are remembered in the default datasource when {@code quarkus-agroal}
     * is present, in memory otherwise.
     */
    @BuildStep
    AdditionalBeanBuildItem registerDedupStore(Capabilities capabilities) {
        if (capabilities.isPresent(Capability.AGROAL)) {
            LOG.debug("quarkus-agroal detected, processed SETs can be remembered in the default datasource");
            return AdditionalBeanBuildItem.builder().setUnremovable().addBeanClass(JDBC_STORE_PRODUCER_CLASS).build();
        }
        return AdditionalBeanBuildItem.builder().setUnremovable().addBeanClass(InMemorySsfJtiDedupStoreProducer.class)
                .build();
    }

    /**
     * Micrometer metrics, when {@code quarkus-micrometer} (or one of its registry
     * extensions) is present. The capability is {@code io.quarkus.metrics}, not
     * micrometer, as SmallRye Metrics could provide it too.
     */
    @BuildStep
    AdditionalBeanBuildItem registerMicrometerMetrics(Capabilities capabilities) {
        if (!capabilities.isPresent(Capability.METRICS)) {
            return null;
        }
        LOG.debug("io.quarkus.metrics capability present, registering the Micrometer metrics of the SSF receiver");
        return AdditionalBeanBuildItem.builder().setUnremovable().addBeanClass(MICROMETER_METRICS_CLASS).build();
    }

    /**
     * Access tokens from {@code quarkus-oidc-client}, for transmitters without a static
     * token or OAuth2 client credentials of their own.
     */
    @BuildStep
    AdditionalBeanBuildItem registerOidcTokenProviders(Capabilities capabilities) {
        if (!capabilities.isPresent(Capability.OIDC_CLIENT)) {
            return null;
        }
        LOG.debug("quarkus-oidc-client detected, its clients can authenticate calls to the SSF transmitters");
        return AdditionalBeanBuildItem.builder().setUnremovable().addBeanClass(OIDC_TOKEN_PROVIDERS_CLASS).build();
    }

    @BuildStep
    AdditionalBeanBuildItem registerHealthCheck(Capabilities capabilities) {
        if (!capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
            return null;
        }
        return AdditionalBeanBuildItem.builder().setUnremovable().addBeanClass(HEALTH_CHECK_CLASS).build();
    }
}
