package com.hafnium.stackaugmentor.instrument.live;

import com.hafnium.stackaugmentor.instrument.bridge.LiveDispatch;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.WeakIdentityMap;
import com.hafnium.stackaugmentor.runtime.config.ClassLoaderConfigs;
import com.hafnium.stackaugmentor.runtime.handler.ThrowHandler;
import com.hafnium.stackaugmentor.runtime.values.FrameFormat;
import com.hafnium.stackaugmentor.runtime.values.ValueResolver;
import com.hafnium.stackaugmentor.runtime.values.NamedValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The live-stack mode's handler. When the VM records the stack trace of a throwable, the frames it recorded are still
 * on the stack: for those of the configured methods, it reads the receiver and the arguments from the live stack and
 * turns them into values right away. When the stack trace array is created from the recorded frames, which happens once,
 * when the trace is first read or printed, it rewrites those frames in that array.
 *
 * <p>So it sees every frame of the trace, also those of constructors while they call {@code super(...)}, and of the
 * methods that catch the exception or called them, and it matches frames by position: recursion needs no special
 * care.
 *
 * <p>Each frame uses the configuration of the class loader of its method's class ({@link ClassLoaderConfigs}): whether
 * it augments the throwable, which values the frame shows and how. A throwable that no configuration augments is left
 * alone without a look at the stack.
 */
public final class LiveFrames implements LiveDispatch.Handler {

    /** Shown for an argument that the JIT optimized away (scalar replacement): it reads as {@code null}. */
    public static final String UNKNOWN = "?";

    /** What a configuration gives the live-stack mode. */
    public interface Context extends ThrowHandler.Context {

        FrameSpecs specs();
    }

    /** The values of one frame, at its position in the trace, and how its configuration writes them. */
    record Captured(int index, String className, String methodName, int lineNumber, List<NamedValue> receiverValues, List<NamedValue> paramValues,
                    FrameFormat format) {
    }

    private final StackWalker plain;
    private final LiveStackFrames live;
    private final ClassLoaderConfigs<? extends Context> contexts;
    /** Frames beyond this depth are not in the trace: {@code -XX:MaxJavaStackTraceDepth}. */
    private final int maxDepth;
    /** Whether a {@code null} object in a compiled frame may be one the JIT optimized away: {@code -XX:+EliminateAllocations}. */
    private final boolean eliminatedAllocations;

    /** The captured frames of the throwables whose stack trace array was not created yet. */
    private final WeakIdentityMap<Throwable, List<Captured>> pending = new WeakIdentityMap<>();

    public LiveFrames(LiveStackFrames live, ClassLoaderConfigs<? extends Context> contexts, int maxDepth, boolean eliminatedAllocations) {
        this.plain = live.plainWalker();
        this.live = live;
        this.contexts = contexts;
        this.maxDepth = maxDepth;
        this.eliminatedAllocations = eliminatedAllocations;
    }

    @Override
    public void onFill(Throwable thrown) {
        if (!contexts.anyMatches(context -> context.resolver().augments(thrown))) {
            // Also when the application fills in the trace again.
            pending.remove(thrown);
            return;
        }
        // Reading local variables is far slower than walking the stack: only when a frame is augmented, and only down to it.
        int last = plain.walk(frames -> lastAugmented(frames, thrown));
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
        if (captured == null) {
            return;
        }
        boolean related = thrown.getCause() != null || thrown.getSuppressed().length > 0;
        StackTraceElement[] original = related ? trace.clone() : null;
        Captured[] rewritten = rewrite(trace, captured);
        if (related) {
            rewriteShared(thrown, trace, original, rewritten);
        }
    }

    /**
     * Writes the rewritten frames of {@code thrown} also into its causes and suppressed exceptions, and theirs, where
     * they share them: the same frames at the same distance from the bottom, whatever values those exceptions show there
     * themselves, or whether {@code [augment.exceptions]} augments them. Printed traces then still collapse the shared
     * frames into {@code ... N more}, which compares frames with {@code equals}. The frame of the same call at another
     * line, where the method caught the cause that it wraps, gets the same values with its own line. As with instrumented
     * classes.
     */
    private void rewriteShared(Throwable thrown, StackTraceElement[] trace, StackTraceElement[] original, Captured[] rewritten) {
        List<Throwable> related = new ArrayList<>();
        related.add(thrown);
        addRelated(thrown, related);
        for (int r = 1; r < related.size(); r++) {
            Throwable other = related.get(r);
            // Its array is created here if it was not yet, as the VM recorded it: this handler is not called again now.
            List<Captured> otherCaptured = pending.remove(other);
            StackTraceElement[] otherTrace = other.getStackTrace();
            StackTraceElement[] otherOriginal = otherCaptured != null ? otherTrace.clone() : null;
            boolean changed = false;
            if (otherCaptured != null) {
                rewrite(otherTrace, otherCaptured);
                changed = true;
            }
            for (int i = trace.length - 1, j = otherTrace.length - 1; i >= 0 && j >= 0; i--, j--) {
                boolean shared = otherOriginal != null ? otherOriginal[j].equals(original[i])
                        : otherTrace[j].equals(original[i]) || otherTrace[j].equals(trace[i]);
                if (!shared) {
                    StackTraceElement plain = otherOriginal != null ? otherOriginal[j] : otherTrace[j];
                    if (rewritten[i] != null && sameMethod(plain, original[i])) {
                        otherTrace[j] = rewritten[i].format().rewrite(plain, rewritten[i].receiverValues(), rewritten[i].paramValues());
                        changed = true;
                    }
                    break;
                }
                if (rewritten[i] != null && !otherTrace[j].equals(trace[i])) {
                    otherTrace[j] = trace[i];
                    changed = true;
                }
            }
            if (changed) {
                other.setStackTrace(otherTrace);
            }
            addRelated(other, related);
        }
    }

