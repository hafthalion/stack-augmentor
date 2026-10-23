package com.hafnium.stackaugmentor.runtime.handler;

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.WeakIdentityMap;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ClassLoaderConfigs;
import com.hafnium.stackaugmentor.runtime.config.Startup;
import com.hafnium.stackaugmentor.runtime.values.FrameFormat;
import com.hafnium.stackaugmentor.runtime.values.ValueResolver;
import com.hafnium.stackaugmentor.runtime.values.NamedValue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Called when an exception leaves an instrumented method: finds that method's frame in the
 * exception's stack trace and augments it.
 *
 * <p>The Java agent creates it with the JVM's configuration and installs it. With build-time
 * instrumentation no agent does that: {@link Dispatch} creates it through {@code ServiceLoader} with the
 * no-argument constructor, which reads the global configuration from {@code -Dstackaugmentor.config}, and those on
 * the classpath as {@link ClassLoaderConfigs} describes. What gets instrumented was decided at build
 * time; at runtime, {@code [augment]}, {@code [augment.exceptions]}
 * and the {@code [augment.receiver]} entries apply, the latter by the key of the entry that decided the method at build time: a method without such an
 * entry shows no receiver members, even if its class is annotated.
 *
 * <p>Each frame uses the configuration of its method's class ({@link ClassLoaderConfigs}), so the applications of one
 * server, and the jars of one application, can have their own; what applies to the whole JVM comes from system properties.
 */
public final class ThrowHandler implements Dispatch.Handler {

    public static final String CLASSPATH_CONFIG = ClassLoaderConfigs.RESOURCE;

    static final String NO_RUNTIME_CONFIG = "no " + ClassLoaderConfigs.CONTEXT_RESOURCE + " or " + CLASSPATH_CONFIG
            + " found for the class and no -D" + AugmentorConfig.CONFIG_PROPERTY + ", so the built-in configuration is used";

