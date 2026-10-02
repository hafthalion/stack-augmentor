package com.hafnium.stackaugmentor.instrument.live;

import com.hafnium.stackaugmentor.instrument.bridge.LiveDispatch;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

/**
 * The code that the live-stack mode adds to {@link Throwable}. It is inlined into {@code Throwable} and only calls
 * {@link LiveDispatch}, which the bootstrap class loader sees.
 */
public final class ThrowableHooks {

    private ThrowableHooks() {
    }

    /** Adds the code to {@code Throwable}, whose type the builder builds. */
    public static <T> DynamicType.Builder<T> addTo(DynamicType.Builder<T> throwable) {
        return throwable
                .visit(Advice.to(Fill.class).on(named("fillInStackTrace").and(takesNoArguments())))
                .visit(Advice.to(Read.class).on(named("getOurStackTrace")))
                .visit(Advice.to(Replace.class).on(named("setStackTrace")));
    }

    /** {@code fillInStackTrace()}: the VM recorded the frames, and they are still on the stack. */
    static final class Fill {

        private Fill() {
        }

        @Advice.OnMethodExit
        static void exit(@Advice.This Throwable self, @Advice.FieldValue("backtrace") Object backtrace) {
            // No backtrace: the stack trace is not writable, so the VM recorded nothing.
            if (backtrace != null) {
                LiveDispatch.onFill(self);
            }
        }
    }

    /** {@code getOurStackTrace()}: every way to read the stack trace goes through it, printing included. */
    static final class Read {

        private Read() {
        }

        /** Whether this call creates the stack trace array from what the VM recorded. */
        @Advice.OnMethodEnter
        static boolean enter(@Advice.FieldValue("stackTrace") StackTraceElement[] trace,
                             @Advice.FieldValue("UNASSIGNED_STACK") StackTraceElement[] unassigned,
                             @Advice.FieldValue("backtrace") Object backtrace) {
            return (trace == unassigned || trace == null) && backtrace != null;
        }

        @Advice.OnMethodExit
        static void exit(@Advice.Enter boolean created, @Advice.This Throwable self, @Advice.Return StackTraceElement[] trace) {
            if (created) {
                LiveDispatch.onTrace(self, trace);
            }
        }
    }

    /** {@code setStackTrace(...)}: the recorded frames no longer apply. */
    static final class Replace {

        private Replace() {
        }

        @Advice.OnMethodExit
        static void exit(@Advice.This Throwable self) {
            LiveDispatch.onSetTrace(self);
        }
    }
}
