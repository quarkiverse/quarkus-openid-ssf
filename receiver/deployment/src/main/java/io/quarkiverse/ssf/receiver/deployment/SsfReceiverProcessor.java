package io.quarkiverse.ssf.receiver.deployment;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.auth.NoopTransmitterTokenProvider;
import io.quarkiverse.ssf.receiver.runtime.auth.StaticTransmitterTokenProvider;
import io.quarkiverse.ssf.receiver.runtime.dedup.InMemorySsfJtiDedupStore;
import io.quarkiverse.ssf.receiver.runtime.delivery.poll.InMemorySsfPollAckStore;
import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfPoller;
import io.quarkiverse.ssf.receiver.runtime.delivery.poll.SsfTransmitterPollApi;
import io.quarkiverse.ssf.receiver.runtime.delivery.push.JwksResolver;
import io.quarkiverse.ssf.receiver.runtime.delivery.push.SetVerifier;
import io.quarkiverse.ssf.receiver.runtime.delivery.push.SsfPushRoute;
import io.quarkiverse.ssf.receiver.runtime.event.LoggingSsfEventHandler;
import io.quarkiverse.ssf.receiver.runtime.event.SsfAliases;
import io.quarkiverse.ssf.receiver.runtime.event.SsfReceiverStartupValidator;
import io.quarkiverse.ssf.receiver.runtime.metadata.SsfConfigurationResolver;
import io.quarkiverse.ssf.receiver.runtime.metadata.SsfTransmitterMetadataApi;
import io.quarkiverse.ssf.receiver.runtime.metrics.NoopSsfReceiverMetrics;
import io.quarkiverse.ssf.receiver.runtime.stream.ReceiverManagedStreamRegistrar;
import io.quarkiverse.ssf.receiver.runtime.stream.ReceiverManagedStreamState;
import io.quarkiverse.ssf.receiver.runtime.stream.SsfStreamClient;
import io.quarkiverse.ssf.receiver.runtime.stream.SsfTransmitterStreamConfigurationApi;
import io.quarkiverse.ssf.receiver.runtime.stream.TransmitterManagedStreamProbe;
import io.quarkiverse.ssf.receiver.runtime.stream.status.SsfTransmitterStreamStatusApi;
import io.quarkiverse.ssf.receiver.runtime.stream.subjects.SsfTransmitterStreamAddSubjectApi;
import io.quarkiverse.ssf.receiver.runtime.stream.subjects.SsfTransmitterStreamRemoveSubjectApi;
import io.quarkiverse.ssf.receiver.runtime.stream.verification.SsfTransmitterStreamVerificationApi;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class SsfReceiverProcessor {

    private static final Logger LOG = Logger.getLogger(SsfReceiverProcessor.class);

    private static final String FEATURE = "ssf-receiver";
    private static final String OIDC_CLIENT_CAPABILITY = "io.quarkus.oidc.client";
    private static final String OIDC_TOKEN_PROVIDER_CLASS = "io.quarkiverse.ssf.receiver.runtime.auth.OidcTransmitterTokenProvider";
    private static final String STATIC_TOKEN_PROPERTY = "quarkus.openid-ssf.receiver.transmitter-access-token";
    private static final String OAUTH2_TOKEN_ENDPOINT_PROPERTY = "quarkus.openid-ssf.receiver.oauth2.token-endpoint";
    /**
     * The capability advertised by {@code quarkus-micrometer} (and pulled in transitively
     * by every {@code quarkus-micrometer-registry-*} extension). Note the capability
     * name is {@code io.quarkus.metrics}, NOT {@code io.quarkus.micrometer} — Quarkus
     * uses the generic name so SmallRye Metrics could in theory provide it too.
     */
    private static final String MICROMETER_CAPABILITY = "io.quarkus.metrics";
    private static final String MICROMETER_METRICS_CLASS = "io.quarkiverse.ssf.receiver.runtime.metrics.MicrometerSsfReceiverMetrics";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * The REST Client extension scans Jandex for interfaces it might be asked to build —
     * classes living in a Quarkus extension's runtime artifact aren't part of the
     * application archive by default, so the interface has to be indexed explicitly.
     */
    @BuildStep
    AdditionalIndexedClassesBuildItem indexRestClientInterfaces() {
        return new AdditionalIndexedClassesBuildItem(
                SsfTransmitterStreamStatusApi.class.getName(),
                SsfTransmitterStreamConfigurationApi.class.getName(),
                SsfTransmitterStreamVerificationApi.class.getName(),
                SsfTransmitterStreamAddSubjectApi.class.getName(),
                SsfTransmitterStreamRemoveSubjectApi.class.getName(),
                SsfTransmitterPollApi.class.getName(),
                SsfTransmitterMetadataApi.class.getName());
    }

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClasses(
                        JwksResolver.class,
                        SetVerifier.class,
                        SsfPushRoute.class,
                        SsfReceiverStartupValidator.class,
                        LoggingSsfEventHandler.class,
                        NoopTransmitterTokenProvider.class,
                        SsfConfigurationResolver.class,
                        SsfStreamClient.class,
                        ReceiverManagedStreamState.class,
                        ReceiverManagedStreamRegistrar.class,
                        TransmitterManagedStreamProbe.class,
                        InMemorySsfPollAckStore.class,
                        SsfPoller.class,
                        InMemorySsfJtiDedupStore.class,
                        SsfAliases.class,
                        NoopSsfReceiverMetrics.class)
                .build();
    }

    /**
     * Registers {@code MicrometerSsfReceiverMetrics} only when the consumer has
     * {@code quarkus-micrometer} (or one of its registry extensions) on the
     * classpath, signalled by the {@code io.quarkus.metrics} capability.
     * Without that capability, {@link NoopSsfReceiverMetrics} stays in effect
     * and {@code MicrometerSsfReceiverMetrics} is never loaded — which is
     * essential, since it imports {@code io.micrometer.core.instrument.*}.
     */
    @BuildStep
    AdditionalBeanBuildItem registerMicrometerMetrics(Capabilities capabilities) {
        if (!capabilities.isPresent(MICROMETER_CAPABILITY)) {
            return null;
        }
        LOG.infof("io.quarkus.metrics capability present — registering MicrometerSsfReceiverMetrics");
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(MICROMETER_METRICS_CLASS)
                .build();
    }

    /**
     * Registers {@link StaticTransmitterTokenProvider} when {@code quarkus.openid-ssf.receiver.transmitter-access-token}
     * is set in the consumer's config. Resolved at build time via {@code ConfigProvider}
     * so the OIDC step (below) can decide whether to skip itself.
     */
    @BuildStep
    AdditionalBeanBuildItem registerStaticTokenProvider() {
        if (!staticTokenConfigured()) {
            return null;
        }
        LOG.infof("%s configured — registering StaticTransmitterTokenProvider", STATIC_TOKEN_PROPERTY);
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(StaticTransmitterTokenProvider.class)
                .build();
    }

    /**
     * Registers {@code Oauth2TransmitterTokenProvider} when
     * {@link #OAUTH2_TOKEN_ENDPOINT_PROPERTY} is set and a static token isn't.
     * Self-contained client_credentials grant — runs without
     * {@code quarkus-oidc-client}, takes precedence over it when both could
     * apply. Consumers who want OIDC simply leave this property unset.
     */
    @BuildStep
    AdditionalBeanBuildItem registerOauth2TokenProvider() {
        if (staticTokenConfigured()) {
            return null;
        }
        if (!oauth2TokenEndpointConfigured()) {
            return null;
        }
        LOG.infof("%s configured — registering Oauth2TransmitterTokenProvider",
                OAUTH2_TOKEN_ENDPOINT_PROPERTY);
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(io.quarkiverse.ssf.receiver.runtime.auth.Oauth2TransmitterTokenProvider.class)
                .build();
    }

    /**
     * Registers the OIDC-backed {@code TransmitterTokenProvider} only when the consumer
     * has {@code quarkus-oidc-client} on the classpath <em>and</em> hasn't pinned a
     * static {@code transmitter-access-token} or configured {@link #OAUTH2_TOKEN_ENDPOINT_PROPERTY}.
     * Without any of those, the default no-op provider stays in effect and outbound
     * calls go unauthenticated.
     */
    @BuildStep
    AdditionalBeanBuildItem registerOidcTokenProvider(Capabilities capabilities) {
        if (!capabilities.isPresent(OIDC_CLIENT_CAPABILITY)) {
            return null;
        }
        if (staticTokenConfigured()) {
            LOG.infof("%s is set — skipping OidcTransmitterTokenProvider", STATIC_TOKEN_PROPERTY);
            return null;
        }
        if (oauth2TokenEndpointConfigured()) {
            LOG.infof("%s is set — skipping OidcTransmitterTokenProvider",
                    OAUTH2_TOKEN_ENDPOINT_PROPERTY);
            return null;
        }
        LOG.infof("quarkus-oidc-client detected — registering OidcTransmitterTokenProvider");
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(OIDC_TOKEN_PROVIDER_CLASS)
                .build();
    }

    private static boolean staticTokenConfigured() {
        return ConfigProvider.getConfig()
                .getOptionalValue(STATIC_TOKEN_PROPERTY, String.class)
                .filter(s -> !s.isBlank())
                .isPresent();
    }

    private static boolean oauth2TokenEndpointConfigured() {
        return ConfigProvider.getConfig()
                .getOptionalValue(OAUTH2_TOKEN_ENDPOINT_PROPERTY, String.class)
                .filter(s -> !s.isBlank())
                .isPresent();
    }
}
