package com.hafnium.stackaugmentor.runtime.config;

import com.hafnium.stackaugmentor.runtime.Log;
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

    public static final String NO_EXCEPTIONS =
            "no [augment.exceptions] entry is true, so no exception is augmented; \"java.lang.Exception+\" = true augments "
                    + "all exceptions, \"**\" = true all throwables";

    private Startup() {
    }

    /**
     * The configuration from {@code loader}, or what follows from it. An invalid one is also printed as an error, naming
     * the file, the key and the line, and then thrown: whoever calls the component, e.g. the ByteBuddy Gradle plugin or
     * {@code ServiceLoader}, may not show the exception's message.
     */
    public static <T> T load(String component, Supplier<T> loader) {
        try {
            return loader.get();
        } catch (ConfigException e) {
            Log.error(component + ": " + e.getMessage());
            throw e;
        }
    }

    /** With debug output on, describes a configuration file and where it came from. */
    public static void describe(String component, String location, AugmentorConfig config) {
        if (!Log.isDebug()) {
            return;
        }
        Log.debug(() -> component + ": configuration " + location);
        Log.debug(() -> component + ": [augment.receiver] " + config.classesDescription());
        Log.debug(() -> component + ": [augment.params] " + config.methodsDescription());
        Log.debug(() -> component + ": [augment] frameFormat=" + config.frameFormat() + ", receiverFormat=" + config.receiverFormat()
                + ", paramsFormat=" + config.paramsFormat() + ", maxValueLength=" + config.maxValueLength()
                + ", exceptions=" + config.exceptionsDescription());
    }

    /**
     * Warns when the configuration of the classes without configurations of their own, with the built-in one after the
     * others, augments no exception.
     */
    public static void warnIfNoExceptions(String component, AugmentorConfig config) {
        if (!config.exceptions().containsValue(true)) {
            Log.warn(component + ": " + NO_EXCEPTIONS);
        }
    }
}
