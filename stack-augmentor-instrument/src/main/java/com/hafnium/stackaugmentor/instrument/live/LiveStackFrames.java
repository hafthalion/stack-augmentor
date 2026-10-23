package com.hafnium.stackaugmentor.instrument.live;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;

/**
 * The JDK-internal {@code java.lang.LiveStackFrame}: a stack walker whose frames also hold their local variables, so
 * the receiver and the arguments of each frame. Needs {@code java.lang} open to the agent. Unsupported by the JDK: it
 * exists since JDK 9, and {@link #checkLayout()} checks that it still works as expected.
 *
 * <p>A local variable of a primitive type is a {@code PrimitiveSlot} with the raw 64-bit slot: an {@code int} in its
 * low 32 bits, a {@code long} or {@code double} in the second of its two slots. A slot that the JIT no longer keeps
 * reads as 0 or {@code null}, so the native library must have asked the JVM to keep them all.
 *
 * <p>It also reads, from {@code java.lang}, the frames that the VM recorded for a throwable ({@link Backtrace}), and the
 * VM's object for the method of a frame, which is the same for all its frames, so that the method's name and type,
 * which cost far more, are needed only once.
 */
public final class LiveStackFrames {

    /** Reflection frames are part of stack traces, so the walkers show them too. */
    private static final Set<StackWalker.Option> OPTIONS =
            EnumSet.of(StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_REFLECT_FRAMES);

    private static final int MODE_COMPILED = 2;

    private final StackWalker walker;
    private final MethodHandle getLocals;
    private final Class<?> primitiveSlot;
    private final MethodHandle slotSize;
    private final MethodHandle slotValue;
    /** {@code LiveStackFrameInfo.mode}: whether the frame is interpreted or compiled; null if the JDK has no such field. */
    private final VarHandle mode;
    /** {@code Throwable.backtrace}: the frames that the VM recorded ({@link Backtrace}). */
    private final VarHandle backtrace;
    /** {@code ClassFrameInfo.classOrMemberName}: the JVM's object for the frame's method, one per method. */
    private final VarHandle method;

    /**
     * @throws ReflectiveOperationException if this JDK has no live stack frames, or {@code java.lang} is not open
     */
    public LiveStackFrames() throws ReflectiveOperationException {
        Class<?> liveStackFrame = Class.forName("java.lang.LiveStackFrame");
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(liveStackFrame, MethodHandles.lookup());
        Method getStackWalker = liveStackFrame.getMethod("getStackWalker", Set.class);
        getStackWalker.setAccessible(true);
        walker = (StackWalker) getStackWalker.invoke(null, OPTIONS);
        getLocals = lookup.findVirtual(liveStackFrame, "getLocals", MethodType.methodType(Object[].class));
        primitiveSlot = Class.forName("java.lang.LiveStackFrame$PrimitiveSlot");
        slotSize = lookup.findVirtual(primitiveSlot, "size", MethodType.methodType(int.class));
        slotValue = lookup.findVirtual(primitiveSlot, "longValue", MethodType.methodType(long.class));
        VarHandle modeField;
        try {
            Class<?> info = Class.forName("java.lang.LiveStackFrameInfo");
            modeField = MethodHandles.privateLookupIn(info, MethodHandles.lookup()).findVarHandle(info, "mode", int.class);
        } catch (ReflectiveOperationException e) {
            modeField = null;
        }
        mode = modeField;
        backtrace = MethodHandles.privateLookupIn(Throwable.class, MethodHandles.lookup()).findVarHandle(Throwable.class, "backtrace", Object.class);
        Class<?> classFrameInfo = Class.forName("java.lang.ClassFrameInfo");
        method = MethodHandles.privateLookupIn(classFrameInfo, MethodHandles.lookup()).findVarHandle(classFrameInfo, "classOrMemberName", Object.class);
    }

    /** What the VM recorded for the throwable, see {@link Backtrace}. */
    Object backtrace(Throwable thrown) {
        return backtrace.get(thrown);
    }

