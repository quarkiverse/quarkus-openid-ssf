package io.quarkiverse.ssf.receiver.runtime;

import org.easyssf.receiver.transmitter.SsfTransmitter;

/**
 * Callback to customize a transmitter before it is built: replace its verifier or token
 * provider, tune the poller, and so on. Every bean of this type is called for every
 * configured transmitter; the {@link SsfTransmitter.Builder#name() name} tells which.
 * Provide an {@code @ApplicationScoped} or {@code @Singleton} bean implementing it.
 */
@FunctionalInterface
public interface SsfTransmitterCustomizer {

    void customize(SsfTransmitter.Builder builder);
}
