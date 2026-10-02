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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isBootstrapClassLoader;

final class Installer {

    private Installer() {
    }

    static void install(Instrumentation instrumentation, AugmentorConfig config, FrameFormat format) {
        // Inlined advice needs neither the Nexus nor Unsafe-based class injection; turning them off avoids
        // the JDK's sun.misc.Unsafe warnings. In the shaded jar these property names are relocated, so they
        // do not affect a ByteBuddy copy used by the application itself.
        System.setProperty(Nexus.PROPERTY, "true");
        System.setProperty(ClassInjector.UsingUnsafe.SAFE_PROPERTY, "true");

        boolean javaLangOpen = openJavaBase(instrumentation);
        if (LiveStack.requested()) {
            String problem = javaLangOpen ? LiveStack.install(instrumentation, config, format) : "java.lang cannot be opened to the agent";
            if (problem == null) {
                // Classes instrumented at build time still call the handler: the live stack already covers their frames.
                Dispatch.install((self, thrown, owner, method, paramValues, paramNames) -> {
                });
                return;
            }
            Log.warn("agent: cannot read frames from the live stack, so classes are instrumented instead: " + problem);
        }
        Dispatch.install(new ThrowHandler(new IdResolver(config), format, stackTraces(javaLangOpen)));

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
                // Classes instrumented at build time already call Dispatch.
                .with(BuildTimeClasses::new)
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
     * Opens {@code java.lang} to the runtime and agent classes: the handler can then replace a frame in the throwable's
     * own stack trace instead of copying the whole trace twice for every frame, and the live-stack mode can read live
     * stack frames. Also lets {@code java.base} read the bridge, for the code the live-stack mode adds to
     * {@link Throwable}.
     */
    private static boolean openJavaBase(Instrumentation instrumentation) {
        Module javaBase = Throwable.class.getModule();
        Set<Module> agent = Stream.of(StackTraces.class.getModule(), Installer.class.getModule()).collect(Collectors.toSet());
        try {
            if (!instrumentation.isModifiableModule(javaBase)) {
                return false;
            }
            instrumentation.redefineModule(javaBase, Set.of(Dispatch.class.getModule()), Map.of(), Map.of("java.lang", agent), Set.of(),
                    Map.of());
            return true;
        } catch (RuntimeException e) {
            Log.debug(() -> "agent: java.lang cannot be opened, so stack traces are copied for every frame: " + e);
            return false;
        }
    }

    /** In place if {@code java.lang} is open, otherwise the copying way. */
    private static StackTraces stackTraces(boolean javaLangOpen) {
        if (!javaLangOpen) {
            return StackTraces.copying();
        }
        try {
            return StackTraces.inPlace();
        } catch (RuntimeException | IllegalAccessException e) {
            Log.debug(() -> "agent: stack traces are copied for every frame, because java.lang cannot be opened: " + e);
            return StackTraces.copying();
        }
    }
}
