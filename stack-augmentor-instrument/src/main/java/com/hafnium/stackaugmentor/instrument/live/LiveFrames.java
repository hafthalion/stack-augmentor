package com.hafnium.stackaugmentor.instrument.live;

import com.hafnium.stackaugmentor.instrument.bridge.LiveDispatch;
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
 * on the stack: it keeps those of the configured classes, with their local variables, so the receiver and the
 * arguments. When the stack trace array is created from the recorded frames, which happens once, when the trace is
 * first read or printed, it turns them into ids and rewrites those frames in that array.
 *
 * <p>So it sees every frame of the trace, also those of constructors while they call {@code super(...)}, and of the
 * methods that catch the exception or called them, and it matches frames by position: recursion needs no special
 * care.
 *
 * <p>Most exceptions are never printed, so creating one costs as little as possible: a walk that sees only the classes
 * of the frames, and, only if one of them may show ids, a walk that reads the local variables down to it. Which method
 * a frame runs, what it shows and the ids are worked out only when the trace is read.
 */
public final class LiveFrames implements LiveDispatch.Handler {

    /** Shown for an argument that the JIT optimized away (scalar replacement): it reads as {@code null}. */
    public static final String UNKNOWN = "?";

    /** A frame of a configured class, as the walk returned it, at its position in the trace. */
    record Kept(int index, StackWalker.StackFrame frame) {
    }

    /** The ids of one frame, at its position in the trace. */
    record Captured(int index, String className, String methodName, int lineNumber, List<NamedId> receiverIds, List<NamedId> paramIds) {
    }

    private final LiveStackFrames live;
    private final FrameSpecs specs;
    private final IdResolver resolver;
    private final FrameFormat format;
    /** Frames beyond this depth are not in the trace: {@code -XX:MaxJavaStackTraceDepth}. */
    private final int maxDepth;
    /** Whether a {@code null} object in a compiled frame may be one the JIT optimized away: {@code -XX:+EliminateAllocations}. */
    private final boolean eliminatedAllocations;

    /** The kept frames of the throwables whose stack trace array was not created yet. */
    private final WeakIdentityMap<Throwable, List<Kept>> pending = new WeakIdentityMap<>();

    public LiveFrames(LiveStackFrames live, FrameSpecs specs, IdResolver resolver, FrameFormat format, int maxDepth,
                      boolean eliminatedAllocations) {
        this.live = live;
        this.specs = specs;
        this.resolver = resolver;
        this.format = format;
        this.maxDepth = maxDepth;
        this.eliminatedAllocations = eliminatedAllocations;
    }

    @Override
    public void onFill(Throwable thrown) {
        // Reading local variables is far slower than walking the stack: only when a frame may show ids, and only down to it.
        int last = live.classWalker().walk(frames -> lastWithIds(frames, thrown));
        List<Kept> kept = last < 0 ? List.of() : live.walker().walk(frames -> keep(frames, thrown, last));
        if (kept.isEmpty()) {
            // Also when the application fills in the trace again.
            pending.remove(thrown);
        } else {
            pending.set(thrown, kept);
        }
    }

    @Override
    public void onTrace(Throwable thrown, StackTraceElement[] trace) {
        List<Kept> kept = pending.remove(thrown);
        if (kept == null) {
            return;
        }
        List<Captured> captured = new ArrayList<>(kept.size());
        for (Kept frame : kept) {
            FrameSpecs.Spec spec = specs.of(frame.frame());
            if (spec != FrameSpecs.NONE) {
                Captured ids = ids(frame.frame(), spec, frame.index());
                if (ids != null) {
                    captured.add(ids);
                }
            }
        }
        rewrite(trace, captured);
    }

    @Override
    public void onSetTrace(Throwable thrown) {
        pending.remove(thrown);
    }

    /**
     * The position in the walk (counted from its first frame, as the walks from {@link #onFill} see the same frames)
     * of the last frame that may show ids, or -1. Also -1 while a class is being loaded, e.g. for the
     * {@code ClassNotFoundException}s of class loaders: looking at classes then could load the class being loaded again.
     */
    private int lastWithIds(Stream<StackWalker.StackFrame> frames, Throwable thrown) {
        // Classes only; a class is looked at with reflection only once no class loader is on the stack.
        List<Class<?>> candidates = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        int position = 0;
        // Where the trace starts, below Throwable's own frames and the throwable's constructors, as far as classes tell;
        // only for the depth limit.
        int traceStart = -1;
        boolean inThrowable = false;
        for (Iterator<StackWalker.StackFrame> iterator = frames.iterator(); iterator.hasNext(); position++) {
            Class<?> type = iterator.next().getDeclaringClass();
            if (ClassLoader.class.isAssignableFrom(type)) {
                return -1;
            }
            if (traceStart < 0) {
                boolean throwableFrame = type == Throwable.class || inThrowable && type.isInstance(thrown);
                inThrowable |= type == Throwable.class;
                if (!inThrowable || throwableFrame) {
                    continue;
                }
                traceStart = position;
            }
            if (position - traceStart < maxDepth && specs.isNamed(type)) {
                candidates.add(type);
                positions.add(position);
            }
        }
        for (int i = candidates.size() - 1; i >= 0; i--) {
            if (specs.mayShowIds(candidates.get(i))) {
                return positions.get(i);
            }
        }
        return -1;
    }

    /** Keeps the frames of the classes that may show ids, down to {@code last}, with their position in the trace. */
    private List<Kept> keep(Stream<StackWalker.StackFrame> frames, Throwable thrown, int last) {
        Iterator<StackWalker.StackFrame> iterator = frames.iterator();
        int position = 0;
        // Down to Throwable.fillInStackTrace(), whose added code called this handler.
        StackWalker.StackFrame frame = null;
        while (iterator.hasNext() && position <= last) {
            frame = iterator.next();
            position++;
            if (frame.getDeclaringClass() == Throwable.class && frame.getMethodName().equals("fillInStackTrace")) {
                break;
            }
            frame = null;
        }
        if (frame == null) {
            return List.of();
        }
        // Overrides of fillInStackTrace that call it, then the constructors of the throwable's class and its superclasses.
        frame = iterator.hasNext() ? iterator.next() : null;
        while (frame != null && frame.getDeclaringClass().isInstance(thrown)
                && (frame.getMethodName().equals("fillInStackTrace") || frame.getMethodName().equals("<init>"))) {
            position++;
            frame = iterator.hasNext() ? iterator.next() : null;
        }
        List<Kept> kept = new ArrayList<>();
        for (int index = 0; frame != null && position <= last; index++, position++) {
            if (specs.mayShowIds(frame.getDeclaringClass())) {
                kept.add(new Kept(index, frame));
            }
            frame = iterator.hasNext() ? iterator.next() : null;
        }
        return kept;
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
            value = LiveStackFrames.primitive(type, live.bits(local));
        }
        return param.hashed() ? resolver.hashedParamId(value) : resolver.paramId(value);
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
