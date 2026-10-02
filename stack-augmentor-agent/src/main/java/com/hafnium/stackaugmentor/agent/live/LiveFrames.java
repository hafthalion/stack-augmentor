package com.hafnium.stackaugmentor.agent.live;

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.WeakIdentityMap;
import com.hafnium.stackaugmentor.runtime.ids.FrameFormat;
import com.hafnium.stackaugmentor.runtime.ids.IdResolver;
import com.hafnium.stackaugmentor.runtime.ids.NamedId;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The live-stack mode's handler. When the VM records the stack trace of a throwable, the frames it recorded are still
 * on the stack: for those of the configured methods, it reads the receiver and the arguments from the live stack and
 * turns them into ids right away. When the stack trace array is created from the recorded frames, which happens once,
 * when the trace is first read or printed, it rewrites those frames in that array.
 *
 * <p>So it sees every frame of the trace, also those of constructors while they call {@code super(...)}, and of the
 * methods that catch the exception or called them, and it matches frames by position: recursion needs no special
 * care.
 */
final class LiveFrames implements Dispatch.TraceHandler {

    /** Shown for an argument that the JIT optimized away (scalar replacement): it reads as {@code null}. */
    static final String UNKNOWN = "?";

    /** The ids of one frame, at its position in the trace. */
    record Captured(int index, String className, String methodName, int lineNumber, List<NamedId> receiverIds, List<NamedId> paramIds) {
    }

    private final StackWalker plain;
    private final LiveStackFrames live;
    private final FrameSpecs specs;
    private final IdResolver resolver;
    private final FrameFormat format;
    /** Frames beyond this depth are not in the trace: {@code -XX:MaxJavaStackTraceDepth}. */
    private final int maxDepth;
    /** Whether a {@code null} object in a compiled frame may be one the JIT optimized away: {@code -XX:+EliminateAllocations}. */
    private final boolean eliminatedAllocations;

    /** The captured frames of the throwables whose stack trace array was not created yet. */
    private final WeakIdentityMap<Throwable, List<Captured>> pending = new WeakIdentityMap<>();

    LiveFrames(StackWalker plain, LiveStackFrames live, FrameSpecs specs, IdResolver resolver, FrameFormat format, int maxDepth,
               boolean eliminatedAllocations) {
        this.plain = plain;
        this.live = live;
        this.specs = specs;
        this.resolver = resolver;
        this.format = format;
        this.maxDepth = maxDepth;
        this.eliminatedAllocations = eliminatedAllocations;
    }

    @Override
    public void onFill(Throwable thrown) {
        // Reading local variables is far slower than walking the stack: only when a frame shows ids, and only down to it.
        int last = plain.walk(frames -> lastWithIds(frames, thrown));
        List<Captured> captured = last < 0 ? List.of() : live.walker().walk(frames -> capture(frames, thrown, last));
        if (captured.isEmpty()) {
            // Also when the application fills in the trace again.
            pending.remove(thrown);
        } else {
            pending.set(thrown, captured);
        }
    }

    @Override
    public void onTrace(Throwable thrown, StackTraceElement[] trace) {
        List<Captured> captured = pending.remove(thrown);
        if (captured != null) {
            rewrite(trace, captured);
        }
    }

    @Override
    public void onSetTrace(Throwable thrown) {
        pending.remove(thrown);
    }

    /**
     * The trace index of the last frame that shows ids, or -1. Also -1 while a class is being loaded, e.g. for the
     * {@code ClassNotFoundException}s of class loaders: looking at classes then could load the class being loaded again.
     */
    private int lastWithIds(Stream<StackWalker.StackFrame> frames, Throwable thrown) {
        Iterator<StackWalker.StackFrame> iterator = frames.iterator();
        StackWalker.StackFrame frame = firstTraceFrame(iterator, thrown);
        int last = -1;
        for (int index = 0; frame != null; index++) {
            if (ClassLoader.class.isAssignableFrom(frame.getDeclaringClass())) {
                return -1;
            }
            if (index < maxDepth && specs.of(frame) != FrameSpecs.NONE) {
                last = index;
            }
            frame = iterator.hasNext() ? iterator.next() : null;
        }
        return last;
    }

    private List<Captured> capture(Stream<StackWalker.StackFrame> frames, Throwable thrown, int last) {
        Iterator<StackWalker.StackFrame> iterator = frames.iterator();
        StackWalker.StackFrame frame = firstTraceFrame(iterator, thrown);
        List<Captured> captured = new ArrayList<>();
        for (int index = 0; frame != null && index <= last; index++) {
            FrameSpecs.Spec spec = specs.of(frame);
            if (spec != FrameSpecs.NONE) {
                Captured ids = ids(frame, spec, index);
                if (ids != null) {
                    captured.add(ids);
                }
            }
            frame = iterator.hasNext() ? iterator.next() : null;
        }
        return captured;
    }

