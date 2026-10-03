package io.quarkiverse.ssf.receiver.runtime.health;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.metadata.SsfTransmitterMetadata;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.smallrye.health.api.Wellness;

/**
 * Reports whether the receiver is in contact with its transmitters, from what the
 * receiver already knows; a transmitter is never called for the check. A wellness check
 * ({@code /q/health/well}): it does not gate readiness, as a transmitter that is down
 * must not take the application out of service. Registered when
 * {@code quarkus-smallrye-health} is present.
 *
 * <ul>
 * <li>UP: every transmitter was reached (its metadata was retrieved, or the last poll
 * succeeded) and its stream, if it is looked up or managed, is registered, or no
 * contact was made yet.</li>
 * <li>DOWN: a last poll failed, or a stream cannot be used.</li>
 * </ul>
 */
@Wellness
@Singleton
public class SsfReceiverHealthCheck implements HealthCheck {

    @Inject
    SsfReceiverConfig config;

    @Inject
    Instance<SsfTransmitters> transmitters;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder response = HealthCheckResponse.named("SSF receiver");
        if (!config.enabled()) {
            return response.up().withData("status", "disabled").build();
        }
        List<SsfTransmitter> all = transmitters.get().all();
        boolean up = true;
        for (SsfTransmitter transmitter : all) {
            String prefix = (all.size() == 1) ? "" : transmitter.getName() + ".";
            up &= report(transmitter, prefix, response);
        }
        return response.status(up).build();
    }

    private static boolean report(SsfTransmitter transmitter, String prefix, HealthCheckResponseBuilder response) {
        boolean up = true;
        response.withData(prefix + "transmitter", transmitter.getIssuer());
        response.withData(prefix + "delivery", (transmitter.getPoller() != null) ? "poll" : "push");
        SsfTransmitterMetadata metadata = transmitter.getMetadataResolver().getResolvedMetadata().orElse(null);
        response.withData(prefix + "metadata", (metadata != null) ? "resolved" : "not retrieved yet");

        String streamId = transmitter.getReceiverStream().getStreamId();
        response.withData(prefix + "stream", (streamId != null) ? streamId : "none");
        SsfStreamRegistrar registrar = transmitter.getStreamRegistrar();
        if (registrar != null) {
            SsfStreamRegistrar.State state = registrar.getState();
            response.withData(prefix + "streamRegistration", state.name().toLowerCase(java.util.Locale.ROOT));
            if (registrar.getLastError() != null) {
                response.withData(prefix + "streamRegistrationError", registrar.getLastError());
            }
            up &= state != SsfStreamRegistrar.State.FAILED;
        }

        SsfPoller poller = transmitter.getPoller();
        if (poller != null) {
            Instant lastPoll = poller.getLastPollAt();
            Instant lastSuccess = poller.getLastSuccessfulPollAt();
            String error = poller.getLastPollError();
            response.withData(prefix + "lastPoll", (lastPoll != null) ? lastPoll.toString() : "never");
            response.withData(prefix + "lastSuccessfulPoll", (lastSuccess != null) ? lastSuccess.toString() : "never");
            if (error != null) {
                response.withData(prefix + "pollError", error);
            }
            Instant pausedUntil = poller.getPausedUntil();
            if (pausedUntil != null && pausedUntil.isAfter(Instant.now())) {
                response.withData(prefix + "pausedUntil", pausedUntil.toString());
            }
            up &= error == null;
        }
        return up;
    }
}
