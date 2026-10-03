package io.quarkiverse.ssf.receiver.runtime.event;

import org.easyssf.core.event.SsfEventTypes;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkus.runtime.configuration.ConfigurationException;

/**
 * Registers the aliases of {@code quarkus.openid-ssf.receiver.event-aliases.*} with
 * {@link SsfEventTypes}, where every part of easyssf resolves them. Registering is
 * idempotent, so it is done wherever an alias may be needed first.
 */
public final class SsfEventAliases {

    private SsfEventAliases() {
    }

    public static void register(SsfReceiverConfig config) {
        config.eventAliases().forEach((alias, uri) -> {
            try {
                SsfEventTypes.registerAlias(alias, uri);
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException(
                        "quarkus.openid-ssf.receiver.event-aliases." + alias + ": " + e.getMessage(), e);
            }
        });
    }
}
