package com.hafnium.stackaugmentor.instrument.bridge;

import java.util.ServiceLoader;

/**
 * Entry point called from the advice code that is inlined into instrumented methods.
 *
 * <p>With the Java agent, this class lives in the bootstrap class loader, so it is visible from every class,
 * and the agent installs the handler at startup. With build-time instrumentation, it is an ordinary
 * dependency of the application, and the handler is found through {@link ServiceLoader} on first use.
 *
 * <p>In the agent's live-stack mode, no method is instrumented: code added to {@link Throwable} calls
 * {@link #onFill}, {@link #onTrace} and {@link #onSetTrace} instead, and the agent installs a {@link TraceHandler}.
 */
public final class Dispatch {

    /** Implemented by the agent, or by the runtime module for build-time instrumentation. */
    public interface Handler {
        void onThrow(Object self, Throwable thrown, String owner, String method, Object[] paramValues, String[] paramNames);
    }

    /**
     * Implemented by the agent's live-stack mode. Called from code added to {@link Throwable}, for every throwable whose
     * stack trace the VM records.
     */
    public interface TraceHandler {

        /** The VM has just recorded the stack trace of {@code thrown}, whose frames are still on this thread's stack. */
        void onFill(Throwable thrown);

        /** {@code trace} is the stack trace array of {@code thrown}, just created from what the VM recorded. */
        void onTrace(Throwable thrown, StackTraceElement[] trace);

        /** The application replaced the stack trace of {@code thrown}. */
        void onSetTrace(Throwable thrown);
    }

    /** Matched by name: this module has no dependencies. */
    private static final String CONFIG_EXCEPTION = "com.hafnium.stackaugmentor.runtime.config.ConfigException";

    private static volatile Handler handler;
    private static volatile boolean lookedUp;
    private static volatile TraceHandler traceHandler;

    /** Set while a handler runs, so exceptions thrown by id sources are not recorded recursively. */
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private Dispatch() {
    }

    public static void install(Handler newHandler) {
        handler = newHandler;
    }

    public static void install(TraceHandler newHandler) {
        traceHandler = newHandler;
    }

    public static void onFill(Throwable thrown) {
        TraceHandler current = traceHandler;
        if (current == null || ACTIVE.get() != null) {
            return;
        }
        ACTIVE.set(Boolean.TRUE);
        try {
            current.onFill(thrown);
        } catch (Throwable ignored) {
            // Never let the augmentation change the exception the application sees.
        } finally {
            ACTIVE.remove();
        }
    }

    public static void onTrace(Throwable thrown, StackTraceElement[] trace) {
        TraceHandler current = traceHandler;
        if (current == null || ACTIVE.get() != null) {
            return;
        }
        ACTIVE.set(Boolean.TRUE);
        try {
            current.onTrace(thrown, trace);
        } catch (Throwable ignored) {
            // The trace stays as the VM recorded it.
        } finally {
            ACTIVE.remove();
        }
    }

    public static void onSetTrace(Throwable thrown) {
        TraceHandler current = traceHandler;
        if (current == null || ACTIVE.get() != null) {
            return;
        }
        ACTIVE.set(Boolean.TRUE);
        try {
            current.onSetTrace(thrown);
        } catch (Throwable ignored) {
            // Nothing to undo.
        } finally {
            ACTIVE.remove();
        }
    }

    public static void onThrow(Object self, Throwable thrown, String owner, String method, Object[] paramValues, String[] paramNames) {
        if (ACTIVE.get() != null) {
            return;
        }
        ACTIVE.set(Boolean.TRUE);
        try {
            Handler current = handler();
            if (current != null) {
                current.onThrow(self, thrown, owner, method, paramValues, paramNames);
            }
        } catch (Throwable ignored) {
            // Never let the augmentation change the exception the application sees.
        } finally {
            ACTIVE.remove();
        }
    }

    /** The installed handler or, if none was installed, the first one registered as a service (looked up once). */
    private static Handler handler() {
        Handler current = handler;
        if (current != null || lookedUp) {
            return current;
        }
        synchronized (Dispatch.class) {
            if (handler == null && !lookedUp) {
                try {
                    for (Handler service : ServiceLoader.load(Handler.class, Dispatch.class.getClassLoader())) {
                        handler = service;
                        break;
                    }
                } catch (Throwable error) {
                    System.err.println("[stack-augmentor] WARN runtime: cannot create the stack trace handler, so stack traces "
                            + "stay unchanged: " + reason(error));
                } finally {
                    lookedUp = true;
                }
            }
            return handler;
        }
    }

    /**
     * What went wrong: {@code ServiceLoader} wraps the handler's exception. An invalid configuration was already printed
     * with its file, key and line by the handler.
     */
    private static String reason(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getName().equals(CONFIG_EXCEPTION) ? "the configuration is invalid" : cause.toString();
    }
}
