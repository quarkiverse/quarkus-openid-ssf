package io.quarkiverse.ssf.receiver.conformance;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.test.conformance.receiver.ConformanceReceiverSettings;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The {@link ConformanceReceiverSettings settings of the receiver under test}, bound from
 * {@code cts.*}. A value that is not set keeps the default of the library, see
 * {@link ConformanceBeans#settings(CtsConfig)}.
 */
@ConfigMapping(prefix = "cts")
public interface CtsConfig {

    Transmitter transmitter();

    Auth auth();

    Delivery delivery();

    Stream stream();

    Run run();

    /**
     * How the automated tests start the suite ({@code cts.suite.*}). Read by the library's
     * {@code ConformanceSettings} from system properties; declared here so that the
     * mapping accepts the properties the build passes on.
     */
    Suite suite();

    /**
     * How the automated tests expose the receiver under test ({@code cts.receiver.*}).
     * Read by the library's {@code ConformanceSettings} from system properties; declared
     * here so that the mapping accepts the properties the build passes on.
     */
    Receiver receiver();

    interface Transmitter {

        /**
         * Issuer of the emulated transmitter: the URL of the test instance in the
         * conformance suite, with its alias, e.g.
         * {@code https://localhost.emobix.co.uk:8443/test/a/easyssf-receiver}.
         */
        Optional<String> issuer();

        /**
         * Name of the TLS configuration ({@code quarkus.tls.<name>.*}) whose trust store
         * holds the certificate of a conformance suite with a self-signed certificate.
         */
        Optional<String> tlsConfigurationName();

        /**
         * Whether to trust any TLS certificate of the transmitter instead. Only for a
         * conformance suite running locally.
         */
        @WithDefault("false")
        boolean trustAllCertificates();

        /**
         * Whether the host name of the transmitter has to match its certificate. The
         * self-signed certificate of a locally running conformance suite is issued for
         * {@code localhost}, not for {@code localhost.emobix.co.uk}.
         */
        @WithDefault("true")
        boolean verifyHostname();
    }

    interface Auth {

        /**
         * {@code dynamic} obtains an access token with the client credentials grant from
         * the token endpoint of the emulated transmitter, {@code static} sends the
         * configured access token. Matches the 'Authentication Variant' of the test plan.
         */
        Optional<ConformanceReceiverSettings.Auth.Mode> mode();

        /** Client id, as configured in the test plan ({@code client.client_id}). */
        Optional<String> clientId();

        /** Client secret, as configured in the test plan ({@code client.client_secret}). */
        Optional<String> clientSecret();

        /** Scopes to request, as configured in the test plan ({@code client.scope}). */
        Optional<List<String>> scopes();

        /**
         * How the client authenticates at the token endpoint: {@code basic} or
         * {@code post}. Matches the 'Client Authentication Type' of the test plan.
         */
        Optional<ConformanceReceiverSettings.Auth.ClientAuthenticationMethod> clientAuthenticationMethod();

        /**
         * Access token to send in mode {@code static}, as configured in the test plan
         * ({@code ssf.transmitter.access_token}).
         */
        Optional<String> accessToken();
    }

    interface Delivery {

        /** {@code push} or {@code poll}. Matches the 'SSF Delivery Mode' of the test plan. */
        Optional<SsfDeliveryMethod> method();

        /**
         * URL under which the conformance suite reaches the push endpoint of this
         * application. Must be https.
         */
        Optional<String> pushUrl();

        /** How often the transmitter is polled with {@code poll} delivery. */
        Optional<Duration> pollInterval();
    }

    interface Stream {

        /** Event types (alias or URI) the stream requests. */
        Optional<List<String>> eventsRequested();

        /**
         * Subject the {@code remove-subject} scenario removes from the stream, as its
         * members, e.g. {@code format=email} and {@code email=foo@example.com}: one of the
         * subjects listed in the 'SSF valid SubjectId' field of the test plan.
         */
        Map<String, String> subjectToRemove();
    }

    interface Run {

        /** How long to wait for the verification event before going on without it. */
        Optional<Duration> verificationTimeout();

        /**
         * The stream is deleted once no SET arrived for this long. Has to exceed the
         * pauses the tests make between events.
         */
        Optional<Duration> idleTimeout();

        /** A run ends at the latest after this time. */
        Optional<Duration> maxDuration();
    }

    interface Suite {

        Optional<String> image();

        Optional<String> nginxImage();

        Optional<String> mongodbImage();

        Optional<String> port();
    }

    interface Receiver {

        Optional<String> port();

        Optional<String> pushUrl();
    }
}
