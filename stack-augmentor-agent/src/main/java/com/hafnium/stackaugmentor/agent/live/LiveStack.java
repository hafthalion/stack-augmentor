package com.hafnium.stackaugmentor.agent.live;

import com.hafnium.stackaugmentor.agent.Configured;
import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.instrument.bridge.LiveDispatch;
import com.hafnium.stackaugmentor.instrument.live.LiveFrames;
import com.hafnium.stackaugmentor.instrument.live.LiveStackFrames;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.ClassLoaderConfigs;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The experimental live-stack mode: instead of instrumenting the configured classes, the agent adds code to
 * {@link Throwable} only, and reads the receiver and the arguments of the configured frames from the live stack when
 * the VM records a stack trace ({@link LiveFrames}).
 *
 * <p>It is used when the native library ({@link NativeLibrary}, {@code -agentpath}) was loaded: only with it does the JIT
 * keep the local variables of compiled frames readable. If it cannot be used, nothing is changed, and the agent
 * instruments classes as usual.
 */
public final class LiveStack {

    static final int DEFAULT_MAX_DEPTH = 1024;

    private LiveStack() {
    }

    /** Whether the native library was loaded, which asks for this mode. */
    public static boolean requested() {
        return NativeLibrary.loadedVersion() >= 0;
    }

    /**
     * Installs the live-stack mode. Needs {@code java.lang} open to the agent.
     *
     * @return null, or why the mode cannot be used; then nothing was changed
     */
    public static String install(Instrumentation instrumentation, ClassLoaderConfigs<Configured> contexts) {
        return install(new RunningJvm(instrumentation), contexts);
    }

    /** {@link #install(Instrumentation, ClassLoaderConfigs)} in this {@code jvm}. */
    static String install(LiveJvm jvm, ClassLoaderConfigs<Configured> contexts) {
        int version = jvm.nativeLibraryVersion();
        if (version != NativeLibrary.VERSION) {
            return version == 0
                    ? "the native library could not make the JVM keep local variables readable"
                    : "the native library has version " + version + ", the agent needs version " + NativeLibrary.VERSION;
        }
        LiveStackFrames live;
        try {
            live = jvm.liveStackFrames();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "this JDK has no usable java.lang.LiveStackFrame: " + e;
        }
        String problem = jvm.checkLayout(live);
        if (problem != null) {
            return problem;
        }
        int maxDepth = jvm.vmOption("MaxJavaStackTraceDepth", DEFAULT_MAX_DEPTH);
        boolean eliminatedAllocations = jvm.vmOption("EliminateAllocations", 1) != 0;
        LiveFrames handler = new LiveFrames(live, contexts, maxDepth <= 0 ? Integer.MAX_VALUE : maxDepth, eliminatedAllocations);

        AtomicBoolean probed = new AtomicBoolean();
        LiveDispatch.install(new ProbeHandler(probed));
        LiveJvm.Hook hook;
        try {
            hook = jvm.hookThrowable();
        } catch (RuntimeException e) {
            LiveDispatch.install(null);
            return "cannot add code to java.lang.Throwable: " + e;
        }

        jvm.fillInStackTrace(new ProbeException());
        if (!probed.get()) {
            LiveDispatch.install(null);
            hook.remove();
            return "the code added to java.lang.Throwable is not called";
        }
        // The configurations of class loaders are read when their first class is defined: reading them while a stack trace
        // is recorded could load classes then.
        jvm.addTransformer(new ClassLoaderRegistration(contexts));
        LiveDispatch.install(handler);
        // Classes instrumented at build time still call the handler: the live stack already covers their frames.
        Dispatch.install((self, thrown, receiverKey, ownerClass, owner, method, args, paramNames) -> {
        });
        Log.debug(() -> "agent: reading frames from the live stack (native library version " + version + "), stack trace depth " + maxDepth + (eliminatedAllocations
                ? ", an object argument the JIT optimized away shows as '" + LiveFrames.UNKNOWN + "' (-XX:-EliminateAllocations avoids that)"
                : ""));
        return null;
    }

    /** Reads the configuration of each class loader when it defines its first class, and changes no class. */
    record ClassLoaderRegistration(ClassLoaderConfigs<Configured> contexts) implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (loader != null) {
                contexts.register(loader);
            }
            return null;
        }
    }

    /** Recorded once at startup, to check that the code added to {@code Throwable} calls the handler. */
    static final class ProbeException extends Throwable {
    }

    private record ProbeHandler(AtomicBoolean probed) implements LiveDispatch.Handler {

        @Override
        public void onFill(Throwable thrown) {
            if (thrown instanceof ProbeException) {
                probed.set(true);
            }
        }

        @Override
        public void onTrace(Throwable thrown, StackTraceElement[] trace) {
        }

        @Override
        public void onSetTrace(Throwable thrown) {
        }
    }
}