    private Captured ids(StackWalker.StackFrame frame, FrameSpecs.Spec spec, int index) {
        Object[] locals = live.locals(frame);
        boolean compiled = live.compiled(frame);
        List<NamedId> receiverIds = List.of();
        if (spec.receiver()) {
            Object self = locals.length > 0 ? locals[0] : null;
            // Not an instance of the declaring class: the JIT optimized the object away, so there is no id to show.
            if (spec.owner().isInstance(self)) {
                receiverIds = resolver.receiverIds(self, spec.owner().getName());
            }
        }
        List<NamedId> paramIds = new ArrayList<>(spec.params().size());
        for (FrameSpecs.Param param : spec.params()) {
            paramIds.add(new NamedId(param.label(), paramId(locals, param, compiled)));
        }
        if (receiverIds.isEmpty() && paramIds.isEmpty()) {
            return null;
        }
        return new Captured(index, frame.getClassName(), frame.getMethodName(), frame.getLineNumber(), receiverIds, List.copyOf(paramIds));
    }

    private String paramId(Object[] locals, FrameSpecs.Param param, boolean compiled) {
        Class<?> type = param.type();
        boolean wide = type == long.class || type == double.class;
        int slot = wide ? param.slot() + 1 : param.slot();
        if (slot >= locals.length) {
            return UNKNOWN;
        }
        Object local = locals[slot];
        Object value;
        if (!type.isPrimitive()) {
            if ((local == null && compiled && eliminatedAllocations) || live.isPrimitive(local)) {
                return UNKNOWN;
            }
            value = local;
        } else {
            if (!live.isPrimitive(local)) {
                return UNKNOWN;
            }
            value = primitive(type, live.bits(local));
        }
        return param.hashed() ? resolver.hashedParamId(value) : resolver.paramId(value);
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
     * Skips the frames above the throwable's stack trace: this handler's, {@code fillInStackTrace} and the constructors
     * of the throwable, as the VM skips them, and returns the trace's first frame, or null.
     */
    static StackWalker.StackFrame firstTraceFrame(Iterator<StackWalker.StackFrame> frames, Throwable thrown) {
        // Down to Throwable.fillInStackTrace(), whose added code called this handler.
        StackWalker.StackFrame frame = null;
        while (frames.hasNext()) {
            frame = frames.next();
            if (frame.getDeclaringClass() == Throwable.class && frame.getMethodName().equals("fillInStackTrace")) {
                break;
            }
            frame = null;
        }
        if (frame == null) {
            return null;
        }
        // Overrides of fillInStackTrace that call it, then the constructors of the throwable's class and its superclasses.
        frame = frames.hasNext() ? frames.next() : null;
        while (frame != null && frame.getMethodName().equals("fillInStackTrace") && frame.getDeclaringClass().isInstance(thrown)) {
            frame = frames.hasNext() ? frames.next() : null;
        }
        while (frame != null && frame.getMethodName().equals("<init>") && frame.getDeclaringClass().isInstance(thrown)) {
            frame = frames.hasNext() ? frames.next() : null;
        }
        return frame;
    }

    /**
     * Rewrites the captured frames. Each is checked against the trace by class, method and line; if the walk saw frames
     * that the trace leaves out, or the other way round, the nearest matching position is used.
     */
    private void rewrite(StackTraceElement[] trace, List<Captured> captured) {
        int shift = 0;
        for (Captured frame : captured) {
            int index = find(trace, frame, frame.index() + shift);
            if (index < 0) {
                if (Log.isDebug()) {
                    Log.debug(() -> "live stack: " + frame.className() + "." + frame.methodName() + " is not at position " + frame.index()
                            + " of the stack trace, so it shows no ids");
                }
                continue;
            }
            shift = index - frame.index();
            trace[index] = format.rewrite(trace[index], frame.receiverIds(), frame.paramIds());
        }
    }

    private static final int SEARCH = 8;

    private static int find(StackTraceElement[] trace, Captured frame, int expected) {
        for (int distance = 0; distance <= SEARCH; distance++) {
            if (matches(trace, expected + distance, frame)) {
                return expected + distance;
            }
            if (distance > 0 && matches(trace, expected - distance, frame)) {
                return expected - distance;
            }
        }
        return -1;
    }

    private static boolean matches(StackTraceElement[] trace, int index, Captured frame) {
        if (index < 0 || index >= trace.length) {
            return false;
        }
        StackTraceElement element = trace[index];
        return element.getLineNumber() == frame.lineNumber() && element.getMethodName().equals(frame.methodName())
                && element.getClassName().equals(frame.className());
    }
}
