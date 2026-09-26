package com.hafnium.stackaugmentor.runtime;

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Called when an exception leaves an instrumented method: finds that method's frame in the
 * exception's stack trace and attaches the ids to it.
 *
 * <p>The Java agent creates it with the agent's configuration and installs it. With build-time
 * instrumentation no agent does that: {@link Dispatch} creates it through {@code ServiceLoader} with the
 * no-argument constructor, which reads the configuration from {@code -Dstackaugmentor.config} or from
 * {@code stack-augmentor.toml} on the classpath. What gets instrumented was decided at build time; at runtime,
 * {@code [augment]}, {@code debug} and the {@code [instrument.classes]} entries apply, and classes without an entry
 * use their annotations.
 */
public final class ThrowHandler implements Dispatch.Handler {

    public static final String CLASSPATH_CONFIG = "stack-augmentor.toml";

    private final IdResolver resolver;
    private final FrameFormat format;

    /**
     * Per throwable, the index after the last frame handled. Frames unwind from the top of the trace
     * downwards, so the search continues from there; this keeps recursive calls apart.
     */
    private final WeakIdentityMap<Throwable, Integer> cursors = new WeakIdentityMap<>();

    public ThrowHandler(IdResolver resolver, FrameFormat format) {
        this.resolver = resolver;
        this.format = format;
    }

    public ThrowHandler(AugmentorConfig config) {
        this(config, false);
    }

    /**
     * For {@code ServiceLoader}: build-time instrumentation. The build plugin already chose which classes to
     * instrument, so classes without an {@code [instrument.classes]} entry use their annotations.
     */
    public ThrowHandler() {
        this(runtimeConfig(), true);
    }

    private ThrowHandler(AugmentorConfig config, boolean annotationsWithoutEntry) {
        this(new IdResolver(config, annotationsWithoutEntry), FrameFormat.create(config));
    }

    @Override
    public void onThrow(Object self, Throwable thrown, String owner, String method, Object[] paramValues, String[] paramNames) {
        StackTraceElement[] trace = thrown.getStackTrace();
        if (trace.length == 0) {
            return;
        }
        Integer cursor = cursors.get(thrown);
        int index = -1;
        for (int i = cursor != null ? cursor : 0; i < trace.length; i++) {
            if (trace[i].getClassName().equals(owner) && trace[i].getMethodName().equals(method)) {
                index = i;
                break;
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

        NamedId receiverId = self != null ? resolver.receiverId(self) : null;
        List<NamedId> paramIds = new ArrayList<>();
        int omitted = 0;
        if (paramValues != null && paramNames != null) {
            // Only the parameters that will be shown are resolved: an id source or toString() may be expensive.
            int shown = Math.min(paramNames.length, format.maxParams());
            for (int i = 0; i < shown; i++) {
                paramIds.add(new NamedId(paramNames[i], resolver.paramId(paramValues[i])));
            }
            omitted = paramNames.length - shown;
        }
        if (receiverId == null && paramIds.isEmpty()) {
            return;
        }

        trace[index] = format.rewrite(trace[index], receiverId, paramIds, omitted);
        thrown.setStackTrace(trace); // no effect if the throwable's stack trace is not writable
    }

    /** The configuration for build-time instrumentation: the system property, then the classpath, then the defaults. */
    private static AugmentorConfig runtimeConfig() {
        AugmentorConfig config;
        String source;
        String location = AugmentorConfig.location(null);
        URL resource;
        if (location != null) {
            config = AugmentorConfig.load((String) null);
            source = location;
        } else if ((resource = classpathConfig()) != null) {
            config = AugmentorConfig.parse(read(resource), CLASSPATH_CONFIG);
            source = resource.toString();
        } else {
            config = new AugmentorConfig();
            source = "none, using the defaults";
        }
        Log.setDebug(config.debug());
        Log.debug(() -> "build-time instrumentation, configuration: " + source);
        return config;
    }

    private static URL classpathConfig() {
        ClassLoader loader = ThrowHandler.class.getClassLoader();
        return loader != null ? loader.getResource(CLASSPATH_CONFIG) : null;
    }

    private static String read(URL resource) {
        try (InputStream in = resource.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
