package com.hafnium.stackaugmentor.runtime.handler;

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.WeakIdentityMap;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.Startup;
import com.hafnium.stackaugmentor.runtime.ids.FrameFormat;
import com.hafnium.stackaugmentor.runtime.ids.IdResolver;
import com.hafnium.stackaugmentor.runtime.ids.NamedId;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Called when an exception leaves an instrumented method: finds that method's frame in the
 * exception's stack trace and attaches the ids to it.
 *
 * <p>The Java agent creates it with the agent's configuration and installs it. With build-time
 * instrumentation no agent does that: {@link Dispatch} creates it through {@code ServiceLoader} with the
 * no-argument constructor, which reads the configuration from {@code -Dstackaugmentor.config} or from
 * {@code stack-augmentor.toml} on the classpath. What gets instrumented was decided at build time; at runtime,
 * {@code [augment]}, {@code debug} and the {@code [augment.receiver]} entries apply, as with the agent: a class
 * without an entry gets no receiver id, even if it is annotated.
 */
public final class ThrowHandler implements Dispatch.Handler {

    public static final String CLASSPATH_CONFIG = "stack-augmentor.toml";

    static final String NO_RUNTIME_CONFIG = "no " + CLASSPATH_CONFIG + " on the classpath and no -D"
            + AugmentorConfig.CONFIG_PROPERTY + ", so frames show no receiver ids";

    /** Reflection frames are part of stack traces, so the walker shows them too. */
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.SHOW_REFLECT_FRAMES);

    private static final String DISPATCH = Dispatch.class.getName();

    private final IdResolver resolver;
    private final FrameFormat format;
    private final StackTraces stackTraces;

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

    public ThrowHandler(IdResolver resolver, FrameFormat format, StackTraces stackTraces) {
        this.resolver = resolver;
        this.format = format;
        this.stackTraces = stackTraces;
    }

    public ThrowHandler(IdResolver resolver, FrameFormat format) {
        this(resolver, format, StackTraces.copying());
    }

    public ThrowHandler(AugmentorConfig config) {
        this(new IdResolver(config), FrameFormat.create(config));
    }

    /** For {@code ServiceLoader}: build-time instrumentation. */
    public ThrowHandler() {
        this(runtimeConfig(), Startup.RUNTIME);
    }

    private ThrowHandler(AugmentorConfig config, String component) {
        this(new IdResolver(config), FrameFormat.create(config), Startup.load(component, () -> runtimeStackTraces(config)));
    }

    @Override
    public void onThrow(Object self, Throwable thrown, String owner, String method, Object[] paramValues, String[] paramNames) {
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

        List<NamedId> receiverIds = self != null ? resolver.receiverIds(self, owner) : List.of();
        List<NamedId> paramIds = new ArrayList<>();
        if (paramValues != null && paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                // The instrumentation marks the labels of hashed values with a trailing '#'.
                String name = paramNames[i];
                boolean hashed = name.endsWith("#");
                paramIds.add(hashed
                        ? new NamedId(name.substring(0, name.length() - 1), resolver.hashedParamId(paramValues[i]))
                        : new NamedId(name, resolver.paramId(paramValues[i])));
            }
        }
        if (receiverIds.isEmpty() && paramIds.isEmpty()) {
            return;
        }

        StackTraceElement original = trace[index];
        stackTraces.write(thrown, trace, index, format.rewrite(original, receiverIds, paramIds));
        if (thrown.getCause() != null || thrown.getSuppressed().length > 0) {
            rewriteShared(thrown, trace, index, original, receiverIds, paramIds);
        }
    }

    /**
     * Writes the rewritten frame {@code trace[index]} of {@code thrown} also into those of its causes and suppressed
     * exceptions, and theirs, that share the frame: the same frame at the same distance from the bottom, with the same
     * frames below it. Printed traces then still collapse the shared frames into {@code ... N more}, which compares
     * frames with {@code equals}. A frame of the same call at another line, e.g. where the method caught the cause that
     * it wraps, gets the same ids with its own line.
     */
    private void rewriteShared(Throwable thrown, StackTraceElement[] trace, int index, StackTraceElement original,
            List<NamedId> receiverIds, List<NamedId> paramIds) {
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
                        frame.equals(original) ? trace[index] : format.rewrite(frame, receiverIds, paramIds));
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
                    if (iterator.hasNext()) {
                        iterator.next(); // the exiting method
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
     * The configuration for build-time instrumentation: the system property, then the classpath, then the defaults. An
     * invalid one is printed as an error; {@link Dispatch} then warns that there is no handler.
     */
    private static AugmentorConfig runtimeConfig() {
        String location = AugmentorConfig.location(null);
        URL resource = location == null ? classpathConfig() : null;
        AugmentorConfig config = Startup.load(Startup.RUNTIME, () -> {
            if (location != null) {
                return AugmentorConfig.load((String) null);
            }
            return resource != null ? AugmentorConfig.parse(read(resource), CLASSPATH_CONFIG) : new AugmentorConfig();
        });
        Startup.configure(Startup.RUNTIME, location != null ? location : resource != null ? resource.toString() : "none, using the defaults",
                config);
        if (location == null && resource == null) {
            Log.warn(Startup.RUNTIME + ": " + NO_RUNTIME_CONFIG);
        }
        return config;
    }

    /**
     * For build-time instrumentation, no agent opens {@code java.lang}: frames can be written in place
     * ({@code inPlaceModification}) only if the application was started with
     * {@code --add-opens java.base/java.lang=ALL-UNNAMED}.
     */
    static StackTraces runtimeStackTraces(AugmentorConfig config) {
        return StackTraces.configured(config, "start the application with --add-opens java.base/java.lang=ALL-UNNAMED");
    }

    /**
     * The runtime jar's class loader, then the context class loader of the thread that first throws: in an application
     * server or a fat jar, the runtime jar can sit in a parent loader that does not see the application's resources.
     */
    private static URL classpathConfig() {
        ClassLoader own = ThrowHandler.class.getClassLoader();
        URL resource = own != null ? own.getResource(CLASSPATH_CONFIG) : null;
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (resource == null && context != null && context != own) {
            resource = context.getResource(CLASSPATH_CONFIG);
        }
        return resource;
    }

    private static String read(URL resource) {
        try (InputStream in = resource.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
