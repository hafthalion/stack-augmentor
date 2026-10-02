package com.hafnium.stackaugmentor.instrument.bridge;

/**
 * Entry point called from the code that the agent's live-stack mode adds to {@link Throwable}, for every throwable whose
 * stack trace the VM records. It lives in the bootstrap class loader with {@link Dispatch}, so {@code java.base} can
 * call it, and the agent installs the handler when it turns the mode on.
 */
public final class LiveDispatch {

    /** Implemented by the agent's live-stack mode. */
    public interface Handler {

        /** The VM has just recorded the stack trace of {@code thrown}, whose frames are still on this thread's stack. */
        void onFill(Throwable thrown);

        /** {@code trace} is the stack trace array of {@code thrown}, just created from what the VM recorded. */
        void onTrace(Throwable thrown, StackTraceElement[] trace);

        /** The application replaced the stack trace of {@code thrown}. */
        void onSetTrace(Throwable thrown);
    }

    private static volatile Handler handler;

    /** Set while the handler runs, so the throwables it creates itself are not handled recursively. */
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private LiveDispatch() {
    }

    /** Installs the handler, or with null removes it. */
    public static void install(Handler newHandler) {
        handler = newHandler;
    }

    public static void onFill(Throwable thrown) {
        Handler current = handler;
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
        Handler current = handler;
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
        Handler current = handler;
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
}
