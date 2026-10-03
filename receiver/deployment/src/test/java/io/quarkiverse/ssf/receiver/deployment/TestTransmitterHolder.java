package io.quarkiverse.ssf.receiver.deployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the running test transmitters. Always looked up through the system class loader
 * (see {@code TestTransmitters}), so that the class loader of the test and the Quarkus
 * class loader of the test methods share the one instance of this class and its map.
 * System properties would do as well, but have to be strings: Narayana copies them all.
 */
public final class TestTransmitterHolder {

    public static final Map<String, Object> INSTANCES = new ConcurrentHashMap<>();

    private TestTransmitterHolder() {
    }
}
