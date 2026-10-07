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
 * on the stack: for those of the configured methods, it reads the receiver and the arguments from the live stack and
 * turns them into ids right away. When the stack trace array is created from the recorded frames, which happens once,
 * when the trace is first read or printed, it rewrites those frames in that array.
 *
 * <p>So it sees every frame of the trace, also those of constructors while they call {@code super(...)}, and of the
 * methods that catch the exception or called them, and it matches frames by position: recursion needs no special
 * care.
 *
 * <p>Reading the live stack costs most, so the recorded frames ({@link Backtrace}) decide first, without walking the
 * stack, whether any frame may show ids, and down to which frame the walk must go. The walk then matches each frame
 * to its recorded one by class and bytecode index, which also gives its position in the trace.
 */
public final class LiveFrames implements LiveDispatch.Handler {

    /** Shown for an argument that the JIT optimized away (scalar replacement): it reads as {@code null}. */
    public static final String UNKNOWN = "?";

    /** The ids of one frame, at its position in the trace. */
    record Captured(int index, List<NamedId> receiverIds, List<NamedId> paramIds) {
    }

    private final LiveStackFrames live;
    private final FrameSpecs specs;
    private final IdResolver resolver;
    private final FrameFormat format;
    /** Whether a {@code null} object in a compiled frame may be one the JIT optimized away: {@code -XX:+EliminateAllocations}. */
    private final boolean eliminatedAllocations;

    /** The captured frames of the throwables whose stack trace array was not created yet. */
    private final WeakIdentityMap<Throwable, List<Captured>> pending = new WeakIdentityMap<>();

    public LiveFrames(LiveStackFrames live, FrameSpecs specs, IdResolver resolver, FrameFormat format,
                      boolean eliminatedAllocations) {
        this.live = live;
        this.specs = specs;
        this.resolver = resolver;
        this.format = format;
        this.eliminatedAllocations = eliminatedAllocations;
    }

    @Override
    public void onFill(Throwable thrown) {
        // Reading the live stack is far slower than anything else: only when a frame may show ids, and only down to it.
        Object backtrace = live.backtrace(thrown);
        int last = lastWithIds(backtrace);
        List<Captured> captured = last < 0 ? List.of()
                : live.walker().walk(frames -> capture(frames, thrown, Backtrace.of(backtrace, last + 1)));
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
            for (Captured frame : captured) {
                if (frame.index() < trace.length) {
                    trace[frame.index()] = format.rewrite(trace[frame.index()], frame.receiverIds(), frame.paramIds());
                }
            }
        }
    }

    @Override
    public void onSetTrace(Throwable thrown) {
        pending.remove(thrown);
    }

    /**
     * From the recorded frames, without walking the stack: the index of the last frame that shows ids or whose method
     * was not seen yet, or -1. The recorded frames end at {@code -XX:MaxJavaStackTraceDepth}, as the trace does. Also -1
     * while a class is being loaded, e.g. for the {@code ClassNotFoundException}s of class loaders: looking at classes
     * then could load the class being loaded again.
     */
    private int lastWithIds(Object backtrace) {
        int last = -1;
        int index = 0;
        for (Object chunk = backtrace; chunk != null; chunk = Backtrace.next(chunk)) {
            Object[] classes = Backtrace.classes(chunk);
            short[] methods = Backtrace.methods(chunk);
            for (int k = 0; k < classes.length; k++, index++) {
                Object type = classes[k];
                if (type == null) {
                    return last;
                }
                FrameSpecs.ClassInfo info = specs.of((Class<?>) type);
                if (info.loader) {
                    return -1;
                }
                if (info.byNumber(methods[k] & 0xFFFF) != FrameSpecs.NONE) {
                    last = index;
                }
            }
        }
        return last;
    }

    /** How many recorded frames may be missing from the walk, should it leave out some. */
    private static final int SEARCH = 8;

    /**
     * Walks the live stack down to the last of the recorded frames, and captures the ids of those of the configured
     * methods. Each walked frame learns its method's spec for the recorded number of its method, see
     * {@link #lastWithIds}.
     */
    private List<Captured> capture(Stream<StackWalker.StackFrame> frames, Throwable thrown, Backtrace recorded) {
        Iterator<StackWalker.StackFrame> iterator = frames.iterator();
        StackWalker.StackFrame frame = firstTraceFrame(iterator, thrown);
        List<Captured> captured = new ArrayList<>();
        int index = 0;
        while (frame != null && index < recorded.length) {
            Class<?> type = frame.getDeclaringClass();
            int bci = frame.isNativeMethod() ? -1 : frame.getByteCodeIndex();
            int at = find(recorded, index, type, bci);
            if (at >= 0) {
                index = at;
                FrameSpecs.ClassInfo info = specs.of(type);
                if (info.relevant) {
                    FrameSpecs.Spec spec = specs.of(frame, info, live.method(frame));
                    info.learn(recorded.methods[index] & 0xFFFF, spec);
                    if (spec != FrameSpecs.NONE) {
                        Captured ids = ids(frame, spec, index);
                        if (ids != null) {
                            captured.add(ids);
                        }
                    }
                }
                index++;
            }
            frame = iterator.hasNext() ? iterator.next() : null;
        }
        return captured;
    }

    /** The index of the recorded frame at or after {@code from} that is this frame (a native one has no bci), or -1. */
    private static int find(Backtrace recorded, int from, Class<?> type, int bci) {
        int end = Math.min(recorded.length, from + SEARCH + 1);
        for (int index = from; index < end; index++) {
            if (recorded.classes[index] == type && (bci < 0 || recorded.bcis[index] == bci)) {
                return index;
            }
        }
        if (Log.isDebug()) {
            Log.debug(() -> "live stack: a frame of " + type.getName() + " is not at position " + from + " of the stack trace, so it shows no ids");
        }
        return -1;
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
        return new Captured(index, receiverIds, List.copyOf(paramIds));
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
}
