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
 * on the stack: for the configured methods, it keeps a copy of their local variables, so the receiver and the
 * arguments. When the stack trace array is created from the recorded frames, which happens once, when the trace is
 * first read or printed, it turns them into values and rewrites those frames in that array.
 *
 * <p>So it sees every frame of the trace, also those of constructors while they call {@code super(...)}, and of the
 * methods that catch the exception or called them, and it matches frames by position: recursion needs no special
 * care.
 *
 * <p>Each frame uses the configuration of the class loader of its method's class ({@link ClassLoaderConfigs}): whether
 * it augments the throwable, which values the frame shows and how. A throwable that no configuration augments is left
 * alone without a look at the stack.
 *
 * <p>Most exceptions are never printed, so creating one costs as little as possible: a walk that sees only the classes
 * of the frames, and, only if one of them may be augmented, a walk that reads the local variables down to it. The
 * values are worked out only when the trace is read.
 *
 * <p>No walked frame is kept: a live frame also holds its operand stack, which references the throwable while its
 * constructor runs (after {@code new X()}), and a kept frame would keep the throwable from ever being collected. The
 * copied local variables hold the throwable itself only as {@link #SELF}.
 */
public final class LiveFrames implements LiveDispatch.Handler {

    /** Shown for an argument that the JIT optimized away (scalar replacement): it reads as {@code null}. */
    public static final String UNKNOWN = "?";

    /** What a configuration gives the live-stack mode. */
    public interface Context extends ThrowHandler.Context {

        FrameSpecs specs();
    }

    /** Stands for the throwable in the copied local variables, e.g. in its own constructors' frames. */
    private static final Object SELF = new Object();

    /**
     * A frame of a configured method at its position in the trace, with a copy of its local variables, and the
     * configuration of its class.
     */
    record Kept(int index, Context context, FrameSpecs.Spec spec, Object[] locals, boolean compiled, String className, String methodName,
                int lineNumber) {
    }

    /** The values of one frame, at its position in the trace, and how its configuration writes them. */
    record Captured(int index, String className, String methodName, int lineNumber, List<NamedValue> receiverValues, List<NamedValue> paramValues,
                    FrameFormat format) {
    }

    private final LiveStackFrames live;
    private final ClassLoaderConfigs<? extends Context> contexts;
    /** Frames beyond this depth are not in the trace: {@code -XX:MaxJavaStackTraceDepth}. */
    private final int maxDepth;
    /** Whether a {@code null} object in a compiled frame may be one the JIT optimized away: {@code -XX:+EliminateAllocations}. */
    private final boolean eliminatedAllocations;

    /** The kept frames of the throwables whose stack trace array was not created yet. */
    private final WeakIdentityMap<Throwable, List<Kept>> pending = new WeakIdentityMap<>();

    public LiveFrames(LiveStackFrames live, ClassLoaderConfigs<? extends Context> contexts, int maxDepth, boolean eliminatedAllocations) {
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
        // Reading local variables is far slower than walking the stack: only when a frame may be augmented, and only down to it.
        int last = live.classWalker().walk(frames -> lastAugmented(frames, thrown));
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
        List<Captured> captured = capture(kept, thrown);
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
            List<Kept> otherKept = pending.remove(other);
            List<Captured> otherCaptured = otherKept != null ? capture(otherKept, other) : null;
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
     * The position in the walk (counted from its first frame, as the walks from {@link #onFill} see the same frames)
     * of the last frame that may be augmented, or -1. Also -1 while a class is being loaded, e.g. for the
     * {@code ClassNotFoundException}s of class loaders: looking at classes then could load the class being loaded again.
     */
    private int lastAugmented(Stream<StackWalker.StackFrame> frames, Throwable thrown) {
        // Classes only; a class is looked at with reflection only once no class loader is on the stack.
        List<Class<?>> candidates = new ArrayList<>();
        List<FrameSpecs> candidateSpecs = new ArrayList<>();
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
            if (position - traceStart < maxDepth) {
                Context context = contexts.forClass(type);
                if (context != null && context.specs().isNamed(type) && context.resolver().augments(thrown)) {
                    candidates.add(type);
                    candidateSpecs.add(context.specs());
                    positions.add(position);
                }
            }
        }
        for (int i = candidates.size() - 1; i >= 0; i--) {
            if (candidateSpecs.get(i).mayAugment(candidates.get(i))) {
                return positions.get(i);
            }
        }
        return -1;
    }

    /** Keeps the frames of the configured methods, down to {@code last}, with their position in the trace. */
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
            Class<?> type = frame.getDeclaringClass();
            Context context = contexts.forClass(type);
            if (context != null && context.specs().mayAugment(type) && context.resolver().augments(thrown)) {
                FrameSpecs.Spec spec = context.specs().of(frame);
                if (spec != FrameSpecs.NONE) {
                    kept.add(new Kept(index, context, spec, copy(live.locals(frame), thrown), live.compiled(frame), frame.getClassName(),
                            frame.getMethodName(), frame.getLineNumber()));
                }
            }
            frame = iterator.hasNext() ? iterator.next() : null;
        }
        return kept;
    }

    /** The local variables, with {@link #SELF} for the throwable. */
    private static Object[] copy(Object[] locals, Throwable thrown) {
        Object[] copy = locals.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == thrown) {
                copy[i] = SELF;
            }
        }
        return copy;
    }

    /** The values of the kept frames that show any. */
    private List<Captured> capture(List<Kept> kept, Throwable thrown) {
        List<Captured> captured = new ArrayList<>(kept.size());
        for (Kept frame : kept) {
            Captured values = capture(frame, thrown);
            if (values != null) {
                captured.add(values);
            }
        }
        return captured;
    }

    private Captured capture(Kept frame, Throwable thrown) {
        Object[] locals = frame.locals().clone();
        for (int i = 0; i < locals.length; i++) {
            if (locals[i] == SELF) {
                locals[i] = thrown;
            }
        }
        ValueResolver resolver = frame.context().resolver();
        FrameSpecs.Spec spec = frame.spec();
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
            paramValues.add(new NamedValue(param.label(), paramValue(resolver, locals, param, frame.compiled())));
        }
        if (receiverValues.isEmpty() && paramValues.isEmpty()) {
            return null;
        }
        return new Captured(frame.index(), frame.className(), frame.methodName(), frame.lineNumber(), receiverValues, List.copyOf(paramValues),
                frame.context().format());
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
