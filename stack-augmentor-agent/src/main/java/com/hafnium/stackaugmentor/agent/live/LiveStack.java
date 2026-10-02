package com.hafnium.stackaugmentor.agent.live;

import com.hafnium.stackaugmentor.instrument.IdParameters;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.instrument.bridge.LiveDispatch;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.ids.FrameFormat;
import com.hafnium.stackaugmentor.runtime.ids.IdResolver;
import com.sun.management.HotSpotDiagnosticMXBean;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

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

    /** Reflection frames are part of stack traces, so the walkers show them too. */
    private static final Set<StackWalker.Option> OPTIONS =
            EnumSet.of(StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_REFLECT_FRAMES);

    private static final int DEFAULT_MAX_DEPTH = 1024;

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
    public static String install(Instrumentation instrumentation, AugmentorConfig config, FrameFormat format) {
        int version = NativeLibrary.loadedVersion();
        if (version != NativeLibrary.VERSION) {
            return version == 0
                    ? "the native library could not make the JVM keep local variables readable"
                    : "the native library has version " + version + ", the agent needs version " + NativeLibrary.VERSION;
        }
        LiveStackFrames live;
        try {
            live = new LiveStackFrames(OPTIONS);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "this JDK has no usable java.lang.LiveStackFrame: " + e;
        }
        String problem = Probe.check(live);
        if (problem != null) {
            return problem;
        }
        int maxDepth = vmOption("MaxJavaStackTraceDepth", DEFAULT_MAX_DEPTH);
        boolean eliminatedAllocations = vmOption("EliminateAllocations", 1) != 0;
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));
        LiveFrames handler = new LiveFrames(StackWalker.getInstance(OPTIONS), live, new FrameSpecs(config, matching), new IdResolver(config), format,
                maxDepth <= 0 ? Integer.MAX_VALUE : maxDepth, eliminatedAllocations);

        AtomicBoolean probed = new AtomicBoolean();
        LiveDispatch.install(new ProbeHandler(probed));
        ResettableClassFileTransformer transformer;
        try {
            transformer = hookThrowable(instrumentation);
        } catch (RuntimeException e) {
            LiveDispatch.install(null);
            return "cannot add code to java.lang.Throwable: " + e;
        }
        new ProbeException();
        if (!probed.get()) {
            LiveDispatch.install(null);
            transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            return "the code added to java.lang.Throwable is not called";
        }
        LiveDispatch.install(handler);
        // Classes instrumented at build time still call the handler: the live stack already covers their frames.
        Dispatch.install((self, thrown, owner, method, paramValues, paramNames) -> {
        });
        Log.debug(() -> "agent: reading frames from the live stack (native library version " + version + "), stack trace depth " + maxDepth + (eliminatedAllocations
                ? ", an object argument the JIT optimized away shows as '" + LiveFrames.UNKNOWN + "' (-XX:-EliminateAllocations avoids that)"
                : ""));
        return null;
    }

    private static ResettableClassFileTransformer hookThrowable(Instrumentation instrumentation) {
        return new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.InjectionStrategy.Disabled.INSTANCE)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded,
                                        Throwable throwable) {
                        Log.error("agent: cannot add code to " + typeName + ": " + throwable);
                    }
                })
                .ignore(none())
                .type(is(Throwable.class))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(ThrowableHooks.Fill.class).on(named("fillInStackTrace").and(takesNoArguments())))
                        .visit(Advice.to(ThrowableHooks.Read.class).on(named("getOurStackTrace")))
                        .visit(Advice.to(ThrowableHooks.Replace.class).on(named("setStackTrace"))))
                .installOn(instrumentation);
    }

    /** A numeric or boolean ({@code 1} or {@code 0}) HotSpot option, or the default if the VM does not tell. */
    private static int vmOption(String name, int defaultValue) {
        try {
            String value = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption(name).getValue();
            return switch (value) {
                case "true" -> 1;
                case "false" -> 0;
                default -> Integer.parseInt(value);
            };
        } catch (RuntimeException | LinkageError e) {
            return defaultValue;
        }
    }

    /** Thrown once at startup, to check that the code added to {@code Throwable} calls the handler. */
    private static final class ProbeException extends Throwable {
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

    /** Checks that live stack frames hold the receiver and the arguments where {@link LiveFrames} expects them. */
    static final class Probe {

        private static final long LONG = 0x1122334455667788L;
        private static final int INT = -42;
        private static final String OBJECT = "probe";
        private static final double DOUBLE = 2.5;

        private final LiveStackFrames live;
        private Object[] locals;

        private Probe(LiveStackFrames live) {
            this.live = live;
        }

        static String check(LiveStackFrames live) {
            Probe probe = new Probe(live);
            try {
                probe.run(LONG, INT, OBJECT, DOUBLE, true);
            } catch (RuntimeException e) {
                return "cannot read live stack frames: " + e;
            }
            Object[] locals = probe.locals;
            if (locals == null || locals.length < 8) {
                return "live stack frames have no local variables";
            }
            boolean expected;
            try {
                // this, long (2 slots), int, Object, double (2 slots), boolean
                expected = locals[0] == probe
                        && Long.valueOf(LONG).equals(LiveFrames.primitive(long.class, live.bits(locals[2])))
                        && Integer.valueOf(INT).equals(LiveFrames.primitive(int.class, live.bits(locals[3])))
                        && locals[4] == OBJECT
                        && Double.valueOf(DOUBLE).equals(LiveFrames.primitive(double.class, live.bits(locals[6])))
                        && Boolean.TRUE.equals(LiveFrames.primitive(boolean.class, live.bits(locals[7])));
            } catch (RuntimeException e) {
                expected = false;
            }
            return expected ? null : "live stack frames do not hold the receiver and the arguments as expected";
        }

        private void run(long a, int b, Object c, double d, boolean e) {
            locals = live.walker().walk(frames -> frames
                    .filter(frame -> frame.getDeclaringClass() == Probe.class && frame.getMethodName().equals("run"))
                    .findFirst()
                    .map(live::locals)
                    .orElse(null));
        }
    }
}
