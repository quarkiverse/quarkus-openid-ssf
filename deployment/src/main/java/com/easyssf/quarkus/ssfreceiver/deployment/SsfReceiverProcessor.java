/*
 * Copyright 2026 Thomas Darimont and the easyssf.com contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.easyssf.quarkus.ssfreceiver.deployment;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import com.easyssf.quarkus.ssfreceiver.runtime.auth.NoopTransmitterTokenProvider;
import com.easyssf.quarkus.ssfreceiver.runtime.auth.StaticTransmitterTokenProvider;
import com.easyssf.quarkus.ssfreceiver.runtime.dedup.InMemorySsfJtiDedupStore;
import com.easyssf.quarkus.ssfreceiver.runtime.delivery.poll.InMemorySsfPollAckStore;
import com.easyssf.quarkus.ssfreceiver.runtime.delivery.poll.SsfPoller;
import com.easyssf.quarkus.ssfreceiver.runtime.delivery.poll.SsfTransmitterPollApi;
import com.easyssf.quarkus.ssfreceiver.runtime.delivery.push.JwksResolver;
import com.easyssf.quarkus.ssfreceiver.runtime.delivery.push.SetVerifier;
import com.easyssf.quarkus.ssfreceiver.runtime.delivery.push.SsfPushRoute;
import com.easyssf.quarkus.ssfreceiver.runtime.event.LoggingSsfEventHandler;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfAliases;
import com.easyssf.quarkus.ssfreceiver.runtime.event.SsfReceiverStartupValidator;
import com.easyssf.quarkus.ssfreceiver.runtime.metadata.SsfConfigurationResolver;
import com.easyssf.quarkus.ssfreceiver.runtime.metadata.SsfTransmitterMetadataApi;
import com.easyssf.quarkus.ssfreceiver.runtime.metrics.NoopSsfReceiverMetrics;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.ReceiverManagedStreamRegistrar;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.ReceiverManagedStreamState;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfStreamClient;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.SsfTransmitterStreamConfigurationApi;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.TransmitterManagedStreamProbe;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.status.SsfTransmitterStreamStatusApi;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.subjects.SsfTransmitterStreamAddSubjectApi;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.subjects.SsfTransmitterStreamRemoveSubjectApi;
import com.easyssf.quarkus.ssfreceiver.runtime.stream.verification.SsfTransmitterStreamVerificationApi;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class SsfReceiverProcessor {

    private static final Logger LOG = Logger.getLogger(SsfReceiverProcessor.class);

    private static final String FEATURE = "ssf-receiver";
    private static final String OIDC_CLIENT_CAPABILITY = "io.quarkus.oidc.client";
    private static final String OIDC_TOKEN_PROVIDER_CLASS = "com.easyssf.quarkus.ssfreceiver.runtime.auth.OidcTransmitterTokenProvider";
    private static final String STATIC_TOKEN_PROPERTY = "ssf.receiver.transmitter-access-token";
    private static final String OAUTH2_TOKEN_ENDPOINT_PROPERTY = "ssf.receiver.oauth2.token-endpoint";
    /**
     * The capability advertised by {@code quarkus-micrometer} (and pulled in transitively
     * by every {@code quarkus-micrometer-registry-*} extension). Note the capability
     * name is {@code io.quarkus.metrics}, NOT {@code io.quarkus.micrometer} — Quarkus
     * uses the generic name so SmallRye Metrics could in theory provide it too.
     */
    private static final String MICROMETER_CAPABILITY = "io.quarkus.metrics";
    private static final String MICROMETER_METRICS_CLASS = "com.easyssf.quarkus.ssfreceiver.runtime.metrics.MicrometerSsfReceiverMetrics";

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
     * Registers {@link StaticTransmitterTokenProvider} when {@code ssf.receiver.transmitter-access-token}
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
                .addBeanClass(com.easyssf.quarkus.ssfreceiver.runtime.auth.Oauth2TransmitterTokenProvider.class)
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
