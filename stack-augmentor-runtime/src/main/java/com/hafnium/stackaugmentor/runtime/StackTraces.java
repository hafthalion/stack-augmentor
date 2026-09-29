package com.hafnium.stackaugmentor.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * How {@link ThrowHandler} reads a throwable's stack trace and writes a rewritten frame back.
 *
 * <p>{@link #copying()} uses the public API: {@code getStackTrace} and {@code setStackTrace} each copy the whole
 * trace, so every instrumented frame an exception leaves costs time proportional to the trace length.
 * {@link #inPlace()} replaces the one element in the throwable's own array instead, which needs
 * {@code java.lang} to be open to this module; the Java agent opens it through {@code Instrumentation}.
 */
public abstract sealed class StackTraces {

    private static final StackTraces COPYING = new Copying();

    private StackTraces() {
    }

    /**
     * The trace to search. The caller must not change it: {@link #write} does. An empty array if the throwable has no
     * stack trace or its stack trace is not writable.
     */
    abstract StackTraceElement[] read(Throwable thrown);

    /** Replaces {@code trace[index]}, where {@code trace} is what {@link #read} returned for {@code thrown}. */
    abstract void write(Throwable thrown, StackTraceElement[] trace, int index, StackTraceElement element);

    /** {@code getStackTrace} and {@code setStackTrace}: works everywhere. */
    public static StackTraces copying() {
        return COPYING;
    }

    /**
     * Writes into the throwable's own array, without copying it.
     *
     * @throws IllegalAccessException if {@code java.lang} is not open to this module
     */
    public static StackTraces inPlace() throws IllegalAccessException {
        return new InPlace();
    }

    private static final class Copying extends StackTraces {

        @Override
        StackTraceElement[] read(Throwable thrown) {
            return thrown.getStackTrace();
        }

        @Override
        void write(Throwable thrown, StackTraceElement[] trace, int index, StackTraceElement element) {
            trace[index] = element;
            thrown.setStackTrace(trace); // no effect if the throwable's stack trace is not writable
        }
    }

    private static final class InPlace extends StackTraces {

        private static final StackTraceElement[] NONE = new StackTraceElement[0];

        /** {@code Throwable.stackTrace}: filled in from the VM's backtrace on first use, or null if not writable. */
        private final VarHandle stackTrace;

        InPlace() throws IllegalAccessException {
            try {
                stackTrace = MethodHandles.privateLookupIn(Throwable.class, MethodHandles.lookup())
                        .findVarHandle(Throwable.class, "stackTrace", StackTraceElement[].class);
            } catch (NoSuchFieldException e) {
                IllegalAccessException error = new IllegalAccessException("Throwable has no stackTrace field");
                error.initCause(e);
                throw error;
            }
        }

        @Override
        StackTraceElement[] read(Throwable thrown) {
            // Throwable guards the field with its own monitor.
            synchronized (thrown) {
                StackTraceElement[] trace = (StackTraceElement[]) stackTrace.get(thrown);
                if (trace == null || trace.length == 0) {
                    // Not filled in yet: getStackTrace does that (and copies it, this once).
                    thrown.getStackTrace();
                    trace = (StackTraceElement[]) stackTrace.get(thrown);
                }
                return trace != null ? trace : NONE;
            }
        }

        @Override
        void write(Throwable thrown, StackTraceElement[] trace, int index, StackTraceElement element) {
            synchronized (thrown) {
                // Unless the application replaced the trace in the meantime, e.g. with fillInStackTrace.
                if (stackTrace.get(thrown) == trace) {
                    trace[index] = element;
                }
            }
        }
    }
}
