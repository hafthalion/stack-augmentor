package com.hafnium.stackaugmentor.bridge;

/**
 * Entry point called from the advice code that is inlined into instrumented methods.
 * Lives in the bootstrap class loader, so it is visible from every class.
 */
public final class Dispatch {

    /** Implemented by the agent. */
    public interface Handler {
        void onThrow(Object self, Throwable thrown, String owner, String method, Object[] paramValues, String[] paramNames);
    }

    private static volatile Handler handler;

    /** Set while a handler runs, so exceptions thrown by id sources are not recorded recursively. */
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private Dispatch() {
    }

    public static void install(Handler newHandler) {
        handler = newHandler;
    }

    public static void onThrow(Object self, Throwable thrown, String owner, String method, Object[] paramValues, String[] paramNames) {
        Handler current = handler;
        if (current == null || ACTIVE.get() != null) {
            return;
        }
        ACTIVE.set(Boolean.TRUE);
        try {
            current.onThrow(self, thrown, owner, method, paramValues, paramNames);
        } catch (Throwable ignored) {
            // Never let the augmentation change the exception the application sees.
        } finally {
            ACTIVE.remove();
        }
    }
}
