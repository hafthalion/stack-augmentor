package com.hafnium.stackaugmentor.agent;

import com.hafnium.stackaugmentor.instrument.ConstructorExit;
import com.hafnium.stackaugmentor.instrument.ExitAdviceFactory;
import com.hafnium.stackaugmentor.instrument.IdParameters;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.FrameFormat;
import com.hafnium.stackaugmentor.runtime.IdResolver;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.StackTraces;
import com.hafnium.stackaugmentor.runtime.ThrowHandler;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.Nexus;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isBootstrapClassLoader;

final class Installer {

    private Installer() {
    }

    static void install(Instrumentation instrumentation, AugmentorConfig config, FrameFormat format) {
        Dispatch.install(new ThrowHandler(new IdResolver(config), format, stackTraces(instrumentation)));

        // Inlined advice needs neither the Nexus nor Unsafe-based class injection; turning them off avoids
        // the JDK's sun.misc.Unsafe warnings. In the shaded jar these property names are relocated, so they
        // do not affect a ByteBuddy copy used by the application itself.
        System.setProperty(Nexus.PROPERTY, "true");
        System.setProperty(ClassInjector.UsingUnsafe.SAFE_PROPERTY, "true");

        IdParameters parameters = new IdParameters(config);
        TypeMatching matching = new TypeMatching(config, parameters);
        Advice advice = ExitAdviceFactory.create(parameters);

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                // The advice is inlined, so no helper classes need to be injected into class loaders.
                .with(AgentBuilder.InjectionStrategy.Disabled.INSTANCE)
                // Transformations are logged by the transformer below; ByteBuddy only reports classes it failed on.
                .with(config.debug()
                        ? AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly()
                        : AgentBuilder.Listener.NoOp.INSTANCE)
                .assureReadEdgeTo(instrumentation, Dispatch.class)
                // The same types are left alone as by the build plugin.
                .ignore(TypeMatching.IGNORED)
                .or(any(), isBootstrapClassLoader())
                .type(matching::instrument)
                .transform((builder, type, classLoader, module, protectionDomain) -> {
                    ElementMatcher<MethodDescription> methods = matching.methods(type);
                    if (Log.isDebug()) {
                        Log.debug(() -> matching.describe(type, methods));
                    }
                    return builder.visit(advice.on(methods)).visit(ConstructorExit.on(parameters, matching.constructors(type)));
                })
                .installOn(instrumentation);
    }

    /**
     * Opens {@code java.lang} to the runtime classes, so that the handler can replace a frame in the throwable's own
     * stack trace instead of copying the whole trace twice for every frame. Without that, the copying way.
     */
    private static StackTraces stackTraces(Instrumentation instrumentation) {
        Module javaBase = Throwable.class.getModule();
        Module runtime = StackTraces.class.getModule();
        try {
            if (instrumentation.isModifiableModule(javaBase)) {
                instrumentation.redefineModule(javaBase, Set.of(), Map.of(), Map.of("java.lang", Set.of(runtime)), Set.of(), Map.of());
            }
            return StackTraces.inPlace();
        } catch (RuntimeException | IllegalAccessException e) {
            Log.debug(() -> "agent: stack traces are copied for every frame, because java.lang cannot be opened: " + e);
            return StackTraces.copying();
        }
    }
}
