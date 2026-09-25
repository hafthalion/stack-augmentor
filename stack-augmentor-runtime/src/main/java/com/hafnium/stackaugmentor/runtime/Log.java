package com.hafnium.stackaugmentor.runtime;

import java.util.function.Supplier;

public final class Log {

    /** Set from the {@code debug} configuration key. */
    private static volatile boolean debug;

    private Log() {
    }

    public static boolean isDebug() {
        return debug;
    }

    public static void setDebug(boolean enabled) {
        debug = enabled;
    }

    /** Always printed. */
    public static void warn(String message) {
        System.err.println("[stack-augmentor] WARN " + message);
    }

    /**
     * The message is only built when debugging is on. Creating the supplier may still allocate, so calls on
     * paths that run for every class or every exception check {@link #isDebug()} first.
     */
    public static void debug(Supplier<String> message) {
        if (debug) {
            System.err.println("[stack-augmentor] DEBUG " + message.get());
        }
    }
}
