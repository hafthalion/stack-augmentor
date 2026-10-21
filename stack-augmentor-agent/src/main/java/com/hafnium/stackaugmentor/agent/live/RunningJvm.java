package com.hafnium.stackaugmentor.agent.live;

import com.hafnium.stackaugmentor.instrument.live.LiveStackFrames;
import com.hafnium.stackaugmentor.instrument.live.ThrowableHooks;
import com.hafnium.stackaugmentor.runtime.Log;
import com.sun.management.HotSpotDiagnosticMXBean;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.utility.nullability.NeverNull;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;

import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.none;

/** The JVM the agent runs in. */
record RunningJvm(Instrumentation instrumentation) implements LiveJvm {

    @Override
    public int nativeLibraryVersion() {
        return NativeLibrary.loadedVersion();
    }

    @Override
    public LiveStackFrames liveStackFrames() throws ReflectiveOperationException {
        return new LiveStackFrames();
    }

    @Override
    public String checkLayout(LiveStackFrames live) {
        return live.checkLayout();
    }

    @Override
    public int vmOption(String name, int defaultValue) {
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

    @Override
    public Hook hookThrowable() {
        ResettableClassFileTransformer transformer = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.InjectionStrategy.Disabled.INSTANCE)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(@NeverNull String typeName, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded,
                                        @NeverNull Throwable throwable) {
                        Log.error("agent: cannot add code to " + typeName + ": " + throwable);
                    }
                })
                .ignore(none())
                .type(is(Throwable.class))
                .transform((builder, type, classLoader, module, protectionDomain) -> ThrowableHooks.addTo(builder))
                .installOn(instrumentation);
        return () -> transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }

    @Override
    public void fillInStackTrace(Throwable probe) {
        probe.fillInStackTrace();
    }

    @Override
    public void addTransformer(ClassFileTransformer transformer) {
        instrumentation.addTransformer(transformer);
    }
}
