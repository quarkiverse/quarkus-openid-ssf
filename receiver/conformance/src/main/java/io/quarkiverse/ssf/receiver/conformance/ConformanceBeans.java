package io.quarkiverse.ssf.receiver.conformance;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.test.conformance.receiver.ConformanceReceiverSettings;
import org.easyssf.test.conformance.receiver.ConformanceRunner;
import org.easyssf.test.conformance.receiver.ConformanceSuiteModules;
import org.jboss.logging.Logger;

import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;

/**
 * The settings of the receiver under test, the HTTP client it calls the conformance
 * suite with, the runner that plays the scenarios and the view on the suite's running
 * modules, as beans.
 */
@Singleton
public class ConformanceBeans {

    private static final Logger LOG = Logger.getLogger(ConformanceBeans.class);

    /**
     * The JDK HTTP client reads this once, before the first client is created.
     */
    static final String DISABLE_HOSTNAME_VERIFICATION = "jdk.internal.httpclient.disableHostnameVerification";

    @Produces
    @Singleton
    ConformanceReceiverSettings settings(CtsConfig config) {
        ConformanceReceiverSettings settings = new ConformanceReceiverSettings();
        config.transmitter().issuer().ifPresent(settings.getTransmitter()::setIssuer);
        settings.getTransmitter().setTrustAllCertificates(config.transmitter().trustAllCertificates());
        settings.getTransmitter().setVerifyHostname(config.transmitter().verifyHostname());
        config.auth().mode().ifPresent(settings.getAuth()::setMode);
        config.auth().clientId().ifPresent(settings.getAuth()::setClientId);
        config.auth().clientSecret().ifPresent(settings.getAuth()::setClientSecret);
        config.auth().scopes().ifPresent(settings.getAuth()::setScopes);
        config.auth().clientAuthenticationMethod().ifPresent(settings.getAuth()::setClientAuthenticationMethod);
        config.auth().accessToken().ifPresent(settings.getAuth()::setAccessToken);
        config.delivery().method().ifPresent(settings.getDelivery()::setMethod);
        config.delivery().pushUrl().map(URI::create).ifPresent(settings.getDelivery()::setPushUrl);
        config.delivery().pollInterval().ifPresent(settings.getDelivery()::setPollInterval);
        config.stream().eventsRequested().ifPresent(settings.getStream()::setEventsRequested);
        if (!config.stream().subjectToRemove().isEmpty()) {
            settings.getStream().setSubjectToRemove(new LinkedHashMap<>(config.stream().subjectToRemove()));
        }
        config.run().verificationTimeout().ifPresent(settings.getRun()::setVerificationTimeout);
        config.run().idleTimeout().ifPresent(settings.getRun()::setIdleTimeout);
        config.run().maxDuration().ifPresent(settings.getRun()::setMaxDuration);
        return settings;
    }

    /**
     * The HTTP client the receiver calls the conformance suite with. It trusts the
     * certificate of the suite as configured, see {@link CtsConfig.Transmitter}.
     */
    @Produces
    @Singleton
    SsfHttpClient transmitterHttpClient(CtsConfig config, TlsConfigurationRegistry tlsRegistry) throws Exception {
        CtsConfig.Transmitter transmitter = config.transmitter();
        if (!transmitter.verifyHostname()) {
            System.setProperty(DISABLE_HOSTNAME_VERIFICATION, "true");
            LOG.info("Host name verification for the transmitter is off (cts.transmitter.verify-hostname=false)");
        }
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (transmitter.trustAllCertificates()) {
            builder.sslContext(trustAll());
            LOG.warn("Trusting any TLS certificate of the transmitter (cts.transmitter.trust-all-certificates=true)");
        } else if (transmitter.tlsConfigurationName().isPresent()) {
            String name = transmitter.tlsConfigurationName().get();
            TlsConfiguration tlsConfiguration = tlsRegistry.get(name)
                    .orElseThrow(() -> new ConfigurationException("cts.transmitter.tls-configuration-name names the TLS"
                            + " configuration '" + name + "', but there is no quarkus.tls." + name + ".* configuration"));
            builder.sslContext(tlsConfiguration.createSSLContext());
        }
        JdkSsfHttpClient httpClient = new JdkSsfHttpClient(builder.build(), Duration.ofSeconds(30));
        httpClient.setUserAgent("easyssf-receiver-conformance");
        return httpClient;
    }

    @Produces
    @Singleton
    ConformanceRunner conformanceRunner(ConformanceReceiverSettings settings, SsfHttpClient httpClient) {
        return new ConformanceRunner(settings, httpClient);
    }

    @Produces
    @Singleton
    ConformanceSuiteModules conformanceSuiteModules(ConformanceReceiverSettings settings, SsfHttpClient httpClient) {
        return new ConformanceSuiteModules(settings, httpClient);
    }

    private static SSLContext trustAll() throws Exception {
        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());
        return sslContext;
    }
}
