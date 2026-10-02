package com.hafnium.stackaugmentor.agent.live;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * The JDK-internal {@code java.lang.LiveStackFrame}: a stack walker whose frames also hold their local variables, so
 * the receiver and the arguments of each frame. Needs {@code java.lang} open to the agent. Unsupported by the JDK: it
 * exists since JDK 9, and {@link LiveStack} checks at startup that it still works as expected.
 *
 * <p>A local variable of a primitive type is a {@code PrimitiveSlot} with the raw 64-bit slot: an {@code int} in its
 * low 32 bits, a {@code long} or {@code double} in the second of its two slots. A slot that the JIT no longer keeps
 * reads as 0 or {@code null}, so the native library must have asked the JVM to keep them all.
 */
final class LiveStackFrames {

    private static final int MODE_COMPILED = 2;

    private final StackWalker walker;
    private final MethodHandle getLocals;
    private final Class<?> primitiveSlot;
    private final MethodHandle slotSize;
    private final MethodHandle slotValue;
    /** {@code LiveStackFrameInfo.mode}: whether the frame is interpreted or compiled; null if the JDK has no such field. */
    private final VarHandle mode;

    /**
     * @throws ReflectiveOperationException if this JDK has no live stack frames, or {@code java.lang} is not open
     */
    LiveStackFrames(Set<StackWalker.Option> options) throws ReflectiveOperationException {
        Class<?> liveStackFrame = Class.forName("java.lang.LiveStackFrame");
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(liveStackFrame, MethodHandles.lookup());
        Method getStackWalker = liveStackFrame.getMethod("getStackWalker", Set.class);
        getStackWalker.setAccessible(true);
        walker = (StackWalker) getStackWalker.invoke(null, options);
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
    }

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
}