    /** Reflection frames are part of stack traces, so the walker shows them too. */
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.SHOW_REFLECT_FRAMES);

    private static final String DISPATCH = Dispatch.class.getName();

    /** What a configuration gives the handler. */
    public interface Context {

        ValueResolver resolver();

        FrameFormat format();

        static Context of(AugmentorConfig config) {
            return of(new ValueResolver(config), FrameFormat.create(config));
        }

        static Context of(ValueResolver resolver, FrameFormat format) {
            return new Values(resolver, format);
        }
    }

    private record Values(ValueResolver resolver, FrameFormat format) implements Context {
    }

    private final ClassLoaderConfigs<? extends Context> contexts;
    private final StackTraces stackTraces;
    /** Set when no configuration file was found, until the built-in configuration was first used, for a warning. */
    private final AtomicBoolean builtInUnannounced = new AtomicBoolean();

    /**
     * Per throwable, the index after the last frame handled. Frames unwind from the top of the trace
     * downwards, so the search continues from there; this keeps recursive calls apart.
     */
    private final WeakIdentityMap<Throwable, Integer> cursors = new WeakIdentityMap<>();

    /**
     * Per cause or suppressed exception, the exception whose frames below the last shared frame it was found to share,
     * see {@link #sharesBelow}. Weakly referenced: a cause must not keep the exception that wraps it alive.
     */
    private final WeakIdentityMap<Throwable, WeakReference<Throwable>> shared = new WeakIdentityMap<>();

    public ThrowHandler(ClassLoaderConfigs<? extends Context> contexts, StackTraces stackTraces) {
        this.contexts = contexts;
        this.stackTraces = stackTraces;
    }

    public ThrowHandler(ValueResolver resolver, FrameFormat format, StackTraces stackTraces) {
        this(ClassLoaderConfigs.fixed(Context.of(resolver, format)), stackTraces);
    }

    public ThrowHandler(ValueResolver resolver, FrameFormat format) {
        this(resolver, format, StackTraces.copying());
    }

    public ThrowHandler(AugmentorConfig config) {
        this(new ValueResolver(config), FrameFormat.create(config));
    }

    /** For {@code ServiceLoader}: build-time instrumentation. */
    public ThrowHandler() {
        this(runtimeConfigs());
    }

    private ThrowHandler(ClassLoaderConfigs<Context> contexts) {
        this(contexts, runtimeStackTraces());
        builtInUnannounced.set(contexts.builtInOnly());
    }

    @Override
    public void onThrow(Object self, Throwable thrown, String receiverKey, Class<?> ownerClass, String owner, String method,
                        Object[] args, String[] paramNames) {
        Context context = ownerClass != null ? contexts.forClass(ownerClass) : contexts.base();
        if (context == null) {
            return;
        }
        if (context == contexts.base() && builtInUnannounced.compareAndSet(true, false)) {
            Log.warn(Startup.RUNTIME + ": " + NO_RUNTIME_CONFIG);
        }
        ValueResolver resolver = context.resolver();
        if (!resolver.augments(thrown)) {
            return;
        }
        StackTraceElement[] trace = stackTraces.read(thrown);
        if (trace.length == 0) {
            return;
        }
        Integer cursor = cursors.get(thrown);
        Caller caller = null;
        int index = -1;
        for (int i = cursor != null ? cursor : 0; i < trace.length; i++) {
            if (trace[i].getClassName().equals(owner) && trace[i].getMethodName().equals(method)) {
                if (caller == null) {
                    caller = caller();
                }
                if (caller.calledFrom(trace, i)) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) {
            // Created elsewhere, e.g. stored and rethrown later, or rethrown from another thread.
            if (Log.isDebug()) {
                Log.debug(() -> owner + "." + method + ": frame not found in the stack trace of " + thrown.getClass().getName()
                        + ", which was created elsewhere");
            }
            return;
        }
        cursors.set(thrown, index + 1);

        List<NamedValue> receiverValues = self != null && receiverKey != null ? resolver.receiverValues(self, owner, receiverKey) : List.of();
        List<NamedValue> paramValues = new ArrayList<>();
        if (args != null && paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                // The instrumentation marks the labels of hashed values with a trailing '#'.
                String name = paramNames[i];
                boolean hashed = name.endsWith("#");
                paramValues.add(hashed
                        ? new NamedValue(name.substring(0, name.length() - 1), resolver.hashedParamValue(args[i]))
                        : new NamedValue(name, resolver.paramValue(args[i])));
            }
        }
        if (receiverValues.isEmpty() && paramValues.isEmpty()) {
            return;
        }

        StackTraceElement original = trace[index];
        FrameFormat format = context.format();
        stackTraces.write(thrown, trace, index, format.rewrite(original, receiverValues, paramValues));
        if (thrown.getCause() != null || thrown.getSuppressed().length > 0) {
            rewriteShared(format, thrown, trace, index, original, receiverValues, paramValues);
        }
    }

    /**
     * Writes the rewritten frame {@code trace[index]} of {@code thrown} also into those of its causes and suppressed
     * exceptions, and theirs, that share the frame: the same frame at the same distance from the bottom, with the same
     * frames below it. Printed traces then still collapse the shared frames into {@code ... N more}, which compares
     * frames with {@code equals}. A frame of the same call at another line, e.g. where the method caught the cause that
     * it wraps, gets the same values with its own line.
     */
    private void rewriteShared(FrameFormat format, Throwable thrown, StackTraceElement[] trace, int index, StackTraceElement original,
            List<NamedValue> receiverValues, List<NamedValue> paramValues) {
        int below = trace.length - index - 1;
        List<Throwable> related = new ArrayList<>();
        related.add(thrown);
        addRelated(thrown, related);
        for (int r = 1; r < related.size(); r++) {
            Throwable other = related.get(r);
            StackTraceElement[] otherTrace = stackTraces.read(other);
            int otherIndex = otherTrace.length - below - 1;
            if (otherIndex >= 0 && sameMethod(otherTrace[otherIndex], original)
                    && sharesBelow(thrown, trace, index, other, otherTrace, otherIndex)) {
                StackTraceElement frame = otherTrace[otherIndex];
                stackTraces.write(other, otherTrace, otherIndex,
                        frame.equals(original) ? trace[index] : format.rewrite(frame, receiverValues, paramValues));
            }
            addRelated(other, related);
        }
    }

    /**
     * Whether the frames below {@code otherTrace[otherIndex]} are those below {@code trace[index]}. Frames unwind from
     * the top of the trace downwards, so once they are, they stay so for the following frames of {@code thrown}, which
     * are further down: then only the frame itself is compared, and not all the frames below it again.
     */
    private boolean sharesBelow(Throwable thrown, StackTraceElement[] trace, int index, Throwable other,
            StackTraceElement[] otherTrace, int otherIndex) {
        WeakReference<Throwable> sharedWith = shared.get(other);
        if (sharedWith != null && sharedWith.get() == thrown) {
            return true;
        }
        if (!Arrays.equals(otherTrace, otherIndex + 1, otherTrace.length, trace, index + 1, trace.length)) {
            return false;
        }
        shared.set(other, new WeakReference<>(thrown));
        return true;
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
        if (cause != null && !containsIdentical(related, cause)) {
            related.add(cause);
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            if (!containsIdentical(related, suppressed)) {
                related.add(suppressed);
            }
        }
    }

    private static boolean containsIdentical(List<Throwable> throwables, Throwable throwable) {
        for (Throwable each : throwables) {
            if (each == throwable) {
                return true;
            }
        }
        return false;
    }

    /**
     * The frame below the exiting method on the current thread, to tell that method's frame apart from other frames of
     * the same method: the exception may have been created outside it, e.g. by its caller in a recursion, and then the
     * trace has no frame for this call.
     */
    private record Caller(boolean known, String className, String methodName, int lineNumber) {

        static final Caller UNKNOWN = new Caller(false, null, null, -1);
        static final Caller NONE = new Caller(true, null, null, -1);

        /** Whether {@code trace[index]} is the frame of the exiting method: the frame below it is its caller's. */
        boolean calledFrom(StackTraceElement[] trace, int index) {
            if (!known) {
                return true;
            }
            if (index + 1 >= trace.length) {
                // The bottom of the trace: the exiting method has no caller, or the trace was cut at its maximum depth.
                return true;
            }
            StackTraceElement below = trace[index + 1];
            return below.getClassName().equals(className) && below.getMethodName().equals(methodName)
                    && below.getLineNumber() == lineNumber;
        }
    }

    /**
     * The caller of the exiting method, which is below {@link Dispatch#onThrow} and that method. {@link Caller#UNKNOWN}
     * when this handler was not called through {@link Dispatch}, e.g. in a test.
     */
    private static Caller caller() {
        return WALKER.walk(frames -> {
            Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getClassName().equals(DISPATCH)) {
                    StackWalker.StackFrame exiting = iterator.hasNext() ? iterator.next() : null;
                    while (exiting != null && exiting.getClassName().equals(DISPATCH)) {
                        exiting = iterator.hasNext() ? iterator.next() : null;
                    }
                    if (!iterator.hasNext()) {
                        return Caller.NONE;
                    }
                    StackWalker.StackFrame frame = iterator.next();
                    return new Caller(true, frame.getClassName(), frame.getMethodName(), frame.getLineNumber());
                }
            }
            return Caller.UNKNOWN;
        });
    }

    /**
     * The configurations for build-time instrumentation: the system property, then those on the classpath, then the
     * built-in configuration, with a warning when a class first uses it alone. An invalid one of the JVM or of the system class
     * loader is printed as an error; {@link Dispatch} then warns that there is no handler.
     */
    private static ClassLoaderConfigs<Context> runtimeConfigs() {
        return Startup.load(Startup.RUNTIME, () -> ClassLoaderConfigs.create(Startup.RUNTIME, AugmentorConfig.global(),
                AugmentorConfig.location(), Context::of));
    }

    /**
     * For build-time instrumentation, no agent opens {@code java.lang}: frames are written in place
     * only if the application was started with
     * {@code --add-opens java.base/java.lang=ALL-UNNAMED}, and copied otherwise.
     */
    static StackTraces runtimeStackTraces() {
        return StackTraces.configured(Startup.RUNTIME, "start the application with --add-opens java.base/java.lang=ALL-UNNAMED");
    }
}
