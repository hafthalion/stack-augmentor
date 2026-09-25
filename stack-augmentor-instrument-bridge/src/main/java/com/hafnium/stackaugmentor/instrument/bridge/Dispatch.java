package com.hafnium.stackaugmentor.instrument.bridge;

import java.util.ServiceLoader;

/**
 * Entry point called from the advice code that is inlined into instrumented methods.
 *
 * <p>With the Java agent, this class lives in the bootstrap class loader, so it is visible from every class,
 * and the agent installs the handler at startup. With build-time instrumentation, it is an ordinary
 * dependency of the application, and the handler is found through {@link ServiceLoader} on first use.
 */
public final class Dispatch {

    /** Implemented by the agent, or by the runtime module for build-time instrumentation. */
    public interface Handler {
        void onThrow(Object self, Throwable thrown, String owner, String method, Object[] paramValues, String[] paramNames);
    }

    private static volatile Handler handler;
    private static volatile boolean lookedUp;

    /** Set while a handler runs, so exceptions thrown by id sources are not recorded recursively. */
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private Dispatch() {
    }

    public static void install(Handler newHandler) {
        handler = newHandler;
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
                    System.err.println("[stack-augmentor] WARN cannot create the stack trace handler: " + error);
                } finally {
                    lookedUp = true;
                }
            }
            return handler;
        }
    }
}
