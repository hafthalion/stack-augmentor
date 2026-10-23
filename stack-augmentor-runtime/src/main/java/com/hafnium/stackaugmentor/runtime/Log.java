package com.hafnium.stackaugmentor.runtime;

import java.util.function.Supplier;

public final class Log {

    /** Turns on the debug output for the whole JVM, or for the build: {@code -Dstackaugmentor.debug=true}. */
    public static final String DEBUG_PROPERTY = "stackaugmentor.debug";

    /** Set at startup from {@link #DEBUG_PROPERTY}. */
    private static volatile boolean debug;

    private Log() {
    }

    /** Whether {@link #DEBUG_PROPERTY} asks for the debug output. */
    public static boolean debugRequested() {
        return Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY, "").trim());
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

    /** Always printed: for a failure whose exception a caller might not show. */
    public static void error(String message) {
        System.err.println("[stack-augmentor] ERROR " + message);
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
