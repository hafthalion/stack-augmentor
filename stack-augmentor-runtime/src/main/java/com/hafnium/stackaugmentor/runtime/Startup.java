package com.hafnium.stackaugmentor.runtime;

import java.util.function.Supplier;

/**
 * How the Java agent, the build plugin and the runtime handler of build-time instrumentation start alike: an invalid
 * configuration is reported the same way, and the debug output describes the same things. Messages start with the
 * name of the component, e.g. {@code agent:}.
 */
public final class Startup {

    public static final String AGENT = "agent";
    public static final String BUILD_PLUGIN = "build plugin";
    /** The handler that build-time instrumented code calls at runtime. */
    public static final String RUNTIME = "runtime";

    public static final String NOTHING_CONFIGURED =
            "the configuration has no [augment.receiver] or [augment.params] entries, so nothing will be augmented";

    private Startup() {
    }

    /**
     * The configuration from {@code loader}. An invalid one is also printed as an error, naming the file, the key and
     * the line, and then thrown: whoever calls the component, e.g. the ByteBuddy Gradle plugin or {@code ServiceLoader},
     * may not show the exception's message.
     */
    public static AugmentorConfig load(String component, Supplier<AugmentorConfig> loader) {
        try {
            return loader.get();
        } catch (ConfigException e) {
            Log.error(component + ": " + e.getMessage());
            throw e;
        }
    }

    /** Turns debug output on or off as configured, and describes the configuration and where it came from. */
    public static void configure(String component, String location, AugmentorConfig config) {
        Log.setDebug(config.debug());
        if (!Log.isDebug()) {
            return;
        }
        Log.debug(() -> component + ": configuration " + location);
        Log.debug(() -> component + ": [augment.receiver] " + config.classesDescription());
        Log.debug(() -> component + ": [augment.params] " + config.methodsDescription());
        Log.debug(() -> component + ": [augment] frameFormat=" + config.frameFormat() + ", receiverFormat=" + config.receiverFormat()
                + ", paramsFormat=" + config.paramsFormat() + ", maxIdLength=" + config.maxIdLength());
    }

    /** For the components that decide what gets instrumented: warns when the configuration selects nothing. */
    public static void warnIfNothingConfigured(String component, AugmentorConfig config) {
        if (!config.hasAugmentEntries()) {
            Log.warn(component + ": " + NOTHING_CONFIGURED);
        }
    }
}