    /** Whether both frames are of the same method, at any line. */
    private static boolean sameMethod(StackTraceElement a, StackTraceElement b) {
        return a.getClassName().equals(b.getClassName()) && a.getMethodName().equals(b.getMethodName())
                && Objects.equals(a.getFileName(), b.getFileName()) && Objects.equals(a.getModuleName(), b.getModuleName())
                && Objects.equals(a.getClassLoaderName(), b.getClassLoaderName());
    }

    /** Adds the cause and the suppressed exceptions of {@code throwable} that are not in {@code related} yet. */
    private static void addRelated(Throwable throwable, List<Throwable> related) {
        Throwable cause = throwable.getCause();
        if (cause != null && related.stream().noneMatch(it -> it == cause)) {
            related.add(cause);
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            if (related.stream().noneMatch(it -> it == suppressed)) {
                related.add(suppressed);
            }
        }
    }

    @Override
    public void onSetTrace(Throwable thrown) {
        pending.remove(thrown);
    }

    /**
     * The trace index of the last augmented frame, or -1. Also -1 while a class is being loaded, e.g. for the
     * {@code ClassNotFoundException}s of class loaders: looking at classes then could load the class being loaded again.
     */
    private int lastAugmented(Stream<StackWalker.StackFrame> frames, Throwable thrown) {
        Iterator<StackWalker.StackFrame> iterator = frames.iterator();
        StackWalker.StackFrame frame = firstTraceFrame(iterator, thrown);
        int last = -1;
        for (int index = 0; frame != null; index++) {
            if (ClassLoader.class.isAssignableFrom(frame.getDeclaringClass())) {
                return -1;
            }
            if (index < maxDepth && spec(frame, thrown) != FrameSpecs.NONE) {
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
            Context context = contexts.forClass(frame.getDeclaringClass());
            FrameSpecs.Spec spec = spec(context, frame, thrown);
            if (spec != FrameSpecs.NONE) {
                Captured values = capture(context, frame, spec, index);
                if (values != null) {
                    captured.add(values);
                }
            }
            frame = iterator.hasNext() ? iterator.next() : null;
        }
        return captured;
    }

    /** What the frame shows: nothing where the configuration of its class does not augment the throwable. */
    private FrameSpecs.Spec spec(StackWalker.StackFrame frame, Throwable thrown) {
        return spec(contexts.forClass(frame.getDeclaringClass()), frame, thrown);
    }

    private static FrameSpecs.Spec spec(Context context, StackWalker.StackFrame frame, Throwable thrown) {
        return context != null && context.resolver().augments(thrown) ? context.specs().of(frame) : FrameSpecs.NONE;
    }

    private Captured capture(Context context, StackWalker.StackFrame frame, FrameSpecs.Spec spec, int index) {
        ValueResolver resolver = context.resolver();
        Object[] locals = live.locals(frame);
        boolean compiled = live.compiled(frame);
        List<NamedValue> receiverValues = List.of();
        if (spec.receiver()) {
            Object self = locals.length > 0 ? locals[0] : null;
            // Not an instance of the declaring class: the JIT optimized the object away, so there is nothing to show.
            if (spec.owner().isInstance(self)) {
                receiverValues = resolver.receiverValues(self, spec.owner().getName(), spec.receiverKey());
            }
        }
        List<NamedValue> paramValues = new ArrayList<>(spec.params().size());
        for (FrameSpecs.Param param : spec.params()) {
            paramValues.add(new NamedValue(param.label(), paramValue(resolver, locals, param, compiled)));
        }
        if (receiverValues.isEmpty() && paramValues.isEmpty()) {
            return null;
        }
        return new Captured(index, frame.getClassName(), frame.getMethodName(), frame.getLineNumber(), receiverValues, List.copyOf(paramValues),
                context.format());
    }

    private String paramValue(ValueResolver resolver, Object[] locals, FrameSpecs.Param param, boolean compiled) {
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
        return param.hashed() ? resolver.hashedParamValue(value) : resolver.paramValue(value);
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
     *
     * @return the captured frame of each rewritten position of the trace, null for the others
     */
    private Captured[] rewrite(StackTraceElement[] trace, List<Captured> captured) {
        Captured[] rewritten = new Captured[trace.length];
        int shift = 0;
        for (Captured frame : captured) {
            int index = find(trace, frame, frame.index() + shift);
            if (index < 0) {
                if (Log.isDebug()) {
                    Log.debug(() -> "live stack: " + frame.className() + "." + frame.methodName() + " is not at position " + frame.index()
                            + " of the stack trace, so it is not augmented");
                }
                continue;
            }
            shift = index - frame.index();
            trace[index] = frame.format().rewrite(trace[index], frame.receiverValues(), frame.paramValues());
            rewritten[index] = frame;
        }
        return rewritten;
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
