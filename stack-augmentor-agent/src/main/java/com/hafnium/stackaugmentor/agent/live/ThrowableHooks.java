package com.hafnium.stackaugmentor.agent.live;

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import net.bytebuddy.asm.Advice;

/**
 * The code that the live-stack mode adds to {@link Throwable}. It is inlined into {@code Throwable} and only calls
 * {@link Dispatch}, which the bootstrap class loader sees.
 */
final class ThrowableHooks {

    private ThrowableHooks() {
    }

    /** {@code fillInStackTrace()}: the VM recorded the frames, and they are still on the stack. */
    static final class Fill {

        private Fill() {
        }

        @Advice.OnMethodExit
        static void exit(@Advice.This Throwable self, @Advice.FieldValue("backtrace") Object backtrace) {
            // No backtrace: the stack trace is not writable, so the VM recorded nothing.
            if (backtrace != null) {
                Dispatch.onFill(self);
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
                Dispatch.onTrace(self, trace);
            }
        }
    }

    /** {@code setStackTrace(...)}: the recorded frames no longer apply. */
    static final class Replace {

        private Replace() {
        }

        @Advice.OnMethodExit
        static void exit(@Advice.This Throwable self) {
            Dispatch.onSetTrace(self);
        }
    }
}