    /** The JVM's object for the frame's method: the same for all frames of a method. */
    Object method(StackWalker.StackFrame frame) {
        return method.get(frame);
    }

    /** The walker whose frames hold their local variables. */
    StackWalker walker() {
        return walker;
    }

    /** The frame's local variables, starting with the receiver of an instance method and then the arguments. */
    Object[] locals(StackWalker.StackFrame frame) {
        try {
            return (Object[]) getLocals.invoke(frame);
        } catch (Throwable e) {
            throw new IllegalStateException("cannot read the local variables of " + frame, e);
        }
    }

    /** Whether the frame runs JIT-compiled code; true if the JDK does not say. */
    boolean compiled(StackWalker.StackFrame frame) {
        return mode == null || (int) mode.get(frame) == MODE_COMPILED;
    }

    boolean isPrimitive(Object local) {
        return primitiveSlot.isInstance(local);
    }

    /** The raw bits of a primitive slot; only 64-bit slots are supported. */
    long bits(Object slot) {
        try {
            int size = (int) slotSize.invoke(slot);
            if (size != Long.BYTES) {
                throw new IllegalStateException("unsupported slot size " + size);
            }
            return (long) slotValue.invoke(slot);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** The value of a primitive slot: narrower types sit in its low bits. */
    static Object primitive(Class<?> type, long bits) {
        if (type == long.class) {
            return bits;
        }
        if (type == double.class) {
            return Double.longBitsToDouble(bits);
        }
        int low = (int) bits;
        if (type == int.class) {
            return low;
        }
        if (type == boolean.class) {
            return low != 0;
        }
        if (type == char.class) {
            return (char) low;
        }
        if (type == byte.class) {
            return (byte) low;
        }
        if (type == short.class) {
            return (short) low;
        }
        if (type == float.class) {
            return Float.intBitsToFloat(low);
        }
        throw new IllegalArgumentException(type.getName());
    }

    /**
     * Checks that live stack frames hold the receiver and the arguments where {@link LiveFrames} expects them.
     *
     * @return null, or what is not as expected
     */
    public String checkLayout() {
        return Probe.check(this);
    }

    private static final class Probe {

        private static final long LONG = 0x1122334455667788L;
        private static final int INT = -42;
        private static final String OBJECT = "probe";
        private static final double DOUBLE = 2.5;

        private final LiveStackFrames live;
        private Object[] locals;

        private Probe(LiveStackFrames live) {
            this.live = live;
        }

        static String check(LiveStackFrames live) {
            Probe probe = new Probe(live);
            try {
                probe.run(LONG, INT, OBJECT, DOUBLE, true);
            } catch (RuntimeException e) {
                return "cannot read live stack frames: " + e;
            }
            Object[] locals = probe.locals;
            if (locals == null || locals.length < 8) {
                return "live stack frames have no local variables";
            }
            boolean expected;
            try {
                // this, long (2 slots), int, Object, double (2 slots), boolean
                expected = locals[0] == probe
                        && Long.valueOf(LONG).equals(primitive(long.class, live.bits(locals[2])))
                        && Integer.valueOf(INT).equals(primitive(int.class, live.bits(locals[3])))
                        && locals[4] == OBJECT
                        && Double.valueOf(DOUBLE).equals(primitive(double.class, live.bits(locals[6])))
                        && Boolean.TRUE.equals(primitive(boolean.class, live.bits(locals[7])));
            } catch (RuntimeException e) {
                expected = false;
            }
            if (!expected) {
                return "live stack frames do not hold the receiver and the arguments as expected";
            }
            return Backtrace.check(live.backtrace(new Throwable()), Probe.class);
        }

        private void run(long a, int b, Object c, double d, boolean e) {
            locals = live.walker().walk(frames -> frames
                    .filter(frame -> frame.getDeclaringClass() == Probe.class && frame.getMethodName().equals("run"))
                    .findFirst()
                    .map(live::locals)
                    .orElse(null));
        }
    }
}
